package com.ikko.aura.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    private var now = 0L
    private val backoff = Backoff(baseMs = 1_000, maxMs = 8_000, clock = { now })

    @Test
    fun `closed until a failure, then open for a pause that doubles`() {
        assertTrue(backoff.allows())
        backoff.onFailure()
        assertFalse(backoff.allows())
        assertEquals(1_000, backoff.remainingMs())
        now += 1_000
        assertTrue(backoff.allows())
        backoff.onFailure()
        assertEquals(2_000, backoff.remainingMs())
        now += 2_000
        backoff.onFailure()
        assertEquals(4_000, backoff.remainingMs())
    }

    @Test
    fun `the pause is capped`() {
        repeat(30) { backoff.onFailure() }
        assertEquals(8_000, backoff.remainingMs())
    }

    @Test
    fun `a rate limit is waited out for at least as long as asked`() {
        backoff.onFailure(atLeastMs = 60_000)
        assertEquals(60_000, backoff.remainingMs())
    }

    @Test
    fun `one success closes it and starts the doubling over`() {
        backoff.onFailure()
        backoff.onFailure()
        backoff.onSuccess()
        assertTrue(backoff.allows())
        backoff.onFailure()
        assertEquals(1_000, backoff.remainingMs())
    }
}
