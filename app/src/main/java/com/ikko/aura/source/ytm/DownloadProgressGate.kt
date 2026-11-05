package com.ikko.aura.source.ytm

/**
 * Decides when a running download's progress is worth publishing.
 *
 * It was published after every 64 KB read — hundreds of times a second on a fast link — and each publication rebuilt the
 * state of every download row for every screen listening. Now at most one in [minIntervalMs] (five a second), and
 * always the exact value when a chunk ends or the download does, so a bar never stops short of where it got to.
 */
class DownloadProgressGate(private val minIntervalMs: Long = 200L) {
    private var lastPublishedAtMs = Long.MIN_VALUE

    /** [force] is for the end of a chunk or of the download: always true. */
    fun shouldPublish(nowMs: Long, force: Boolean = false): Boolean {
        if (!force && lastPublishedAtMs != Long.MIN_VALUE && nowMs - lastPublishedAtMs < minIntervalMs) return false
        lastPublishedAtMs = nowMs
        return true
    }
}
