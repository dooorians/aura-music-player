package com.ikko.aura.source

import com.ikko.aura.source.ytm.DownloadProgressGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressGateTest {

    @Test
    fun `the first progress is published at once`() {
        assertTrue(DownloadProgressGate().shouldPublish(nowMs = 0))
    }

    @Test
    fun `a flood of reads in one second publishes at most five times`() {
        val gate = DownloadProgressGate(minIntervalMs = 200)
        // 64 KB reads at ~10 MB/s: one every 6 ms for a second
        val published = (0 until 1000 step 6).count { gate.shouldPublish(nowMs = it.toLong()) }
        assertTrue("published $published times", published in 5..6)
    }

    @Test
    fun `the exact value is always published at the end of a chunk or of the download`() {
        val gate = DownloadProgressGate(minIntervalMs = 200)
        assertTrue(gate.shouldPublish(nowMs = 0))
        assertFalse(gate.shouldPublish(nowMs = 10))
        assertTrue(gate.shouldPublish(nowMs = 11, force = true))
    }

    @Test
    fun `after the interval the next read publishes again`() {
        val gate = DownloadProgressGate(minIntervalMs = 200)
        gate.shouldPublish(nowMs = 0)
        assertFalse(gate.shouldPublish(nowMs = 199))
        assertTrue(gate.shouldPublish(nowMs = 200))
        assertEquals(false, gate.shouldPublish(nowMs = 250))
    }
}
