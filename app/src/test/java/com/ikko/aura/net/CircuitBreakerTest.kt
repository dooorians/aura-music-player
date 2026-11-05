package com.ikko.aura.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitBreakerTest {
    private var now = 0L
    private fun breaker(threshold: Int = 3) =
        CircuitBreaker(failureThreshold = threshold, baseCooldownMs = 1_000, maxCooldownMs = 8_000, clock = { now })

    @Test fun `stays closed below the threshold`() {
        val b = breaker()
        b.onFailure(); b.onFailure()
        assertEquals(CircuitBreaker.State.CLOSED, b.state())
        assertTrue(b.allowRequest())
    }

    @Test fun `a success in between resets the count`() {
        val b = breaker()
        b.onFailure(); b.onFailure(); b.onSuccess(); b.onFailure(); b.onFailure()
        assertTrue(b.allowRequest())
    }

    @Test fun `opens after the threshold and refuses until the cool-down has passed`() {
        val b = breaker()
        repeat(3) { b.onFailure() }
        assertEquals(CircuitBreaker.State.OPEN, b.state())
        assertFalse(b.allowRequest())
        assertEquals(1_000, b.remainingCooldownMs())
        now = 999
        assertFalse(b.allowRequest())
        now = 1_000
        assertEquals(CircuitBreaker.State.HALF_OPEN, b.state())
    }

    @Test fun `half-open lets exactly one probe through`() {
        val b = breaker()
        repeat(3) { b.onFailure() }
        now = 1_000
        assertTrue(b.allowRequest())
        assertFalse(b.allowRequest())
        assertFalse(b.allowRequest())
    }

    @Test fun `a successful probe closes it`() {
        val b = breaker()
        repeat(3) { b.onFailure() }
        now = 1_000
        b.allowRequest()
        b.onSuccess()
        assertEquals(CircuitBreaker.State.CLOSED, b.state())
        assertTrue(b.allowRequest())
        assertTrue(b.allowRequest())
    }

    @Test fun `a failed probe reopens it for twice as long, up to the cap`() {
        val b = breaker()
        repeat(3) { b.onFailure() }
        val cooldowns = mutableListOf<Long>()
        repeat(5) {
            now += b.remainingCooldownMs()
            assertTrue(b.allowRequest())
            b.onFailure()
            cooldowns += b.remainingCooldownMs()
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 8_000L, 8_000L), cooldowns)
    }

    @Test fun `a 429 opens it at once, for at least what Retry-After asks`() {
        val b = breaker()
        b.onFailure(rateLimited = true, retryAfterMs = 30_000)
        assertFalse(b.allowRequest())
        assertEquals(30_000, b.remainingCooldownMs())
    }

    @Test fun `the network coming back closes a circuit opened by failures`() {
        val b = breaker()
        repeat(3) { b.onFailure() }
        b.onNetworkRestored()
        assertTrue(b.allowRequest())
    }

    @Test fun `the network coming back does not lift a rate limit`() {
        val b = breaker()
        b.onFailure(rateLimited = true, retryAfterMs = 30_000)
        b.onNetworkRestored()
        assertFalse(b.allowRequest())
        now = 30_000
        assertTrue(b.allowRequest())
    }
}
