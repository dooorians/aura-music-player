package com.ikko.aura.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import com.ikko.aura.ui.components.AlbumTheme
import com.ikko.aura.ui.components.albumThemeFromBitmap
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.RecentlyPlayedTracker
import com.ikko.aura.data.SettingsStore
import com.ikko.aura.data.model.AudioOutputState
import com.ikko.aura.data.model.Track
import com.ikko.aura.player.AudioOutputMonitor
import com.ikko.aura.player.PlayerController
import com.ikko.aura.player.QueueSnapshotStore
import com.ikko.aura.ui.components.AlbumArtCache
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.source.ytm.YtmLibrary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RepeatMode { OFF, ALL, ONE }

/**
 * See [LibraryUiState]'s doc comment — same `List<T>` stability-inference issue applies here.
 *
 * `positionMs` and `durationMs` are deliberately absent: they tick every 500 ms during playback,
 * and including them here would force every collector of [PlayerUiState] to recompose on every
 * tick even if it only cares about track metadata or queue state. They live in dedicated flows
 * ([positionMs] and [durationMs]) so only the composables that actually display the playback
 * position subscribe to them.
 */
@Immutable
data class PlayerUiState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isFavorite: Boolean = false,
    val meshColors: List<Color> = AuraColors.DefaultMesh,
    /** The one colour the cover is "about", for the tint at the top of every screen. */
    val primaryColor: Color = AuraColors.DefaultPrimary,
    val audioOutput: AudioOutputState = AudioOutputState(),
    val automixEnabled: Boolean = false,
    /** What the app's background is lit with: the playing cover's colours. */
    val albumTheme: AlbumTheme = AlbumTheme.Neutral,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerController: PlayerController,
    private val audioOutputMonitor: AudioOutputMonitor,
    private val recentlyPlayedTracker: RecentlyPlayedTracker,
    private val musicRepository: MusicRepository,
    private val queueSnapshotStore: QueueSnapshotStore,
    private val settingsStore: SettingsStore,
    private val playlistRepository: com.ikko.aura.data.playlist.PlaylistRepository,
    private val ytmLibrary: com.ikko.aura.source.ytm.YtmLibrary,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    private var paletteJob: Job? = null

    /** Set once [PlayerController.awaitPlayer] resolves; every read below happens after that. */
    private var boundPlayer: Player? = null

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** Isolated from [uiState] so only the composables that display the seek bar and lyrics subscribe. */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** Isolated for the same reason: only [WavyProgressBar] and the lyrics clock need it. */
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /** Wakes the position ticker below at once, rather than at the end of its paused sleep. */
    private val tickerWake = Channel<Unit>(Channel.CONFLATED)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            tickerWake.trySend(Unit)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
            tickerWake.trySend(Unit)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            tickerWake.trySend(Unit)
            val player = boundPlayer ?: return
            val index = player.currentMediaItemIndex
            val track = _uiState.value.queue.getOrNull(index)
            // MediaController re-delivers this on every periodic session resync, not just on a
            // genuine track change — observed firing dozens of times per second with the same
            // track/reason=REPEAT. Without this guard each of those spuriously reset positionMs
            // back to 0, freezing the displayed position even though real playback (confirmed via
            // dumpsys) kept advancing normally the whole time. Compared against currentTrack
            // rather than queueIndex: queueIndex is set synchronously by playQueue() before this
            // first fires for real, so comparing against it would also skip that genuine event.
            if (track?.id == _uiState.value.currentTrack?.id) return
            _uiState.update {
                it.copy(
                    currentTrack = track,
                    queueIndex = index,
                )
            }
            _positionMs.value = 0L
            _durationMs.value = player.duration.coerceAtLeast(0L)
            track?.let(::extractPalette)
            track?.let { recentlyPlayedTracker.recordPlayed(it.albumId, it.id) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _uiState.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _uiState.update { it.copy(repeatMode = repeatMode.toRepeatMode()) }
        }
    }

    init {
        viewModelScope.launch {
            val player = playerController.awaitPlayer()
            boundPlayer = player
            adoptExistingQueue(player)
            restoreSavedQueue(player)
            player.addListener(playerListener)
            // One continuous ticker for the ViewModel's lifetime rather than one restarted from
            // onIsPlayingChanged: isPlaying only flips on genuine play/pause, not on every track
            // change (seekToNextMediaItem keeps it true throughout), so an event-triggered ticker
            // only ever starts once per app session — any race that drops that single start (e.g.
            // a transient buffering blip right at the first play() call) leaves position frozen
            // for the rest of the session with no further event to recover it.
            while (isActive) {
                if (player.isPlaying) {
                    _positionMs.value = player.currentPosition.coerceAtLeast(0L)
                    _durationMs.value = player.duration.coerceAtLeast(0L)
                }
                reconcileCurrentItem(player)
                // Paused, nothing moves: rather than waking twice a second for the whole time the app is
                // open, it sleeps until the player says something changed (play, pause, a new item, a
                // state change), and still looks in every few seconds in case an event never came.
                if (player.isPlaying) {
                    kotlinx.coroutines.delay(POSITION_TICK_MS)
                } else {
                    withTimeoutOrNull(PAUSED_TICK_MS) { tickerWake.receive() }
                }
            }
        }
        viewModelScope.launch {
            settingsStore.automixEnabled.collect { enabled ->
                _uiState.update { it.copy(automixEnabled = enabled) }
            }
        }
        // The heart shows whether the track playing is in Favoris — for each track, and kept on disk. It used to be
        // a flag in memory only, the same for every track and gone when the app closed.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                _uiState.map { it.currentTrack?.id }.distinctUntilChanged(),
                playlistRepository.favoriteTrackIds,
            ) { id, favorites -> id != null && id in favorites }
                .collect { favorite -> _uiState.update { it.copy(isFavorite = favorite) } }
        }
        audioOutputMonitor.start()
        viewModelScope.launch {
            audioOutputMonitor.state.collect { output ->
                _uiState.update { it.copy(audioOutput = output) }
            }
        }
    }

    /**
     * Pulls the playing item back off the player when a listener callback did not report it.
     *
     * [PlaybackService][com.ikko.aura.player.PlaybackService] runs two ExoPlayers so an Automix
     * transition can overlap two tracks, and the queue moving on during one is not an item
     * transition on a single player — the incoming track starts as its *own* player's first
     * item, and the session is then moved onto it. [onMediaItemTransition] can legitimately
     * never fire for that change. Rather than guess which callback a given Media3 version
     * surfaces the swap through, the ticker that is already running reconciles: same comparison
     * the listener makes, so a genuine transition still costs one update and a steady state
     * costs none.
     */
    private fun reconcileCurrentItem(player: Player) {
        val index = player.currentMediaItemIndex
        val track = _uiState.value.queue.getOrNull(index) ?: return
        val state = _uiState.value
        if (track.id == state.currentTrack?.id && index == state.queueIndex) return
        _uiState.update {
            it.copy(
                currentTrack = track,
                queueIndex = index,
            )
        }
        _positionMs.value = player.currentPosition.coerceAtLeast(0L)
        _durationMs.value = player.duration.coerceAtLeast(0L)
        extractPalette(track)
        recentlyPlayedTracker.recordPlayed(track.albumId, track.id)
    }

    /**
     * Rebuilds this view model's picture of the queue from whatever the player is already doing.
     *
     * The service outlives the UI: close Aura while a track is playing and the foreground service
     * keeps going, but the activity — and this view model with it — is built again from nothing on
     * the way back. [queue] was in memory only, so it came back empty, [currentTrack] stayed null,
     * and the pill never appeared over music that was audibly still playing. Nothing short of
     * starting a new queue brought it back.
     *
     * The player's media items carry ids, not tracks, so they are resolved against the library.
     * That waits for the first scan to land — hence [MusicRepository.tracks]'s first non-empty
     * emission rather than a snapshot, which on a cold start is empty for a second or two.
     */
    private suspend fun adoptExistingQueue(player: Player) {
        if (player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val byId = (musicRepository.tracks.first { it.isNotEmpty() } + ytmLibrary.known()).associateBy { it.id }
        // This view model's queue mirrors the player's position for position, so an item the library does not know
        // (a file deleted since the queue was written) is taken out of the player as well, not just left out here:
        // dropped on this side only, every index after it would point at the wrong track.
        ids.indices.reversed().filter { ids[it] !in byId }.forEach { player.removeMediaItem(it) }
        val queue = ids.mapNotNull { byId[it] }
        if (queue.isEmpty()) return
        val index = player.currentMediaItemIndex.coerceIn(0, queue.lastIndex)
        _uiState.update {
            it.copy(
                queue = queue,
                queueIndex = index,
                currentTrack = queue[index],
                isPlaying = player.isPlaying,
            )
        }
        _positionMs.value = player.currentPosition.coerceAtLeast(0L)
        _durationMs.value = player.duration.coerceAtLeast(0L)
        queue[index].let(::extractPalette)
    }

    /**
     * Brings back the queue that was playing when the process was killed — loaded and paused where it was left, as the
     * pill and the lock-screen notification show it — when the player has nothing of its own to adopt.
     *
     * Resolved against the library first, then handed to the player and kept here as the very same list: what the
     * library no longer has is left out of both, so the two stay in step. Waits for the first scan like
     * [adoptExistingQueue].
     */
    private suspend fun restoreSavedQueue(player: Player) {
        if (player.mediaItemCount > 0) return
        val saved = queueSnapshotStore.read() ?: return
        val byId = (musicRepository.tracks.first { it.isNotEmpty() } + ytmLibrary.known()).associateBy { it.id }
        val snapshot = saved.surviving { it in byId } ?: return
        // Something may have started playing while the library was loading (a media button, say): that is the queue
        // then, and not this one — adopted here, since the first look found the player empty.
        if (player.mediaItemCount > 0) {
            adoptExistingQueue(player)
            return
        }
        val queue = snapshot.items.mapNotNull { byId[it.id] }
        if (queue.size != snapshot.items.size) return
        val track = queue[snapshot.index]
        _uiState.update {
            it.copy(
                queue = queue,
                queueIndex = snapshot.index,
                currentTrack = track,
                isPlaying = false,
                shuffleEnabled = snapshot.shuffle,
                repeatMode = snapshot.repeat.toRepeatMode(),
            )
        }
        _positionMs.value = snapshot.positionMs
        _durationMs.value = track.durationMs
        playerController.restoreQueue(queue, snapshot.index, snapshot.positionMs, snapshot.shuffle, snapshot.repeat)
        extractPalette(track)
    }

    /** Streamed songs of a queue are remembered as they enter it, so a playlist, a heart or a restored queue can name them. */
    private fun rememberStreamed(tracks: List<Track>) {
        if (tracks.none(YtmLibrary::isStreamed)) return
        viewModelScope.launch(Dispatchers.IO) { runCatching { ytmLibrary.remember(tracks) } }
    }

    /** The streamed songs kept in the library. */
    val keptStreamedIds: StateFlow<Set<String>> = ytmLibrary.observeKeptIds()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Remembered first when a streamed song is about to be named by something else (the add-to-playlist dialog). */
    suspend fun rememberForUse(tracks: List<Track>) = withContext(Dispatchers.IO) { runCatching { ytmLibrary.remember(tracks) } }

    fun keepStreamed(tracks: List<Track>) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { ytmLibrary.keep(tracks) } }
    }

    fun unkeepStreamed(tracks: List<Track>) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { ytmLibrary.unkeep(tracks.map { it.id }) } }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        rememberStreamed(tracks)
        _uiState.update { it.copy(queue = tracks, queueIndex = startIndex) }
        playerController.playQueue(tracks, startIndex)
    }

    fun togglePlayPause() = playerController.togglePlayPause()
    fun skipNext() = playerController.skipNext()
    fun skipPrevious() = playerController.skipPrevious()
    fun seekTo(positionMs: Long) {
        playerController.seekTo(positionMs)
        _positionMs.value = positionMs
    }

    /**
     * Automix, from the player rather than only from Réglages. It changes how the *next* track
     * arrives, which is a decision you make while listening, not while configuring.
     */
    fun toggleAutomix() {
        viewModelScope.launch { settingsStore.setAutomixEnabled(!_uiState.value.automixEnabled) }
    }

    fun toggleShuffle() = playerController.setShuffleEnabled(!_uiState.value.shuffleEnabled)

    fun jumpToQueueIndex(index: Int) = playerController.seekToQueueIndex(index)

    fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        rememberStreamed(tracks)
        playerController.playNext(tracks)
        _uiState.update { state ->
            if (state.queue.isEmpty()) {
                state.copy(queue = tracks, queueIndex = 0)
            } else {
                val at = (state.queueIndex + 1).coerceIn(0, state.queue.size)
                state.copy(queue = state.queue.toMutableList().apply { addAll(at, tracks) })
            }
        }
    }

    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        rememberStreamed(tracks)
        playerController.addToQueue(tracks)
        _uiState.update { state ->
            if (state.queue.isEmpty()) {
                state.copy(queue = tracks, queueIndex = 0)
            } else {
                state.copy(queue = state.queue + tracks)
            }
        }
    }

    /**
     * Moves a queued track. The local list is reordered alongside the player's so the screen
     * that asked for the move does not wait a tick to show it, and [queueIndex] follows the
     * *playing* track rather than staying on a slot whose occupant has changed.
     */
    fun moveInQueue(from: Int, to: Int) {
        if (from == to) return
        playerController.moveInQueue(from, to)
        _uiState.update { state ->
            val queue = state.queue.toMutableList()
            if (from !in queue.indices) return@update state
            val target = to.coerceIn(0, queue.lastIndex)
            queue.add(target, queue.removeAt(from))
            val playing = when {
                state.queueIndex == from -> target
                from < state.queueIndex && target >= state.queueIndex -> state.queueIndex - 1
                from > state.queueIndex && target <= state.queueIndex -> state.queueIndex + 1
                else -> state.queueIndex
            }
            state.copy(queue = queue, queueIndex = playing)
        }
    }

    /**
     * What to do once the system has confirmed a deletion: take the tracks out of the library and
     * out of the queue, then reconcile with MediaStore.
     *
     * Both halves matter. Left in the queue, a deleted file is a row that plays silence and a
     * track change that fails; left in the library, it is a row that does nothing when tapped.
     */
    fun onTracksDeleted(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val ids = tracks.mapTo(mutableSetOf()) { it.id }
        musicRepository.forget(ids)
        _uiState.value.queue
            .withIndex()
            .filter { it.value.id in ids }
            .sortedByDescending { it.index }
            .forEach { removeFromQueue(it.index) }
        viewModelScope.launch { musicRepository.refresh(forceRescan = true) }
    }

    fun clearQueue() {
        playerController.clearQueueExceptCurrent()
        _uiState.update { state ->
            val current = state.queue.getOrNull(state.queueIndex) ?: return@update state
            state.copy(queue = listOf(current), queueIndex = 0)
        }
    }

    fun removeFromQueue(index: Int) {
        playerController.removeFromQueue(index)
        _uiState.update { state ->
            val newQueue = state.queue.toMutableList().apply { if (index in indices) removeAt(index) }
            val newIndex = if (index < state.queueIndex) state.queueIndex - 1 else state.queueIndex
            state.copy(queue = newQueue, queueIndex = newIndex)
        }
    }

    fun cycleRepeatMode() {
        val next = when (_uiState.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        playerController.setRepeatMode(next.toPlayerRepeatMode())
    }

    /** Adds the track playing to Favoris, or takes it out. The heart answers at once; the playlist follows. */
    fun toggleFavorite() {
        val id = _uiState.value.currentTrack?.id ?: return
        _uiState.update { it.copy(isFavorite = !it.isFavorite) }
        viewModelScope.launch { playlistRepository.toggleFavorite(id) }
    }

    /** Routes playback to one of the outputs in [PlayerUiState.audioOutput], or back to the system's. */
    fun selectAudioOutput(deviceId: Int?) = audioOutputMonitor.select(deviceId)

    private fun extractPalette(track: Track) {
        val artworkUri = track.artworkUri ?: run {
            _uiState.update { it.copy(meshColors = AuraColors.DefaultMesh, albumTheme = AlbumTheme.Neutral) }
            return
        }
        // Cancels the previous one: swiping through the queue used to start a decode, a palette
        // pass and a JPEG encode for every track flicked past, all landing at once on the same
        // cores the UI and render threads need. Waiting for the track to hold still means only the
        // one you actually stopped on does that work.
        paletteJob?.cancel()
        paletteJob = viewModelScope.launch {
            delay(PALETTE_DEBOUNCE_MS)
            // Keyed by album and loaded small. This used to take the cache's 512px default under
            // a per-*track* key, so every track change decoded a fresh 1MB bitmap and pushed the
            // library's own thumbnails out of the LruCache — which then had to decode themselves
            // again. An atrace of five swipes counted 290 decodes. A palette and a notification
            // cover don't need more than this.
            val bitmap = AlbumArtCache.load(
                context = appContext,
                uri = artworkUri,
                targetSizePx = PALETTE_SIZE_PX,
                cacheKey = track.albumId.toString(),
            )
            val palette = bitmap?.let { readPalette(it, track.albumId.hashCode()) }
            _uiState.update {
                it.copy(
                    meshColors = palette?.mesh ?: AuraColors.DefaultMesh,
                    primaryColor = palette?.primary ?: AuraColors.DefaultPrimary,
                    albumTheme = palette?.theme ?: AlbumTheme.Neutral,
                )
            }
            bitmap?.let { playerController.updateItemArtwork(track.id, it) }
        }
    }

    private class CoverPalette(val mesh: List<Color>, val primary: Color, val theme: AlbumTheme)

    private suspend fun readPalette(bitmap: Bitmap, seed: Int): CoverPalette = withContext(Dispatchers.Default) {
        // A backdrop gradient needs a handful of dominant colours, not a full 16-swatch
        // analysis of every pixel; both knobs cut the pass to a fraction of its default cost.
        val palette = Palette.from(bitmap)
            .maximumColorCount(8)
            .resizeBitmapArea(96 * 96)
            .generate()
        val mesh = listOfNotNull(
            palette.vibrantSwatch?.rgb,
            palette.darkVibrantSwatch?.rgb,
            palette.mutedSwatch?.rgb,
            palette.darkMutedSwatch?.rgb ?: palette.dominantSwatch?.rgb,
        ).map { Color(it) }.ifEmpty { AuraColors.DefaultMesh }

        // Vibrant before dominant: the dominant swatch of a cover that is mostly white paper is
        // white, which says nothing about the record. The vibrant one is the colour someone would
        // name if you asked them what the sleeve looks like.
        val primary = (
            palette.vibrantSwatch
                ?: palette.darkVibrantSwatch
                ?: palette.mutedSwatch
                ?: palette.dominantSwatch
            )?.rgb?.let { Color(it) } ?: AuraColors.DefaultPrimary

        CoverPalette(mesh, primary, albumThemeFromBitmap(bitmap, seed))
    }

    override fun onCleared() {
        audioOutputMonitor.stop()
        boundPlayer?.removeListener(playerListener)
        super.onCleared()
    }

    companion object {
        private const val POSITION_TICK_MS = 500L
        private const val PAUSED_TICK_MS = 5_000L
        /**
         * The same size the backdrop loads at, deliberately — and therefore the same cache
         * entry. At 256 this decoded the identical cover a second time on every track change,
         * ~35ms of MediaProvider round trip traced per call, for numbers that are then computed
         * over a 96x96 resample anyway. The notification's own thumbnail is encoded from this
         * too, and does not need more.
         */
        private const val PALETTE_SIZE_PX = 192

        /**
         * Long enough that a flick through the queue never runs it, and — since the backdrop
         * settles at 320ms — late enough not to land its decode, its Palette pass, its JPEG
         * encode and the notification rebuild that follows on the same frames as the backdrop
         * swap, which is the most expensive frame the player has.
         */
        private const val PALETTE_DEBOUNCE_MS = 520L
    }
}

private fun Int.toRepeatMode(): RepeatMode = when (this) {
    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    else -> RepeatMode.OFF
}

private fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
}
