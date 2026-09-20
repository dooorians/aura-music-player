package com.ikko.aura.player

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pauses playback after a set time.
 *
 * Deliberately a pause and not a stop: falling asleep to an album and finding the queue emptied
 * in the morning is worse than finding it where you left it, and pause is the one outcome that
 * can be undone by pressing play.
 *
 * The deadline is held as [SystemClock.elapsedRealtime], which counts through deep sleep — a
 * wall-clock or uptime deadline would drift or freeze exactly when this is meant to be running.
 * The coroutine itself is not what makes the timer accurate, though: it is a plain delay, so the
 * pause lands when the process next runs. For a sleep timer, a few seconds either way is not
 * something anyone is awake to notice.
 */
@Singleton
class SleepTimer @Inject constructor(
    private val playerController: PlayerController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** When the music stops, on [SystemClock.elapsedRealtime]'s clock, or null when nothing is set. */
    private val _endsAtMs = MutableStateFlow<Long?>(null)

    fun start(durationMs: Long) {
        if (durationMs <= 0) return
        job?.cancel()
        _endsAtMs.value = SystemClock.elapsedRealtime() + durationMs
        job = scope.launch {
            delay(durationMs)
            playerController.pause()
            _endsAtMs.value = null
        }
    }

    /** Whatever is left, or null when no timer is running. */
    fun remainingMs(): Long? = _endsAtMs.value?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }

    fun cancel() {
        job?.cancel()
        job = null
        _endsAtMs.value = null
    }
}
