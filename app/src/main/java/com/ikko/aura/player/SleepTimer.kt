package com.ikko.aura.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.ikko.aura.player.silence.SilenceTrim
import com.ikko.aura.player.sleep.SleepFade
import com.ikko.aura.player.sleep.SleepPlayback
import com.ikko.aura.player.sleep.SleepStep
import com.ikko.aura.player.sleep.SleepTimerMachine
import com.ikko.aura.player.sleep.SleepTimerState
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pauses playback after a set time, fading out over the last 30 seconds.
 *
 * With `finishCurrentTrack` (the default), the deadline only chooses the last track: the music
 * stops at the end of whichever track is playing when it passes, never halfway through a song,
 * and the fade runs over that track's last 30 seconds. Without it, the music stops at the
 * deadline itself.
 *
 * Deliberately a pause and not a stop: falling asleep to an album and finding the queue emptied
 * in the morning is worse than finding it where you left it, and pause is the one outcome that
 * can be undone by pressing play. A paused player gives up its wake and Wi-Fi locks (see the
 * players' wake mode in [PlaybackService]) and the service leaves the foreground, so once the timer
 * has fired nothing is left holding the phone awake — the timer itself holds no lock and has no
 * coroutine left running.
 *
 * The decisions are [SleepTimerMachine]'s; this class only feeds it the clock and the player, and
 * carries out what it says. The volume goes through [SleepFade] into the audio sink rather than
 * through the player's volume, which the crossfade owns.
 *
 * The deadline is held as [SystemClock.elapsedRealtime], which counts through deep sleep. While
 * music plays the player's wake lock keeps the process running, so the fade lands on time; while
 * paused there is nothing to stop, and the timer simply finishes when the process next runs.
 */
@Singleton
class SleepTimer @Inject constructor(
    private val playerController: PlayerController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val machine = SleepTimerMachine()
    private var job: Job? = null
    private var player: Player? = null

    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Idle)

    /** The timer's state, for anything that wants to show more than the minutes left. */
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A seek is the listener's choice — unless it was the silence trimmer moving on from a
            // track that had finished. A new queue is always the listener's choice.
            val userInitiated = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> !SilenceTrim.isRecentAutoSkip(now())
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> true
                else -> false
            }
            if (machine.onTrackTransition(userInitiated)) {
                player?.let { stopPlayback(it, rewindToTrackStart = true) }
            }
            publish()
        }
    }

    /**
     * Starts (or restarts) the timer for [durationMs].
     *
     * @param finishCurrentTrack let the track playing at the deadline end before pausing.
     */
    fun start(durationMs: Long, finishCurrentTrack: Boolean = true) {
        if (durationMs <= 0) return
        job?.cancel()
        machine.start(now(), durationMs, finishCurrentTrack)
        publish()
        job = scope.launch {
            val connected = playerController.awaitPlayer()
            if (player !== connected) {
                player?.removeListener(listener)
                connected.addListener(listener)
                player = connected
            }
            while (isActive && machine.state != SleepTimerState.Idle) {
                val step = machine.step(now(), snapshot(connected))
                apply(step, connected)
                publish()
                if (machine.state == SleepTimerState.Idle) break
                delay(nextTickMs(step))
            }
            SleepFade.gain = 1f
        }
    }

    /** Whatever is left, or null when no timer is running. */
    fun remainingMs(): Long? = machine.remainingMs(now(), player?.let(::snapshot))

    fun cancel() {
        job?.cancel()
        job = null
        machine.cancel()
        SleepFade.gain = 1f
        publish()
    }

    private fun apply(step: SleepStep, player: Player) {
        SleepFade.gain = step.gain
        if (step.pauseNow) stopPlayback(player, rewindToTrackStart = false)
    }

    /**
     * Pauses, then puts the volume back for whenever play is pressed next.
     *
     * After the last track has played out, the player has already moved onto the next one (at
     * zero volume): it is put back at that track's start, so play resumes cleanly from the top
     * instead of a fraction of a second in. That seek also clears the silent audio already
     * buffered.
     */
    private fun stopPlayback(player: Player, rewindToTrackStart: Boolean) {
        player.pause()
        if (rewindToTrackStart && player.playbackState != Player.STATE_ENDED) player.seekTo(0L)
        SleepFade.gain = 1f
        job?.cancel()
        job = null
    }

    private fun snapshot(player: Player): SleepPlayback = SleepPlayback(
        playWhenReady = player.playWhenReady,
        positionMs = player.currentPosition,
        durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 },
        ended = player.playbackState == Player.STATE_ENDED,
    )

    /**
     * Four times a second while the volume is moving (the sink glides between updates), once a
     * second while waiting on a track's end, and otherwise straight to the start of the fade.
     */
    private fun nextTickMs(step: SleepStep): Long {
        if (step.gain < 1f) return FADE_TICK_MS
        return when (val current = machine.state) {
            is SleepTimerState.Counting ->
                if (current.finishTrack) WAIT_TICK_MS
                else (current.deadlineMs - machine.fadeMs - now()).coerceIn(FADE_TICK_MS, MAX_SLEEP_MS)
            is SleepTimerState.FinishingTrack -> WAIT_TICK_MS
            SleepTimerState.Idle -> WAIT_TICK_MS
        }
    }

    private fun publish() {
        _state.value = machine.state
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val FADE_TICK_MS = 250L
        const val WAIT_TICK_MS = 1_000L
        const val MAX_SLEEP_MS = 60_000L
    }
}
