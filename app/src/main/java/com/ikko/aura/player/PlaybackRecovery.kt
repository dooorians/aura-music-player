package com.ikko.aura.player

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.ikko.aura.playback.PlaybackFailure
import com.ikko.aura.playback.PlaybackRecoveryPolicy
import com.ikko.aura.playback.RecoveryAction
import com.ikko.aura.playback.TrackResolver
import com.ikko.aura.source.TrackKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Applies [PlaybackRecoveryPolicy] to the session's player. A track that cannot be opened used to leave the player
 * stopped on it: no move to the next, and nothing in the screens saying why.
 *
 * It only ever replaces the failed item's address or moves to the next one, and never removes an item, so the queue
 * the screens mirror stays in step with the player's own.
 */
class PlaybackRecovery(
    private val resolver: TrackResolver,
    private val policy: PlaybackRecoveryPolicy,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var player: Player? = null

    /** Attempts made on the item now playing, by mediaId. Cleared once an item plays. */
    private val attempts = HashMap<String, Int>()
    private var consecutiveItemFailures = 0

    /**
     * Follows the player the session is on: the crossfade hands the session to the other ExoPlayer at each transition,
     * so this has to move with it or it would go on listening to the one that has just gone quiet.
     */
    fun attach(next: Player) {
        if (player === next) return
        player?.removeListener(this)
        player = next
        next.addListener(this)
    }

    fun release() {
        player?.removeListener(this)
        player = null
    }

    override fun onPlayerError(error: PlaybackException) {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val index = p.currentMediaItemIndex
        val position = p.currentPosition
        val failure = PlaybackFailure.classify(error.errorCode)
        val attemptsOnItem = (attempts[item.mediaId] ?: 0) + 1
        attempts[item.mediaId] = attemptsOnItem

        scope.launch {
            val failedUri = item.localConfiguration?.uri?.toString()
            val alternatives = runCatching { resolver.candidates(TrackKey(item.mediaId), item.mediaMetadata.title?.toString(), item.mediaMetadata.artist?.toString()) }
                .getOrDefault(emptyList())
                .filterNot { it.uri == failedUri }
            // Repeat-one reports the same item as "next": skipping would only fail on it again.
            val hasNext = p.repeatMode != Player.REPEAT_MODE_ONE && p.hasNextMediaItem()
            val action = policy.decide(failure, attemptsOnItem, consecutiveItemFailures, alternatives, hasNext)
            // Whatever was decided is for the item that failed: if the listener has moved on since, leave it be.
            if (p.currentMediaItemIndex != index || p.currentMediaItem?.mediaId != item.mediaId) return@launch
            when (action) {
                is RecoveryAction.TryAlternative -> retryFrom(p, index, item, action.source.uri, position)
                RecoveryAction.SkipToNext -> {
                    consecutiveItemFailures++
                    p.seekToNextMediaItem()
                    p.prepare()
                    p.play()
                }
                RecoveryAction.Stop -> Unit
            }
        }
    }

    private fun retryFrom(p: Player, index: Int, item: MediaItem, uri: String, positionMs: Long) {
        p.replaceMediaItem(index, item.buildUpon().setUri(uri).build())
        p.prepare()
        p.seekTo(index, positionMs)
        p.play()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_READY) return
        consecutiveItemFailures = 0
        player?.currentMediaItem?.let { attempts.remove(it.mediaId) }
    }
}
