package com.ikko.aura.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PositionTickPolicyTest {
    private var playing = true
    private var watched = true
    private var ticks = 0
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Test fun `on screen and playing it ticks twice a second`() = runTest {
        val job = launch { PositionTickPolicy.run({ playing }, { watched }, wake) { ticks++ } }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(121, ticks)
        job.cancel()
    }

    @Test fun `in the background it ticks ten times less while still playing`() = runTest {
        watched = false
        val job = launch { PositionTickPolicy.run({ playing }, { watched }, wake) { ticks++ } }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(13, ticks)
        job.cancel()
    }

    @Test fun `paused with nothing watching it barely wakes at all`() = runTest {
        playing = false; watched = false
        val job = launch { PositionTickPolicy.run({ playing }, { watched }, wake) { ticks++ } }
        advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals(11, ticks) // once a minute over ten minutes, plus the first
        job.cancel()
    }

    @Test fun `an event ends the sleep at once`() = runTest {
        playing = false; watched = false
        val job = launch { PositionTickPolicy.run({ playing }, { watched }, wake) { ticks++ } }
        runCurrent()
        assertEquals(1, ticks)
        playing = true; watched = true
        wake.trySend(Unit); runCurrent()
        assertEquals(2, ticks)
        job.cancel()
    }

    @Test fun `cancelling the scope stops the ticker for good`() = runTest {
        val job = launch { PositionTickPolicy.run({ playing }, { watched }, wake) { ticks++ } }
        advanceTimeBy(1_100); runCurrent()
        val before = ticks
        job.cancel()
        advanceTimeBy(60 * 60_000); runCurrent()
        assertEquals(before, ticks)
        assertTrue(job.isCancelled)
    }

    @Test fun `only the watched and playing case refuses to be woken early`() {
        assertEquals(PositionTickPolicy.Tick(500, false), PositionTickPolicy.next(isPlaying = true, watched = true))
        assertTrue(PositionTickPolicy.next(isPlaying = true, watched = false).wakeable)
        assertTrue(PositionTickPolicy.next(isPlaying = false, watched = true).wakeable)
        assertTrue(PositionTickPolicy.next(isPlaying = false, watched = false).wakeable)
    }
}
