package com.ikko.aura.player.silence

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What to do about a confirmed trailing silence, given where the playhead is. Pure, for tests.
 */
internal object TrailingSilencePolicy {

    sealed interface Decision {
        /** The playhead is in the silence: move on now. */
        data object Skip : Decision

        /** Not there yet: look again in [ms]. */
        data class Wait(val ms: Long) : Decision

        /** The silence no longer applies (another track, sound came back, or someone else owns the transition). */
        data object Drop : Decision
    }

    fun decide(
        found: EdgeSilenceTrimProcessor.TrailingSilence,
        stillConfirmed: Boolean,
        currentMediaId: String?,
        positionMs: Long,
        isPlaying: Boolean,
        mayIntervene: Boolean,
    ): Decision = when {
        !stillConfirmed || !mayIntervene || currentMediaId != found.mediaId -> Decision.Drop
        positionMs >= found.silenceStartMs -> if (isPlaying) Decision.Skip else Decision.Wait(PAUSED_RECHECK_MS)
        !isPlaying -> Decision.Wait(PAUSED_RECHECK_MS)
        else -> Decision.Wait((found.silenceStartMs - positionMs).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS))
    }

    private const val MIN_WAIT_MS = 15L
    private const val MAX_WAIT_MS = 1_000L
    private const val PAUSED_RECHECK_MS = 500L
}

/**
 * Starts the next track as soon as the playing one's trailing silence is reached.
 *
 * The trimmer runs ahead of what is heard (by the sink's buffer, a few hundred milliseconds), so it
 * usually confirms the silence before the playhead gets there; this then waits for the playhead
 * and moves on the moment the music has actually stopped. Nothing runs between silences: the
 * trimmer calls in when it finds one.
 *
 * Steps aside whenever [mayIntervene] says so — while a crossfade can be armed, the crossfade owns
 * the end of the track and Automix already places its cue before the silence.
 */
@UnstableApi
class TrailingSilenceSkipper(
    private val scope: CoroutineScope,
    private val mayIntervene: () -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var trimmer: EdgeSilenceTrimProcessor? = null
    private var job: Job? = null

    /** Follows [target], whose sink runs [processor]. Called again at every crossfade handoff. */
    fun attach(target: ExoPlayer, processor: EdgeSilenceTrimProcessor) {
        if (player === target && trimmer === processor) return
        trimmer?.onTrailingSilence = null
        job?.cancel()
        player = target
        trimmer = processor
        processor.onTrailingSilence = { found -> main.post { follow(target, processor, found) } }
        processor.trailing?.let { follow(target, processor, it) }
    }

    fun release() {
        trimmer?.onTrailingSilence = null
        job?.cancel()
        job = null
        player = null
        trimmer = null
    }

    private fun follow(target: ExoPlayer, processor: EdgeSilenceTrimProcessor, found: EdgeSilenceTrimProcessor.TrailingSilence) {
        if (target !== player) return
        job?.cancel()
        job = scope.launch {
            while (isActive && target === player) {
                val decision = TrailingSilencePolicy.decide(
                    found = found,
                    stillConfirmed = processor.trailing == found,
                    currentMediaId = target.currentMediaItem?.mediaId,
                    positionMs = target.currentPosition,
                    isPlaying = target.isPlaying,
                    mayIntervene = SilenceTrim.enabled && mayIntervene(),
                )
                when (decision) {
                    TrailingSilencePolicy.Decision.Drop -> return@launch
                    TrailingSilencePolicy.Decision.Skip -> {
                        skip(target)
                        return@launch
                    }
                    is TrailingSilencePolicy.Decision.Wait -> delay(decision.ms)
                }
            }
        }
    }

    private fun skip(target: ExoPlayer) {
        SilenceTrim.markAutoSkip(SystemClock.elapsedRealtime())
        if (target.hasNextMediaItem()) {
            target.seekToNextMediaItem()
        } else {
            // Last track: end it now, exactly as if it had played out.
            val duration = target.duration
            if (duration > 0) target.seekTo(duration)
        }
    }
}
