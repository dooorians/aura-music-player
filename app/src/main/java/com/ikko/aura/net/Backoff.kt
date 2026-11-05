package com.ikko.aura.net

import kotlin.random.Random

/**
 * Exponential backoff: [baseMs], then twice that, four times… never more than [maxMs]. With [jitter] (0..1) each wait is
 * drawn from the upper part of its window, so the many clients a shared outage stopped do not all come back on the same tick.
 */
object Backoff {
    fun delayMs(attempt: Int, baseMs: Long, maxMs: Long, jitter: Double = 0.0, random: Random = Random.Default): Long {
        require(baseMs > 0 && maxMs >= baseMs) { "baseMs must be > 0 and <= maxMs" }
        val shift = attempt.coerceIn(0, 30)
        val raw = if (baseMs > maxMs shr shift) maxMs else (baseMs shl shift).coerceAtMost(maxMs)
        if (jitter <= 0.0) return raw
        val spread = (raw * jitter.coerceIn(0.0, 1.0)).toLong()
        return raw - spread + if (spread > 0) random.nextLong(spread + 1) else 0L
    }
}
