package com.ikko.aura.player.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerMachineTest {

    private val minute = 60_000L

    private fun playing(positionMs: Long, durationMs: Long? = 4 * minute) =
        SleepPlayback(playWhenReady = true, positionMs = positionMs, durationMs = durationMs)

    // ---- Fade curve ---------------------------------------------------------

    @Test
    fun `full volume until the last 30 seconds, then down to nothing`() {
        val m = SleepTimerMachine()
        assertEquals(1f, m.fadeGain(60_000), 0f)
        assertEquals(1f, m.fadeGain(30_000), 0f)
        assertEquals(0.25f, m.fadeGain(15_000), 1e-6f)
        assertEquals(0f, m.fadeGain(0), 0f)
        assertEquals(0f, m.fadeGain(-5), 0f)
    }

    @Test
    fun `the fade only ever goes down as the end approaches`() {
        val m = SleepTimerMachine()
        var last = 1f
        for (left in 30_000L downTo 0L step 500L) {
            val gain = m.fadeGain(left)
            assertTrue("gain rose at $left ms", gain <= last)
            last = gain
        }
    }

    // ---- Plain countdown ----------------------------------------------------

    @Test
    fun `idle does nothing`() {
        val m = SleepTimerMachine()
        assertEquals(SleepStep.UNTOUCHED, m.step(0, playing(0)))
        assertNull(m.remainingMs(0, playing(0)))
    }

    @Test
    fun `a plain timer fades over its last 30 seconds and pauses at the deadline`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = 10 * minute, finishTrack = false)

        assertEquals(SleepStep.UNTOUCHED, m.step(5 * minute, playing(0)))
        val mid = m.step(10 * minute - 15_000, playing(0))
        assertEquals(0.25f, mid.gain, 1e-6f)
        assertFalse(mid.pauseNow)

        assertEquals(SleepStep.STOP, m.step(10 * minute, playing(0)))
        assertEquals(SleepTimerState.Idle, m.state)
    }

    @Test
    fun `a plain timer cuts mid-track, which is what turning the option off means`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = minute, finishTrack = false)
        // Two minutes left in the track, and it still stops at the deadline.
        assertEquals(SleepStep.STOP, m.step(minute, playing(positionMs = 2 * minute)))
    }

    // ---- Finishing the track ------------------------------------------------

    @Test
    fun `never stops in the middle of a track`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = minute, finishTrack = true)
        // At the deadline the track has a minute and a half to go: keep playing at full volume.
        val step = m.step(minute, playing(positionMs = 150_000))
        assertFalse(step.pauseNow)
        assertEquals(1f, step.gain, 0f)
        assertTrue(m.state is SleepTimerState.FinishingTrack)
    }

    @Test
    fun `the last track is picked as soon as the deadline falls inside it`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = 10 * minute, finishTrack = true)
        // The current track ends at 3 min: before the deadline, so it is not the last one.
        m.step(minute, playing(positionMs = 2 * minute, durationMs = 4 * minute))
        assertTrue(m.state is SleepTimerState.Counting)
        // Later, a track that ends after the deadline.
        m.step(9 * minute, playing(positionMs = 0, durationMs = 4 * minute))
        assertTrue(m.state is SleepTimerState.FinishingTrack)
    }

    @Test
    fun `the last track fades over its own last 30 seconds`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = minute, finishTrack = true)
        m.step(minute, playing(positionMs = 2 * minute))
        val nearEnd = m.step(minute + 105_000, playing(positionMs = 4 * minute - 15_000))
        assertEquals(0.25f, nearEnd.gain, 1e-6f)
        assertFalse(nearEnd.pauseNow)
    }

    @Test
    fun `the last track playing out pauses`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = minute, finishTrack = true)
        m.step(minute, playing(positionMs = 3 * minute))
        assertTrue(m.onTrackTransition(userInitiated = false))
        assertEquals(SleepTimerState.Idle, m.state)
    }

    @Test
    fun `a skip on the last track makes the new one the last`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = minute, finishTrack = true)
        m.step(minute, playing(positionMs = 3 * minute))
        assertFalse(m.onTrackTransition(userInitiated = true))
        assertTrue(m.state is SleepTimerState.FinishingTrack)
        // The new track starts at full volume.
        assertEquals(1f, m.step(minute + 1_000, playing(positionMs = 1_000)).gain, 0f)
    }

    @Test
    fun `any track change inside the last track's fade is its end`() {
        // A crossfade handoff a few seconds before the end may not be reported as the track running out.
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = minute, finishTrack = true)
        m.step(minute, playing(positionMs = 3 * minute))
        m.step(minute + 55_000, playing(positionMs = 4 * minute - 5_000))
        assertTrue(m.onTrackTransition(userInitiated = true))
        assertEquals(SleepTimerState.Idle, m.state)
    }

    @Test
    fun `tracks ending before the deadline are not the end`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = 10 * minute, finishTrack = true)
        m.step(minute, playing(positionMs = 3 * minute))
        assertFalse(m.onTrackTransition(userInitiated = false))
        assertTrue(m.state is SleepTimerState.Counting)
    }

    @Test
    fun `the end of the queue on the last track stops`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = minute, finishTrack = true)
        m.step(minute, playing(positionMs = 3 * minute))
        val step = m.step(2 * minute, playing(positionMs = 4 * minute).copy(ended = true))
        assertEquals(SleepStep.STOP, step)
        assertEquals(SleepTimerState.Idle, m.state)
    }

    @Test
    fun `pausing by hand on the last track ends the timer without touching anything`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = minute, finishTrack = true)
        m.step(minute, playing(positionMs = 3 * minute))
        val step = m.step(minute + 5_000, playing(positionMs = 3 * minute + 5_000).copy(playWhenReady = false))
        assertEquals(SleepStep.UNTOUCHED, step)
        assertEquals(SleepTimerState.Idle, m.state)
    }

    @Test
    fun `paused before the deadline, the countdown carries on`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = 10 * minute, finishTrack = true)
        val paused = SleepPlayback(playWhenReady = false, positionMs = 3 * minute, durationMs = 4 * minute)
        assertEquals(SleepStep.UNTOUCHED, m.step(9 * minute + 30_000, paused))
        assertTrue(m.state is SleepTimerState.Counting)
        // Still paused past the deadline: the music already stopped, so the timer just ends.
        m.step(10 * minute, paused)
        assertEquals(SleepTimerState.Idle, m.state)
    }

    @Test
    fun `a track of unknown length falls back to the deadline`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = minute, finishTrack = true)
        val live = playing(positionMs = 0, durationMs = null)
        assertEquals(0.25f, m.step(minute - 15_000, live).gain, 1e-6f)
        assertEquals(SleepStep.STOP, m.step(minute, live))
    }

    @Test
    fun `no player connected counts down on the deadline alone`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = minute, finishTrack = true)
        assertEquals(SleepStep.STOP, m.step(minute, null))
    }

    // ---- Time left, cancel, restart -----------------------------------------

    @Test
    fun `time left is the deadline, or the last track's end once it is known`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = 10 * minute, finishTrack = true)
        assertEquals(9 * minute, m.remainingMs(minute, playing(positionMs = 0, durationMs = 4 * minute)))
        // At 9 min the track has 3 min to go, past the deadline: that is when it really stops.
        assertEquals(3 * minute, m.remainingMs(9 * minute, playing(positionMs = minute, durationMs = 4 * minute)))

        val plain = SleepTimerMachine()
        plain.start(nowMs = 0, durationMs = 10 * minute, finishTrack = false)
        assertEquals(minute, plain.remainingMs(9 * minute, playing(positionMs = minute)))
    }

    @Test
    fun `cancel goes back to idle and leaves the volume alone`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = minute, finishTrack = false)
        m.cancel()
        assertEquals(SleepTimerState.Idle, m.state)
        assertEquals(SleepStep.UNTOUCHED, m.step(minute, playing(0)))
    }

    @Test
    fun `starting again replaces the deadline`() {
        val m = SleepTimerMachine()
        m.start(nowMs = 0, durationMs = minute, finishTrack = false)
        m.start(nowMs = 0, durationMs = 5 * minute, finishTrack = false)
        assertEquals(SleepTimerState.Counting(5 * minute, finishTrack = false), m.state)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a timer needs a positive duration`() {
        SleepTimerMachine().start(nowMs = 0, durationMs = 0, finishTrack = false)
    }

    @Test
    fun `the processor ramp moves one step at a time and lands on its target`() {
        assertEquals(0.9f, SleepFadeProcessor.rampToward(1f, 0f, 0.1f), 1e-6f)
        assertEquals(0.5f, SleepFadeProcessor.rampToward(0.55f, 0.5f, 0.1f), 0f)
        assertEquals(1f, SleepFadeProcessor.rampToward(0.95f, 1f, 0.1f), 0f)
        assertEquals(0.3f, SleepFadeProcessor.rampToward(0.3f, 0.3f, 0.1f), 0f)
    }
}
