package com.ikko.aura.net

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test fun `doubles from the base and stops at the cap`() {
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 5_000L, 5_000L), (0..5).map { Backoff.delayMs(it, 500, 5_000) })
    }

    @Test fun `a very large attempt does not overflow`() {
        assertEquals(60_000L, Backoff.delayMs(1_000, 1_000, 60_000))
        val huge = Backoff.delayMs(62, 1, Long.MAX_VALUE / 2)
        assertTrue("$huge", huge > 0 && huge <= Long.MAX_VALUE / 2)
    }

    @Test fun `jitter stays inside the upper part of the window`() {
        val random = Random(42)
        repeat(1_000) {
            val d = Backoff.delayMs(3, 1_000, 60_000, jitter = 0.5, random = random)
            assertTrue("$d", d in 4_000L..8_000L)
        }
    }
}
