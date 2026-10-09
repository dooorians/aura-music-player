package com.ikko.aura.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ikko.aura.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * The app's single connection to the [Player] living inside [PlaybackService]. Every command and
 * every bit of state observation anywhere in Aura (the position ticker, the notification, the
 * lock screen) goes through the same [MediaController] here — there is no second, separate
 * `ExoPlayer` reference anywhere else in the process to drift out of sync with it, which is the
 * standard shape for a Media3 player (confirmed against BitChord's and Gramophone's own
 * MediaController-based UI layers, plus Google's own session samples).
 */
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext context: Context,
    private val audioSessionIdProvider: AudioSessionIdProvider,
) {
    val audioSessionId: Int get() = audioSessionIdProvider.id

    private var controller: MediaController? = null
    private val controllerReady = CompletableDeferred<MediaController>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionFailed = MutableStateFlow(false)

    /** True when the service could not be reached after every retry: no player to observe or command. */
    val connectionFailed: StateFlow<Boolean> = _connectionFailed.asStateFlow()

    init {
        connect(context, attempt = 0)
    }

    /**
     * Connects to the service, and tries again — a second, two seconds, four, eight — when it does not answer, instead
     * of letting the failure of `get()` take the app down. After the last try [awaitPlayer] fails and
     * [connectionFailed] is set.
     */
    private fun connect(context: Context, attempt: Int) {
        runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                val connected = try {
                    controllerFuture.get()
                } catch (e: Exception) {
                    null
                }
                when {
                    connected != null -> {
                        controller = connected
                        controllerReady.complete(connected)
                    }
                    attempt < MAX_CONNECT_RETRIES ->
                        mainHandler.postDelayed({ connect(context, attempt + 1) }, 1_000L shl attempt)
                    else -> {
                        _connectionFailed.value = true
                        controllerReady.completeExceptionally(IllegalStateException("The playback service did not answer"))
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /**
     * Resolves once the [MediaController] finishes its async connection (typically well under a
     * second after process start). For state observation, which must not miss anything the
     * controller publishes once attached.
     */
    suspend fun awaitPlayer(): Player = controllerReady.await()

    /**
     * Best-effort synchronous command target: `null` only in the brief window before
     * [controllerReady] resolves. A command tapped that early (before the app has even finished
     * launching its own UI) silently no-ops rather than falling back to some other player
     * reference — there is only ever one real [Player] in this process, the one behind
     * [controller].
     */
    private val player: Player? get() = controller

    var isShuffleActive: Boolean = false
        private set

    fun setShuffleActive(active: Boolean) {
        isShuffleActive = active
    }

    fun playQueue(tracks: List<Track>, startIndex: Int, startPositionMs: Long = 0L) {
        if (tracks.isEmpty()) return
        val player = player ?: return
        player.shuffleModeEnabled = false
        player.setMediaItems(tracks.map(::toMediaItem), startIndex, startPositionMs)
        player.prepare()
        player.play()
    }

    /**
     * Puts a saved queue back without playing it: loaded, and paused where it was left. [playQueue] always plays.
     */
    fun restoreQueue(tracks: List<Track>, startIndex: Int, startPositionMs: Long, shuffle: Boolean, repeatMode: Int) {
        if (tracks.isEmpty()) return
        val player = player ?: return
        isShuffleActive = shuffle
        player.shuffleModeEnabled = false
        player.repeatMode = repeatMode
        player.setMediaItems(tracks.map(::toMediaItem), startIndex, startPositionMs)
        player.prepare()
    }

    fun togglePlayPause() {
        val player = player ?: return
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekToDefaultPosition()
        }
        if (player.isPlaying) player.pause() else player.play()
    }

    /** Used by the sleep timer; everything else toggles. */
    fun pause() {
        player?.pause()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun skipNext() {
        player?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        val player = player ?: return
        if (player.currentPosition > SKIP_PREVIOUS_RESTART_THRESHOLD_MS) {
            player.seekTo(0)
        } else {
            player.seekToPreviousMediaItem()
        }
    }

    fun setShuffleEnabled(enabled: Boolean) {
        isShuffleActive = enabled
        player?.shuffleModeEnabled = false
    }

    /**
     * Reorders upcoming media items in ExoPlayer's timeline to match [targetIds], starting from
     * [startIndex] (typically `currentMediaItemIndex + 1`).
     *
     * The items before [startIndex] (including the currently playing item) are never moved or interrupted.
     */
    fun reorderUpcoming(startIndex: Int, targetIds: List<String>) {
        val player = player ?: return
        if (startIndex !in 0 until player.mediaItemCount) return
        val currentIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toMutableList()
        val limit = targetIds.size.coerceAtMost(currentIds.size)
        for (i in startIndex until limit) {
            val targetId = targetIds[i]
            val currSubIdx = currentIds.subList(i, currentIds.size).indexOf(targetId)
            if (currSubIdx != -1) {
                val actualIdx = i + currSubIdx
                if (actualIdx != i) {
                    player.moveMediaItem(actualIdx, i)
                    val item = currentIds.removeAt(actualIdx)
                    currentIds.add(i, item)
                }
            }
        }
    }

    fun setRepeatMode(@Player.RepeatMode mode: Int) {
        player?.repeatMode = mode
    }

    fun seekToQueueIndex(index: Int) {
        val player = player ?: return
        if (index in 0 until player.mediaItemCount) player.seekTo(index, 0L)
    }

    /**
     * Drops [tracks] in right after whatever is playing, so "lire ensuite" means the next thing
     * heard rather than the end of an hour-long queue.
     */
    fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val player = player ?: return
        if (player.mediaItemCount == 0) {
            playQueue(tracks, 0)
            return
        }
        player.addMediaItems(player.currentMediaItemIndex + 1, tracks.map(::toMediaItem))
    }

    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val player = player ?: return
        if (player.mediaItemCount == 0) {
            playQueue(tracks, 0)
            return
        }
        player.addMediaItems(tracks.map(::toMediaItem))
    }

    fun moveInQueue(from: Int, to: Int) {
        val player = player ?: return
        if (from == to) return
        if (from !in 0 until player.mediaItemCount) return
        player.moveMediaItem(from, to.coerceIn(0, player.mediaItemCount - 1))
    }

    /**
     * Empties the queue apart from what is playing.
     *
     * Keeping the current track is what "vider la file" means everywhere it exists: you are
     * clearing what is *coming*, not stopping the song you are listening to. Removing everything
     * would stop playback, which is a different action and one nobody reaches for from a queue.
     */
    fun clearQueueExceptCurrent() {
        val player = player ?: return
        val keep = player.currentMediaItemIndex
        if (player.mediaItemCount <= 1) return
        if (keep + 1 < player.mediaItemCount) player.removeMediaItems(keep + 1, player.mediaItemCount)
        if (keep > 0) player.removeMediaItems(0, keep)
    }

    fun removeFromQueue(index: Int) {
        val player = player ?: return
        if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
    }

    /**
     * Attaches real artwork bytes to the current [MediaItem] so the lock-screen/notification can
     * show it. [Track.artworkUri] is the audio file's own content URI (there's no reliable image
     * URI for MediaStore audio on modern Android), so it can't be handed to
     * [MediaMetadata.Builder.setArtworkUri] directly — Media3's notification builder does a plain
     * bitmap decode on whatever URI it's given, and decoding an audio stream as an image fails
     * silently (confirmed via logcat: "Could not decode image data"). [bitmap] is expected to
     * already be decoded via the same [android.content.ContentResolver.loadThumbnail] path
     * [com.ikko.aura.ui.components.AlbumArtCache] uses elsewhere in the app, which does know how
     * to pull a thumbnail out of an audio file.
     *
     * [mediaId] is checked against the current item before applying: the bitmap load this
     * follows is async, so a fast skip could otherwise land this track's artwork on whatever the
     * user has since skipped to.
     */
    suspend fun updateItemArtwork(mediaId: String, bitmap: Bitmap) {
        // Once per item. Replacing the playing item is not free: the player announces a playlist change, the view model
        // reads a "new" current track, reads its palette, and lands back here. With repeat-one on, the two items the loop
        // bounced between (after the queue was restored on a launch) kept replacing each other's artwork for ever — the
        // position bar, the lyrics and the names flickered twice a second. An item that already carries its cover has
        // nothing to be replaced with.
        player?.let { p ->
            val i = p.currentMediaItemIndex
            if (i in 0 until p.mediaItemCount) {
                val item = p.getMediaItemAt(i)
                if (item.mediaId == mediaId && item.mediaMetadata.artworkData != null) return
            }
        }
        // JPEG encoding a cover is tens of milliseconds and used to run on the caller's thread —
        // which was the main one, so every track change spent that budget inside a frame. Only
        // the Media3 call afterwards has to be on the main thread.
        val bytes = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                stream.toByteArray()
            }
        }
        val player = player ?: return
        val index = player.currentMediaItemIndex
        if (index !in 0 until player.mediaItemCount) return
        val current = player.getMediaItemAt(index)
        if (current.mediaId != mediaId) return
        val updatedMetadata = current.mediaMetadata.buildUpon()
            .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        player.replaceMediaItem(index, current.buildUpon().setMediaMetadata(updatedMetadata).build())
    }

    private fun toMediaItem(track: Track): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.contentUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .build(),
            )
            .build()

    companion object {
        private const val SKIP_PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
        private const val MAX_CONNECT_RETRIES = 4
    }
}
