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
    /** One stable key per entry of [queue], same order (see [QueueKeys]): the same song can be queued twice. */
    val queueKeys: List<Long> = emptyList(),
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
    private val ytmDownloads: com.ikko.aura.source.ytm.YtmDownloads,
    private val listeningRecorder: com.ikko.aura.reco.ListeningRecorder,
    private val recommendations: com.ikko.aura.reco.RecommendationEngine,
    private val albumResolver: com.ikko.aura.source.StreamedAlbumResolver,
    private val smartEngine: com.ikko.aura.reco.SmartRecommendationEngine,
    private val ytmClient: com.ikko.aura.source.ytm.YtmClient,
    private val remoteSources: com.ikko.aura.source.remote.RemoteSources,
    private val jioSaavnClient: com.ikko.aura.source.jiosaavn.JioSaavnClient,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    /**
     * The recommended playlist (its [com.ikko.aura.reco.RecoKind.key]) the queue was started from, if it was: a skip
     * made in "Nuit" is evidence about "Nuit", not about the listener's taste in general.
     */
    private var listeningContext: String? = null
    /** The song the listener started from the interface and that has not been left yet: its listen counts as chosen. */
    private var manualTrackId: String? = null
    private var refilling = false

    /**
     * Automix keeps the queue going: with fewer than three songs to come, the recommendations add the next ones (the library's,
     * chosen on the listener's taste and on Last.fm, and a share of streamed ones when online). Never on the main thread.
     */
    private fun refillIfNeeded() {
        val state = _uiState.value
        if (!state.automixEnabled || refilling || state.currentTrack == null) return
        val remaining = state.queue.size - state.queueIndex - 1
        if (remaining >= com.ikko.aura.reco.RecommendationConfig.REFILL_BELOW) return
        refilling = true
        viewModelScope.launch {
            try {
                val next = withContext(Dispatchers.Default) {
                    runCatching { smartEngine.nextTracks(state.currentTrack, com.ikko.aura.reco.RecommendationConfig.REFILL_COUNT, allowStreaming = true) }
                        .getOrDefault(emptyList())
                }
                // Not if something else was started meanwhile.
                if (next.isNotEmpty() && _uiState.value.currentTrack?.id == state.currentTrack.id) addToQueue(next)
            } finally { refilling = false }
        }
    }

    private var paletteJob: Job? = null

    /** Set once [PlayerController.awaitPlayer] resolves; every read below happens after that. */
    private var boundPlayer: Player? = null

    /**
     * Where playback is right now, read off the player itself rather than from the twice-a-second value the screens
     * observe. The lyrics ask it on every frame to choose the line and to sweep a word, so a line changes when it is
     * sung and not up to half a second after.
     */
    /** How long ago the last beat of the music being heard was; null until its tempo has been found. */
    fun sinceBeat(): Double? = com.ikko.aura.player.beat.BeatGrids.sinceBeat()

    fun livePositionMs(): Long = boundPlayer?.currentPosition?.coerceAtLeast(0L) ?: _positionMs.value

    private val entryKeys = QueueKeys()
    private var originalQueue: List<Track> = emptyList()
    private var originalQueueKeys: List<Long> = emptyList()
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** The album page of a streamed song, looked up when the song does not carry its album (see [com.ikko.aura.source.StreamedAlbumResolver]). */
    suspend fun streamedAlbumRouteOf(track: Track): String? = albumResolver.routeOf(track)

    /** Whether the song playing is one the listener liked (the player's heart, which feeds the recommendations). */
    val isLiked: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(
        _uiState.map { it.currentTrack?.id }.distinctUntilChanged(),
        recommendations.likedTrackIds,
    ) { id, liked -> id != null && id in liked }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The songs of an album of an external source, by its reference (a YouTube Music playlist id, or `<source>:<id>`): what the
     * menu of an album that is not in the library acts on. Empty when the source does not answer.
     */
    suspend fun streamedAlbumTracks(ref: String): List<Track> = withContext(Dispatchers.IO) {
        runCatching {
            if (ref.contains(':')) {
                val source = ref.substringBefore(':')
                val id = ref.substringAfter(':')
                val client = remoteSources.client(source) ?: return@runCatching emptyList<Track>()
                val page = client.album(id)
                page.songs.map { song ->
                    com.ikko.aura.source.remote.RemoteTracks.trackOf(
                        source, song.copy(albumId = id, album = page.album.title.ifEmpty { song.album }), client,
                    )
                }
            } else {
                ytmClient.album(ref).songs.map(com.ikko.aura.source.ytm.YtmLibrary::trackOf)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * "Lancer la radio": the song plays at once, and what resembles it is added behind it as soon as a source has said what:
     * what JioSaavn and YouTube Music list as close to it, else the artist's other songs of the library.
     */
    fun startRadio(track: Track) {
        // The song and some of the artist's own, at once: a queue of one song would be filled by the automix before the radio added anything.
        val key = com.ikko.aura.reco.ArtistNames.primaryKey(track.artist)
        val ownNow = musicRepository.tracks.value.filter { it.id != track.id && isOfArtist(it, key) }.shuffled().take(8)
        playQueue(listOf(track) + ownNow, 0)
        val shown = ownNow.mapTo(hashSetOf(track.id)) { it.id }
        viewModelScope.launch(Dispatchers.IO) {
            val own = runCatching { artistOwnSongs(track.artist) }.getOrDefault(emptyList()).filter { it.id !in shown }.shuffled()
            val close = runCatching { radioCandidates(track) }.getOrDefault(emptyList()).filter { it.id !in shown && !isOfArtist(it, key) }
            val more = com.ikko.aura.reco.RadioMix.mix(own, close).distinctBy { it.id }.take(RADIO_SIZE)
            if (more.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                // Not if something else was started meanwhile.
                if (_uiState.value.currentTrack?.id == track.id) addToQueue(more)
            }
        }
    }

    /** Whether [t] is by the artist whose key is [key], as its first or second credit. */
    private fun isOfArtist(t: Track, key: String): Boolean =
        key.isNotEmpty() && key in com.ikko.aura.reco.ArtistNames.creditsOf(t.artist, t.title).take(2)

    /** The songs of [artist] the library and the sources have, so that their radio is theirs. */
    private suspend fun artistOwnSongs(artist: String): List<Track> {
        val key = com.ikko.aura.reco.ArtistNames.primaryKey(artist)
        val library = musicRepository.tracks.first().filter { isOfArtist(it, key) }
        val online = if (ytmClient.isEnabled()) {
            runCatching {
                ytmClient.artistSongs(com.ikko.aura.reco.ArtistNames.primary(artist, beforeAmpersand = true))
                    .map(com.ikko.aura.source.ytm.YtmLibrary::trackOf).filter { isOfArtist(it, key) }
            }.getOrDefault(emptyList())
        } else emptyList()
        return (library + online).distinctBy { com.ikko.aura.reco.TrackIdentity.key(it.title, it.artist) }
    }

    /**
     * The radio of one artist (a station of Home): their songs lead and play at once, with a little of what is close to them between
     * them. [found] is what the engine already found for it.
     */
    fun startArtistRadio(artist: String, found: List<Track>, context: String?) {
        val key = com.ikko.aura.reco.ArtistNames.primaryKey(artist)
        viewModelScope.launch(Dispatchers.IO) {
            val ownFound = found.filter { isOfArtist(it, key) }.distinctBy { it.id }
            // Their songs are looked for before the queue starts, when the engine found few: a queue started with what it had would be
            // other artists' after the first three, and the automix would fill it before the radio could add theirs.
            val extra = if (ownFound.size >= RADIO_OWN_ENOUGH) emptyList() else
                kotlinx.coroutines.withTimeoutOrNull(5_000L) { runCatching { artistOwnSongs(artist) }.getOrDefault(emptyList()) }.orEmpty()
            val own = (ownFound + extra).distinctBy { it.id }.shuffled()
            val queue = com.ikko.aura.reco.RadioMix.mix(own, found.filterNot { isOfArtist(it, key) }.distinctBy { it.id }).take(RADIO_SIZE)
            if (queue.isEmpty()) return@launch
            val shown = queue.mapTo(HashSet()) { it.id }
            withContext(Dispatchers.Main) { playQueue(queue, 0, context = context) }
            // A little of what is close to them, from the source, behind it.
            val close = runCatching { radioCandidates(queue.first()) }.getOrDefault(emptyList())
                .filter { it.id !in shown && !isOfArtist(it, key) }.distinctBy { it.id }.take(RADIO_SIZE / 2)
            if (close.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                // Not if something else was started meanwhile.
                if (_uiState.value.currentTrack?.id in shown) addToQueue(close)
            }
        }
    }

    private suspend fun radioCandidates(t: Track): List<Track> {
        if (t.id.startsWith("jiosaavn:")) {
            return jioSaavnClient.recommendationsOrThrow(t.id.substringAfter(':')).map(com.ikko.aura.source.jiosaavn.JioSaavnTracks::trackOf)
        }
        if (ytmClient.isEnabled()) {
            val videoId = if (t.id.startsWith("ytm:")) t.id.substringAfter(':')
            else ytmClient.search("${t.title} ${t.artist}").songs.firstOrNull()?.videoId
            if (videoId != null) {
                val related = ytmClient.related(videoId).map(com.ikko.aura.source.ytm.YtmLibrary::trackOf)
                if (related.isNotEmpty()) return related
            }
        }
        val library = musicRepository.tracks.first()
        return library.filter { it.artist == t.artist }.shuffled() + library.shuffled().take(RADIO_SIZE)
    }

    /** Every song the listener liked (the long-press menu's "J'aime"). */
    val likedTrackIds: StateFlow<Set<String>> = recommendations.likedTrackIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Likes any song, or takes its like back. */
    /** The albums liked as a whole, by every key each answers to (see [com.ikko.aura.reco.AlbumKeys]). */
    val likedAlbumKeys: StateFlow<Set<String>> = recommendations.likedAlbumKeys
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Likes an album as a whole, or takes the like back: it counts for the album even when none of its songs is known. */
    fun toggleLikeAlbum(ref: String?, title: String, artist: String, tracks: List<Track>) {
        viewModelScope.launch {
            if (com.ikko.aura.reco.AlbumKeys.of(ref, title, artist).any { it in likedAlbumKeys.value }) recommendations.unlikeAlbum(ref, title, artist, tracks)
            else recommendations.likeAlbum(ref, title, artist, tracks, listeningContext)
        }
    }

    fun toggleLikeOf(track: Track) {
        viewModelScope.launch {
            if (track.id in likedTrackIds.value) recommendations.unlike(track) else recommendations.like(track, listeningContext)
        }
    }

    /** "Je n'aime pas": the song is not proposed again. */
    fun dislike(track: Track) {
        viewModelScope.launch { recommendations.dislike(track, listeningContext) }
    }

    /** "Je n'aime pas" on an album of a source: all its songs, and the album itself, are not proposed again. */
    fun dislikeAlbum(ref: String?, title: String, artist: String, tracks: List<Track>) {
        viewModelScope.launch {
            recommendations.dislikeAlbum(ref, title, artist, tracks, listeningContext)
            // What the listener refused weighs in the profile now: the playlists are made again without it, with something
            // else of their taste in its place.
            runCatching { recommendations.refreshNow() }
        }
    }

    /** "Je n'aime pas" in the player: the song goes at once, is never proposed again, and counts against it in the profile. */
    fun dislikeCurrent() {
        val track = _uiState.value.currentTrack ?: return
        dislike(track)
        playerController.skipNext()
    }

    /** Likes the song playing, or takes the like back. The playlist it was started from counts as its context. */
    fun toggleLike() {
        val track = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            if (isLiked.value) recommendations.unlike(track) else recommendations.like(track, listeningContext)
        }
    }

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
            val mediaId = mediaItem?.mediaId ?: player.currentMediaItem?.mediaId
            val track = _uiState.value.queue.getOrNull(index)?.takeIf { mediaId == null || it.id == mediaId }
                ?: _uiState.value.queue.firstOrNull { it.id == mediaId }
            if (track == null || track.id == _uiState.value.currentTrack?.id) return
            // The song just left: how much of it was heard, and how it was left.
            _uiState.value.currentTrack?.let { left ->
                val transition = when (reason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> com.ikko.aura.reco.Transition.AUTO
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> com.ikko.aura.reco.Transition.USER_SKIP
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> com.ikko.aura.reco.Transition.QUEUE_REPLACED
                    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> com.ikko.aura.reco.Transition.REPEAT
                    else -> com.ikko.aura.reco.Transition.UNKNOWN
                }
                listeningRecorder.songLeft(left, _positionMs.value, _durationMs.value, transition, listeningContext, manualStart = left.id == manualTrackId)
                if (left.id == manualTrackId) manualTrackId = null
            }
            listeningRecorder.songStarted(track)
            _uiState.update {
                it.copy(
                    currentTrack = track,
                    queueIndex = index.coerceIn(0, (it.queue.size - 1).coerceAtLeast(0)),
                )
            }
            refillIfNeeded()
            _positionMs.value = 0L
            _durationMs.value = player.duration.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET }
                ?: track.durationMs.coerceAtLeast(0L)
            extractPalette(track)
            recentlyPlayedTracker.recordPlayed(track.albumForPlays(), track.id)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // Intentionally no-op: ExoPlayer's internal shuffle mode is kept disabled.
            // Queue-based shuffle state is managed directly via PlayerViewModel.
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _uiState.update { it.copy(repeatMode = repeatMode.toRepeatMode()) }
        }
    }

    init {
        // A download queue the process was killed in the middle of picks up again.
        viewModelScope.launch(Dispatchers.IO) { runCatching { ytmDownloads.resume() } }
        viewModelScope.launch {
            // No connection after the retries (see PlayerController): nothing to observe, and not a crash.
            val player = try {
                playerController.awaitPlayer()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            boundPlayer = player
            // Listening and ticking start now. Rebuilding the queue may wait for the device's first scan (when it holds
            // local files), and the screens must not wait with it.
            player.addListener(playerListener)
            launch {
                adoptExistingQueue(player)
                restoreSavedQueue(player)
            }
            // One continuous ticker for the ViewModel's lifetime rather than one restarted from
            // onIsPlayingChanged: isPlaying only flips on genuine play/pause, not on every track
            // change (seekToNextMediaItem keeps it true throughout), so an event-triggered ticker
            // only ever starts once per app session — any race that drops that single start (e.g.
            // a transient buffering blip right at the first play() call) leaves position frozen
            // for the rest of the session with no further event to recover it.
            while (isActive) {
                val expectedId = _uiState.value.currentTrack?.id
                val playerMediaId = player.currentMediaItem?.mediaId
                if (expectedId == null || playerMediaId == expectedId) {
                    _positionMs.value = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration
                    if (dur > 0L && dur != androidx.media3.common.C.TIME_UNSET) {
                        _durationMs.value = dur
                    }
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
        viewModelScope.launch {
            settingsStore.shuffleEnabled.collect { enabled ->
                _uiState.update { it.copy(shuffleEnabled = enabled) }
                playerController.setShuffleActive(enabled)
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

    private fun reconcileCurrentItem(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val index = player.currentMediaItemIndex
        val track = _uiState.value.queue.getOrNull(index)?.takeIf { it.id == mediaId } ?: return
        val state = _uiState.value
        if (track.id == state.currentTrack?.id && index == state.queueIndex) return
        // The change was not announced by the listener's callback (two players during a mix): how the song was left is not known.
        if (track.id != state.currentTrack?.id) {
            state.currentTrack?.let { left ->
                listeningRecorder.songLeft(left, _positionMs.value, _durationMs.value, com.ikko.aura.reco.Transition.UNKNOWN, listeningContext, manualStart = left.id == manualTrackId)
                if (left.id == manualTrackId) manualTrackId = null
            }
        }
        _uiState.update {
            it.copy(
                currentTrack = track,
                queueIndex = index,
            )
        }
        _positionMs.value = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration
        _durationMs.value = if (dur > 0L && dur != androidx.media3.common.C.TIME_UNSET) dur else track.durationMs.coerceAtLeast(0L)
        extractPalette(track)
        recentlyPlayedTracker.recordPlayed(track.albumForPlays(), track.id)
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
        val byId = resolveQueueEntries(
            ids = ids,
            idOf = Track::id,
            streamed = { ytmLibrary.known() },
            local = { musicRepository.tracks.first { it.isNotEmpty() } },
        )
        // This view model's queue mirrors the player's position for position, so an item the library does not know
        // (a file deleted since the queue was written) is taken out of the player as well, not just left out here:
        // dropped on this side only, every index after it would point at the wrong track.
        ids.indices.reversed().filter { ids[it] !in byId }.forEach { player.removeMediaItem(it) }
        val queue = ids.mapNotNull { byId[it] }
        if (queue.isEmpty()) return
        val index = player.currentMediaItemIndex.coerceIn(0, queue.lastIndex)
        val keys = entryKeys.fresh(queue.size)
        originalQueue = queue
        originalQueueKeys = keys
        _uiState.update {
            it.copy(
                queue = queue,
                queueKeys = keys,
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
        val byId = resolveQueueEntries(
            ids = saved.items.map { it.id },
            idOf = Track::id,
            streamed = { ytmLibrary.known() },
            local = { musicRepository.tracks.first { it.isNotEmpty() } },
        )
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
        val keys = entryKeys.fresh(queue.size)
        originalQueue = queue
        originalQueueKeys = keys
        _uiState.update {
            it.copy(
                queue = queue,
                queueKeys = keys,
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

    /** Taking a streamed song out of the library takes its downloaded file with it. */
    fun unkeepStreamed(tracks: List<Track>) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ytmDownloads.remove(tracks.map { it.id }) }
            runCatching { ytmLibrary.unkeep(tracks.map { it.id }) }
        }
    }

    /** Every streamed song's download state, for the pages that show it. */
    val downloadStates: StateFlow<Map<String, com.ikko.aura.source.ytm.DownloadEntry>> = ytmDownloads.entries
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun downloadStreamed(tracks: List<Track>) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { ytmDownloads.enqueue(tracks) } }
    }

    /**
     * Deleting a downloaded song deletes it: its file goes and so does its place in the library. A song that only
     * lost its file stayed in the titles (as a stream) while its album, which lists downloads, disappeared.
     */
    fun removeDownloads(tracks: List<Track>) = unkeepStreamed(tracks)

    fun playQueue(
        tracks: List<Track>,
        startIndex: Int,
        shuffle: Boolean = false,
        context: String? = null,
        alreadyShuffled: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        listeningContext = context
        rememberStreamed(tracks)
        originalQueue = tracks
        val keys = entryKeys.fresh(tracks.size)
        originalQueueKeys = keys

        val safeStartIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val startTrack = tracks[safeStartIndex]
        val prevTrack = _uiState.value.currentTrack
        if (prevTrack != null && prevTrack.id != startTrack.id) {
            listeningRecorder.songLeft(prevTrack, _positionMs.value, _durationMs.value, com.ikko.aura.reco.Transition.QUEUE_REPLACED, listeningContext, manualStart = prevTrack.id == manualTrackId)
        }
        // A queue started from the interface: the song it opens on was chosen by the listener, not reached by the queue.
        manualTrackId = startTrack.id
        _positionMs.value = 0L
        _durationMs.value = startTrack.durationMs.coerceAtLeast(0L)

        if (shuffle) {
            val newQueue: List<Track>
            val newKeys: List<Long>
            val newIndex: Int
            if (alreadyShuffled) {
                newQueue = tracks
                newKeys = keys
                newIndex = safeStartIndex
            } else {
                val startKey = keys[safeStartIndex]
                val remainderIndices = tracks.indices.filter { it != safeStartIndex }.shuffled()
                newQueue = listOf(startTrack) + remainderIndices.map { tracks[it] }
                newKeys = listOf(startKey) + remainderIndices.map { keys[it] }
                newIndex = 0
            }
            _uiState.update {
                it.copy(
                    currentTrack = startTrack,
                    isPlaying = true,
                    queue = newQueue,
                    queueKeys = newKeys,
                    queueIndex = newIndex,
                    shuffleEnabled = true,
                )
            }
            extractPalette(startTrack)
            recentlyPlayedTracker.recordPlayed(startTrack.albumForPlays(), startTrack.id)
            playerController.setShuffleActive(true)
            viewModelScope.launch { settingsStore.setShuffleEnabled(true) }
            playerController.playQueue(newQueue, newIndex)
            refillIfNeeded()
        } else {
            _uiState.update {
                it.copy(
                    currentTrack = startTrack,
                    isPlaying = true,
                    queue = tracks,
                    queueKeys = keys,
                    queueIndex = safeStartIndex,
                    shuffleEnabled = false,
                )
            }
            extractPalette(startTrack)
            recentlyPlayedTracker.recordPlayed(startTrack.albumForPlays(), startTrack.id)
            playerController.setShuffleActive(false)
            viewModelScope.launch { settingsStore.setShuffleEnabled(false) }
            playerController.playQueue(tracks, safeStartIndex)
            refillIfNeeded()
        }
    }

    fun playShuffled(tracks: List<Track>, startIndex: Int? = null, context: String? = null) {
        if (tracks.isEmpty()) return
        val start = startIndex?.takeIf { it in tracks.indices } ?: tracks.indices.random()
        playQueue(tracks, start, shuffle = true, context = context)
    }

    fun togglePlayPause() = playerController.togglePlayPause()
    fun skipNext() = playerController.skipNext()
    fun skipPrevious() = playerController.skipPrevious()
    fun seekTo(positionMs: Long) {
        // Against the player's own length for the track now loaded, not the one last copied into the UI state.
        val length = boundPlayer?.duration?.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET }
        val target = if (length != null) positionMs.coerceIn(0L, length - 1L) else positionMs.coerceAtLeast(0L)
        playerController.seekTo(target)
        _positionMs.value = target
        if (length != null) _durationMs.value = length
    }

    /**
     * Automix, from the player rather than only from Réglages. It changes how the *next* track
     * arrives, which is a decision you make while listening, not while configuring.
     */
    fun toggleAutomix() {
        viewModelScope.launch { settingsStore.setAutomixEnabled(!_uiState.value.automixEnabled) }
    }

    fun toggleShuffle() {
        val state = _uiState.value
        val currentQueue = state.queue
        if (currentQueue.isEmpty()) return
        val currentIndex = state.queueIndex
        val upcomingStart = currentIndex + 1
        val willEnable = !state.shuffleEnabled

        if (willEnable) {
            if (originalQueue.isEmpty() || originalQueue.size != currentQueue.size) {
                originalQueue = currentQueue
                originalQueueKeys = state.queueKeys
            }
            if (upcomingStart < currentQueue.size) {
                val pastAndCurrent = currentQueue.take(upcomingStart)
                val pastAndCurrentKeys = state.queueKeys.take(upcomingStart)

                val upcoming = currentQueue.drop(upcomingStart)
                val upcomingKeys = state.queueKeys.drop(upcomingStart)
                val shuffledIndices = upcoming.indices.shuffled()

                val newUpcoming = shuffledIndices.map { upcoming[it] }
                val newUpcomingKeys = shuffledIndices.map { upcomingKeys[it] }

                val newQueue = pastAndCurrent + newUpcoming
                val newKeys = pastAndCurrentKeys + newUpcomingKeys

                _uiState.update {
                    it.copy(
                        queue = newQueue,
                        queueKeys = newKeys,
                        shuffleEnabled = true,
                    )
                }
                playerController.setShuffleActive(true)
                viewModelScope.launch { settingsStore.setShuffleEnabled(true) }
                playerController.replaceUpcoming(upcomingStart, newUpcoming)
            } else {
                _uiState.update { it.copy(shuffleEnabled = true) }
                playerController.setShuffleActive(true)
                viewModelScope.launch { settingsStore.setShuffleEnabled(true) }
            }
        } else {
            // Turning shuffle OFF: restore upcoming tracks to their original order
            if (upcomingStart < currentQueue.size && originalQueue.isNotEmpty()) {
                val pastAndCurrent = currentQueue.take(upcomingStart)
                val pastAndCurrentKeys = state.queueKeys.take(upcomingStart)

                val upcoming = currentQueue.drop(upcomingStart)
                val upcomingKeys = state.queueKeys.drop(upcomingStart)
                val origKeyOrder = originalQueueKeys.withIndex().associate { it.value to it.index }

                val restoredPairs = upcoming.zip(upcomingKeys).sortedBy { (_, key) ->
                    origKeyOrder[key] ?: Int.MAX_VALUE
                }

                val restoredUpcoming = restoredPairs.map { it.first }
                val newQueue = pastAndCurrent + restoredUpcoming
                val newKeys = pastAndCurrentKeys + restoredPairs.map { it.second }

                _uiState.update {
                    it.copy(
                        queue = newQueue,
                        queueKeys = newKeys,
                        shuffleEnabled = false,
                    )
                }
                playerController.setShuffleActive(false)
                viewModelScope.launch { settingsStore.setShuffleEnabled(false) }
                playerController.replaceUpcoming(upcomingStart, restoredUpcoming)
            } else {
                _uiState.update { it.copy(shuffleEnabled = false) }
                playerController.setShuffleActive(false)
                viewModelScope.launch { settingsStore.setShuffleEnabled(false) }
            }
        }
    }

    fun jumpToQueueIndex(index: Int) = playerController.seekToQueueIndex(index)

    fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        rememberStreamed(tracks)
        playerController.playNext(tracks)
        _uiState.update { state ->
            if (state.queue.isEmpty()) {
                val keys = entryKeys.fresh(tracks.size)
                originalQueue = tracks
                originalQueueKeys = keys
                state.copy(queue = tracks, queueKeys = keys, queueIndex = 0)
            } else {
                val at = (state.queueIndex + 1).coerceIn(0, state.queue.size)
                val newKeys = entryKeys.inserted(state.queueKeys, at, tracks.size)
                val newQueue = state.queue.toMutableList().apply { addAll(at, tracks) }
                if (originalQueue.isNotEmpty()) {
                    originalQueue = originalQueue.toMutableList().apply { addAll(at.coerceIn(0, size), tracks) }
                    originalQueueKeys = entryKeys.inserted(originalQueueKeys, at.coerceIn(0, originalQueueKeys.size), tracks.size)
                }
                state.copy(
                    queue = newQueue,
                    queueKeys = newKeys,
                )
            }
        }
    }

    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        rememberStreamed(tracks)
        playerController.addToQueue(tracks)
        _uiState.update { state ->
            if (state.queue.isEmpty()) {
                val keys = entryKeys.fresh(tracks.size)
                originalQueue = tracks
                originalQueueKeys = keys
                state.copy(queue = tracks, queueKeys = keys, queueIndex = 0)
            } else {
                val newKeys = entryKeys.appended(state.queueKeys, tracks.size)
                val newQueue = state.queue + tracks
                if (originalQueue.isNotEmpty()) {
                    originalQueue = originalQueue + tracks
                    originalQueueKeys = entryKeys.appended(originalQueueKeys, tracks.size)
                }
                state.copy(queue = newQueue, queueKeys = newKeys)
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
            state.copy(queue = queue, queueKeys = entryKeys.moved(state.queueKeys, from, to), queueIndex = playing)
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
            val key = state.queueKeys.keyAt(state.queueIndex)
            originalQueue = listOf(current)
            originalQueueKeys = listOf(key)
            state.copy(queue = listOf(current), queueKeys = listOf(key), queueIndex = 0)
        }
    }

    fun removeFromQueue(index: Int) {
        playerController.removeFromQueue(index)
        _uiState.update { state ->
            val removedKey = state.queueKeys.getOrNull(index)
            if (removedKey != null && originalQueueKeys.isNotEmpty()) {
                val origIdx = originalQueueKeys.indexOf(removedKey)
                if (origIdx != -1) {
                    originalQueue = originalQueue.toMutableList().apply { removeAt(origIdx) }
                    originalQueueKeys = originalQueueKeys.toMutableList().apply { removeAt(origIdx) }
                }
            }
            val newQueue = state.queue.toMutableList().apply { if (index in indices) removeAt(index) }
            val newIndex = if (index < state.queueIndex) state.queueIndex - 1 else state.queueIndex
            state.copy(queue = newQueue, queueKeys = entryKeys.removed(state.queueKeys, index), queueIndex = newIndex)
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
            // The notification's and lock screen's cover is not the palette's 192 px picture (which made it soft whatever the
            // source): it is read at its own size, and cut to a square — a video's 16:9 frame holds its art in the middle.
            val cover = AlbumArtCache.load(
                context = appContext,
                uri = artworkUri,
                targetSizePx = NOTIFICATION_COVER_PX,
                cacheKey = track.albumId.toString(),
                shrink = true,
            ) ?: bitmap
            cover?.let {
                val squaredCover = withContext(Dispatchers.Default) { squared(it) }
                playerController.updateItemArtwork(track.id, squaredCover)
            }
            // While the song plays, its album page is found in the background: a tap on its title then opens at once.
            if (YtmLibrary.isStreamed(track) && track.remoteAlbumId == null) {
                delay(ALBUM_WARM_DELAY_MS)
                runCatching { albumResolver.warm(track) }
            }
        }
    }

    /** [source] cut to the square at its centre (itself when it already is one). */
    private fun squared(source: Bitmap): Bitmap {
        if (source.width == source.height) return source
        val side = minOf(source.width, source.height)
        return runCatching {
            Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        }.getOrDefault(source)
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

        /** What the notification's cover is read at: the largest the system draws it (a lock screen's, full width). */
        private const val NOTIFICATION_COVER_PX = 1024

        /**
         * Long enough that a flick through the queue never runs it, and — since the backdrop
         * settles at 320ms — late enough not to land its decode, its Palette pass, its JPEG
         * encode and the notification rebuild that follows on the same frames as the backdrop
         * swap, which is the most expensive frame the player has.
         */
        private const val PALETTE_DEBOUNCE_MS = 120L
        private const val ALBUM_WARM_DELAY_MS = 1_500L
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

/** The album a play counts for: a streamed song that is not known through an album has none. */
private fun Track.albumForPlays(): Long = if (YtmLibrary.isStreamed(this) && remoteAlbumId == null) 0L else albumId

private const val RADIO_SIZE = 40

/** With this many songs of the artist found, their radio does not wait to look for more. */
private const val RADIO_OWN_ENOUGH = 8
