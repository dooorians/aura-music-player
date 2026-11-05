package com.ikko.aura.player.sleep

/** What the sleep timer needs to know about playback at one instant. */
data class SleepPlayback(
    /** Playing or meant to be (true while buffering). False once paused. */
    val playWhenReady: Boolean,
    val positionMs: Long,
    /** The current track's length, or null when unknown (a live stream, nothing loaded yet). */
    val durationMs: Long?,
    /** Playback reached the end of the queue. */
    val ended: Boolean = false,
) {
    /** Time left in the current track, or null when its length is unknown. */
    val trackRemainingMs: Long?
        get() = durationMs?.takeIf { it > 0 }?.let { (it - positionMs).coerceAtLeast(0L) }
}

sealed interface SleepTimerState {
    /** No timer. */
    data object Idle : SleepTimerState

    /**
     * Counting down to [deadlineMs].
     *
     * With [finishTrack], the deadline only picks the last track: the music stops at the end of
     * whichever track is playing when it passes, never in the middle of one.
     */
    data class Counting(val deadlineMs: Long, val finishTrack: Boolean) : SleepTimerState

    /** The deadline has picked this track as the last: stop when it ends. */
    data class FinishingTrack(val deadlineMs: Long) : SleepTimerState
}

/** What to do right now: the volume to play at, and whether to pause. */
data class SleepStep(val gain: Float, val pauseNow: Boolean) {
    companion object {
        val UNTOUCHED = SleepStep(gain = 1f, pauseNow = false)
        val STOP = SleepStep(gain = 0f, pauseNow = true)
    }
}

/**
 * The sleep timer's decisions, apart from any clock, player or coroutine: given the time and the
 * state of playback, what volume and whether to stop. [com.ikko.aura.player.SleepTimer] feeds it.
 *
 * The music always fades out over the last [fadeMs] before it stops, whether that is the deadline
 * itself or the end of the last track.
 *
 * All times are on one monotonic clock (the caller uses `SystemClock.elapsedRealtime`).
 */
class SleepTimerMachine(val fadeMs: Long = DEFAULT_FADE_MS) {

    init {
        require(fadeMs > 0) { "fadeMs must be positive" }
    }

    var state: SleepTimerState = SleepTimerState.Idle
        private set

    /** Time left in the track at the last [step], or null when unknown. */
    private var lastTrackRemainingMs: Long? = null

    fun start(nowMs: Long, durationMs: Long, finishTrack: Boolean) {
        require(durationMs > 0) { "durationMs must be positive" }
        state = SleepTimerState.Counting(nowMs + durationMs, finishTrack)
    }

    fun cancel() {
        state = SleepTimerState.Idle
        lastTrackRemainingMs = null
    }

    /** Advances the timer to [nowMs]. [playback] is null when no player is connected. */
    fun step(nowMs: Long, playback: SleepPlayback?): SleepStep {
        lastTrackRemainingMs = playback?.trackRemainingMs
        return when (val current = state) {
            SleepTimerState.Idle -> SleepStep.UNTOUCHED
            is SleepTimerState.Counting -> stepCounting(current, nowMs, playback)
            is SleepTimerState.FinishingTrack -> stepFinishing(current, nowMs, playback)
        }
    }

    /**
     * The current track changed. [userInitiated] when the listener chose it (a skip, a new queue):
     * then the track they picked becomes the one to finish. Otherwise the last track has played
     * out, and this returns true: pause now.
     *
     * Inside the last track's fade, any change counts as its end, whatever it was reported as. A
     * crossfade hands the session from one player to the other a few seconds before the end, and
     * that handoff need not be reported as a track running out; resuming the next track at full
     * volume there would wake the listener the fade was for.
     */
    fun onTrackTransition(userInitiated: Boolean): Boolean {
        if (state !is SleepTimerState.FinishingTrack) return false
        val inFade = lastTrackRemainingMs?.let { it <= fadeMs } ?: false
        if (userInitiated && !inFade) return false
        state = SleepTimerState.Idle
        return true
    }

    /** What to show as time left, or null without a timer. */
    fun remainingMs(nowMs: Long, playback: SleepPlayback?): Long? {
        val untilDeadline = { deadline: Long -> (deadline - nowMs).coerceAtLeast(0L) }
        return when (val current = state) {
            SleepTimerState.Idle -> null
            is SleepTimerState.Counting -> {
                val trackLeft = playback?.trackRemainingMs
                if (current.finishTrack && trackLeft != null && nowMs + trackLeft >= current.deadlineMs) trackLeft
                else untilDeadline(current.deadlineMs)
            }
            is SleepTimerState.FinishingTrack -> playback?.trackRemainingMs ?: untilDeadline(current.deadlineMs)
        }
    }

    private fun stepCounting(current: SleepTimerState.Counting, nowMs: Long, playback: SleepPlayback?): SleepStep {
        val trackLeft = playback?.trackRemainingMs
        if (!current.finishTrack || trackLeft == null) return towardDeadline(current.deadlineMs, nowMs)
        if (nowMs + trackLeft < current.deadlineMs) return SleepStep.UNTOUCHED
        // The deadline falls inside this track.
        if (!playback.playWhenReady) {
            // Paused: the countdown goes on; once it is through the deadline the music has
            // already stopped, so there is nothing left to do.
            if (nowMs >= current.deadlineMs) state = SleepTimerState.Idle
            return SleepStep.UNTOUCHED
        }
        state = SleepTimerState.FinishingTrack(current.deadlineMs)
        return SleepStep(fadeGain(trackLeft), pauseNow = false)
    }

    private fun stepFinishing(current: SleepTimerState.FinishingTrack, nowMs: Long, playback: SleepPlayback?): SleepStep {
        if (playback?.ended == true) {
            state = SleepTimerState.Idle
            return SleepStep.STOP
        }
        if (playback != null && !playback.playWhenReady) {
            // Paused by hand on the last track: whoever did that is awake, and playback is already stopped.
            state = SleepTimerState.Idle
            return SleepStep.UNTOUCHED
        }
        // A track of unknown length cannot be finished: fall back to the deadline.
        val trackLeft = playback?.trackRemainingMs ?: return towardDeadline(current.deadlineMs, nowMs)
        return SleepStep(fadeGain(trackLeft), pauseNow = false)
    }

    private fun towardDeadline(deadlineMs: Long, nowMs: Long): SleepStep {
        val left = deadlineMs - nowMs
        if (left <= 0) {
            state = SleepTimerState.Idle
            return SleepStep.STOP
        }
        return SleepStep(fadeGain(left), pauseNow = false)
    }

    /**
     * Volume with [remainingMs] to go: full until the last [fadeMs], then down to nothing along a
     * square curve. A straight line in amplitude sounds like it barely moves for most of the fade
     * and then drops away at the end; the square spends the fade more evenly across what is heard.
     */
    fun fadeGain(remainingMs: Long): Float {
        if (remainingMs >= fadeMs) return 1f
        if (remainingMs <= 0) return 0f
        val x = remainingMs.toFloat() / fadeMs
        return x * x
    }

    companion object {
        const val DEFAULT_FADE_MS = 30_000L
    }
}
