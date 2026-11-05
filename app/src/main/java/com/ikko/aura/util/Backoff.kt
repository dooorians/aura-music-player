package com.ikko.aura.util

/**
 * A circuit breaker for one web service: after a failure that says "not now" (no network, a timeout, a rate limit, the
 * service down), calls are refused for a while, the pause doubling at each new failure up to [maxMs]; one success
 * closes it again.
 *
 * Without it, offline, every lookup of a background enrichment waited for its own connect timeout, one after the other,
 * and a rate limit was answered by asking again at once. Thread-safe; [clock] is there for the tests.
 */
class Backoff(
    private val baseMs: Long = 5_000L,
    private val maxMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var failures = 0
    private var openUntil = 0L

    /** Whether a call may go out now. */
    @Synchronized
    fun allows(): Boolean = clock() >= openUntil

    /** How long until [allows] is true again; 0 when it already is. */
    @Synchronized
    fun remainingMs(): Long = (openUntil - clock()).coerceAtLeast(0L)

    @Synchronized
    fun onSuccess() {
        failures = 0
        openUntil = 0L
    }

    /** A failure worth waiting out; [atLeastMs] when the service said how long (a rate limit's "retry after"). */
    @Synchronized
    fun onFailure(atLeastMs: Long = 0L) {
        failures = (failures + 1).coerceAtMost(MAX_DOUBLINGS)
        val pause = (baseMs shl (failures - 1)).coerceAtMost(maxMs)
        openUntil = maxOf(openUntil, clock() + maxOf(pause, atLeastMs))
    }

    private companion object {
        const val MAX_DOUBLINGS = 20
    }
}
