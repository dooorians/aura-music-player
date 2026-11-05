package com.ikko.aura.net

/**
 * Stops asking a host that keeps failing. Closed, every request goes through; after [failureThreshold] failures in a row (or one
 * "slow down", HTTP 429) it opens and refuses everything at once — no radio wake-up, no socket, no timeout to sit through — for
 * a cool-down that doubles each time it opens again ([Backoff]). Once that has passed, a single request is let through to probe
 * (half-open): a success closes it, a failure opens it again for longer.
 *
 * Pure and clocked from outside, so its whole life can be played in a unit test.
 */
class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val baseCooldownMs: Long = 5_000L,
    private val maxCooldownMs: Long = 15 * 60_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private var state = State.CLOSED
    private var failures = 0
    private var trips = 0
    private var openUntil = 0L
    private var probing = false
    private var rateLimited = false

    @Synchronized
    fun state(): State {
        if (state == State.OPEN && clock() >= openUntil) state = State.HALF_OPEN
        return state
    }

    /** Whether a request may go out now. In half-open, true for the one probe only. */
    @Synchronized
    fun allowRequest(): Boolean = when (state()) {
        State.CLOSED -> true
        State.OPEN -> false
        State.HALF_OPEN -> if (probing) false else { probing = true; true }
    }

    /** How long until a request may go out again; 0 when it may now. */
    @Synchronized
    fun remainingCooldownMs(): Long = if (state() == State.OPEN) (openUntil - clock()).coerceAtLeast(0L) else 0L

    @Synchronized
    fun onSuccess() {
        state = State.CLOSED
        failures = 0
        trips = 0
        probing = false
        rateLimited = false
    }

    /**
     * A failure: no answer, a timeout, a 5xx — or a 429, given as [rateLimited], which opens at once and for at least the
     * [retryAfterMs] the host asked for.
     */
    @Synchronized
    fun onFailure(rateLimited: Boolean = false, retryAfterMs: Long? = null) {
        failures++
        val wasProbe = state() == State.HALF_OPEN
        probing = false
        if (rateLimited || wasProbe || failures >= failureThreshold) {
            val cooldown = Backoff.delayMs(trips, baseCooldownMs, maxCooldownMs)
            trips++
            openUntil = clock() + maxOf(cooldown, retryAfterMs ?: 0L)
            state = State.OPEN
            this.rateLimited = this.rateLimited || rateLimited
        }
    }

    /**
     * The device has just got a network back: failures that were only the network being gone say nothing about the host any
     * more, so it is asked again at once. A host that asked to be left alone (429) keeps its cool-down.
     */
    @Synchronized
    fun onNetworkRestored() {
        if (rateLimited && state() == State.OPEN) return
        onSuccess()
    }
}
