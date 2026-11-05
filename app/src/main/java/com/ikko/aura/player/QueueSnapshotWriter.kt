package com.ikko.aura.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.ikko.aura.playback.QueueSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps [QueueSnapshotStore] up to date with the session's player: when the queue changes, the track changes, playback
 * pauses, and every few seconds while it plays, so a killed process loses at most a few seconds of position.
 *
 * It never writes an empty queue. At start-up the player is empty until something puts the queue back, and writing
 * that would erase the very snapshot about to be read.
 */
class QueueSnapshotWriter(
    private val store: QueueSnapshotStore,
    private val settingsStore: com.ikko.aura.data.SettingsStore,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var player: Player? = null
    private var pending: Job? = null
    private var ticker: Job? = null
    private var shuffleActive: Boolean = false

    init {
        scope.launch {
            settingsStore.shuffleEnabled.collect {
                shuffleActive = it
                schedule(DEBOUNCE_MS)
            }
        }
    }

    /** The items, rebuilt only when the queue itself changes: a position tick should not walk the whole queue again. */
    private var entries: List<QueueSnapshot.Entry> = emptyList()
    private var entriesDirty = true

    /** Follows the player the session is on, as PlaybackRecovery does: see PlaybackService.adoptPlayer. */
    fun attach(next: Player) {
        if (player === next) return
        player?.removeListener(this)
        ticker?.cancel()
        player = next
        next.addListener(this)
        entriesDirty = true
        if (next.isPlaying) startTicker()
        schedule(DEBOUNCE_MS)
    }

    /**
     * Stops following the player, after a last write taken now, while the player still answers. Without it a queue
     * change still inside its [DEBOUNCE_MS], and up to a [POSITION_TICK_MS] of position, were lost with the service. The
     * write runs outside [scope], which the service is about to cancel.
     */
    fun release() {
        pending?.cancel()
        ticker?.cancel()
        val last = runCatching { capture() }.getOrNull()
        player?.removeListener(this)
        player = null
        if (last != null) store.writeDetached(last)
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        entriesDirty = true
        schedule(DEBOUNCE_MS)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = schedule(DEBOUNCE_MS)

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = schedule(DEBOUNCE_MS)

    override fun onRepeatModeChanged(repeatMode: Int) = schedule(DEBOUNCE_MS)

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            startTicker()
        } else {
            ticker?.cancel()
            // Paused or stopped: the position is where it will be picked up from.
            schedule(0L)
        }
    }

    private fun rebuildEntries() {
        val p = player ?: return
        entries = (0 until p.mediaItemCount).map { i ->
            val item = p.getMediaItemAt(i)
            QueueSnapshot.Entry(
                id = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
            )
        }
        entriesDirty = false
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(POSITION_TICK_MS)
                capture()?.let { store.write(it) }
            }
        }
    }

    private fun schedule(after: Long) {
        pending?.cancel()
        pending = scope.launch {
            delay(after)
            capture()?.let { store.write(it) }
        }
    }

    private fun capture(): QueueSnapshot? {
        val p = player ?: return null
        if (entriesDirty) rebuildEntries()
        return snapshotOf(entries, p.mediaItemCount, p.currentMediaItemIndex, p.currentPosition, shuffleActive, p.repeatMode)
    }

    internal companion object {
        /**
         * The snapshot for a player in this state, or null when it should not be written: an empty queue (it would
         * erase the one about to be restored) or items not yet rebuilt for the queue the player now has. A player
         * between items reports an index past the end and a position below zero; neither is written as such.
         */
        fun snapshotOf(
            entries: List<QueueSnapshot.Entry>,
            itemCount: Int,
            index: Int,
            positionMs: Long,
            shuffle: Boolean,
            repeat: Int,
        ): QueueSnapshot? {
            if (itemCount == 0 || entries.size != itemCount) return null
            return QueueSnapshot(
                items = entries,
                index = index.coerceIn(0, entries.lastIndex),
                positionMs = positionMs.coerceAtLeast(0L),
                shuffle = shuffle,
                repeat = repeat,
            )
        }

        private const val DEBOUNCE_MS = 600L
        private const val POSITION_TICK_MS = 10_000L
    }
}
