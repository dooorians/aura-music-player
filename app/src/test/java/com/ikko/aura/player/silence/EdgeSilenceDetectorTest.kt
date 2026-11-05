package com.ikko.aura.player.silence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeSilenceDetectorTest {

    private val rate = 1_000 // one frame per millisecond keeps the arithmetic readable
    private val loud = SilenceLevels.dbfsToLinear(-20.0)
    private val quiet = SilenceLevels.dbfsToLinear(-70.0)
    private val config = SilenceTrimConfig(
        thresholdDbfs = -50.0,
        windowMs = 10,
        maxLeadTrimMs = 500,
        trailingConfirmMs = 1_000,
        trailingWindowMs = 30_000,
    )

    private fun detector(durationMs: Long = 200_000, trimLead: Boolean = true, startMs: Long = 0) =
        EdgeSilenceDetector(config).apply { begin(rate, startMs * 1000, durationMs * 1000, trimLead) }

    /** Feeds [ms] of audio at [level] in 10 ms windows; returns how many windows were dropped. */
    private fun EdgeSilenceDetector.feed(ms: Int, level: Double): Int {
        var dropped = 0
        repeat(ms / 10) { if (onWindow(10, level)) dropped++ }
        return dropped
    }

    @Test
    fun `leading silence is dropped up to the first sound`() {
        val d = detector()
        assertEquals(30, d.feed(300, quiet))
        assertEquals(EdgeSilenceDetector.Phase.LEADING, d.phase)
        assertEquals(0, d.feed(10, loud))
        assertEquals(EdgeSilenceDetector.Phase.MUSIC, d.phase)
        assertEquals(300L, d.droppedFrames)
    }

    @Test
    fun `once the music starts nothing more is dropped, quiet or not`() {
        val d = detector()
        d.feed(10, loud)
        assertEquals(0, d.feed(300, quiet))
        assertEquals(0L, d.droppedFrames)
    }

    @Test
    fun `leading trim stops at its cap`() {
        val d = detector()
        assertEquals(50, d.feed(800, quiet)) // 500 ms cap
        assertEquals(500L, d.droppedFrames)
        assertEquals(EdgeSilenceDetector.Phase.MUSIC, d.phase)
    }

    @Test
    fun `a stream that is not ours to trim keeps its opening`() {
        val d = detector(trimLead = false)
        assertEquals(0, d.feed(300, quiet))
        assertEquals(0L, d.droppedFrames)
    }

    @Test
    fun `dropped frames still move the track position on`() {
        val d = detector()
        d.feed(200, quiet)
        assertEquals(200_000L, d.positionUs())
    }

    @Test
    fun `the body of a track is not measured`() {
        val d = detector(durationMs = 200_000)
        d.feed(10, loud)
        assertFalse(d.needsLevel())
    }

    @Test
    fun `trailing silence is called once it has lasted the confirmation time`() {
        val d = detector(durationMs = 200_000, startMs = 180_000)
        d.feed(1_000, loud)
        assertTrue(d.needsLevel())
        d.feed(990, quiet)
        assertNull(d.trailingSilenceStartUs())
        d.feed(10, quiet)
        assertEquals(181_000_000L, d.trailingSilenceStartUs())
    }

    @Test
    fun `sound coming back cancels a trailing silence`() {
        val d = detector(durationMs = 200_000, startMs = 180_000)
        d.feed(100, loud)
        d.feed(1_500, quiet)
        assertEquals(180_100_000L, d.trailingSilenceStartUs())
        d.feed(10, loud)
        assertNull(d.trailingSilenceStartUs())
    }

    @Test
    fun `a pause in the middle of a track is part of the track`() {
        val d = detector(durationMs = 200_000, startMs = 60_000)
        d.feed(100, loud)
        d.feed(3_000, quiet)
        assertNull(d.trailingSilenceStartUs())
    }

    @Test
    fun `the first half of a short track never counts as its end`() {
        // 20 s track: the tail window would reach back to -10 s; it is held at the halfway point.
        val d = detector(durationMs = 20_000, startMs = 5_000)
        d.feed(100, loud)
        d.feed(2_000, quiet)
        assertNull(d.trailingSilenceStartUs())

        val late = detector(durationMs = 20_000, startMs = 12_000)
        late.feed(100, loud)
        late.feed(1_000, quiet)
        assertEquals(12_100_000L, late.trailingSilenceStartUs())
    }

    @Test
    fun `silence with no music before it is not an ending`() {
        // A seek straight into the silent tail: nothing was heard, so nothing ended.
        val d = detector(durationMs = 200_000, startMs = 190_000, trimLead = false)
        d.feed(2_000, quiet)
        assertNull(d.trailingSilenceStartUs())
    }

    @Test
    fun `no end is ever called without a duration`() {
        val d = EdgeSilenceDetector(config).apply { begin(rate, 0, EdgeSilenceDetector.UNKNOWN, trimLead = false) }
        d.feed(100, loud)
        assertFalse(d.needsLevel())
        d.feed(5_000, quiet)
        assertNull(d.trailingSilenceStartUs())
    }

    @Test
    fun `a new stream starts from a clean slate`() {
        val d = detector()
        d.feed(300, quiet)
        d.begin(rate, 0, 200_000_000, trimLead = true)
        assertEquals(0L, d.droppedFrames)
        assertEquals(0L, d.positionUs())
        assertEquals(EdgeSilenceDetector.Phase.LEADING, d.phase)
    }

    @Test
    fun `the threshold decides what is silent`() {
        val strict = EdgeSilenceDetector(config.copy(thresholdDbfs = -80.0)).apply { begin(rate, 0, 200_000_000, true) }
        assertEquals(0, strict.feed(10, quiet)) // -70 dBFS is sound against a -80 line
        val loose = EdgeSilenceDetector(config.copy(thresholdDbfs = -60.0)).apply { begin(rate, 0, 200_000_000, true) }
        assertEquals(1, loose.feed(10, quiet))
    }

    @Test
    fun `a window is sized from the sample rate`() {
        val d = EdgeSilenceDetector(config).apply { begin(48_000, 0, EdgeSilenceDetector.UNKNOWN, false) }
        assertEquals(480, d.windowFrames())
    }

    @Test
    fun `the config converts its threshold once`() {
        assertEquals(SilenceLevels.dbfsToLinear(-50.0), SilenceTrimConfig().thresholdLinear, 1e-12)
    }

    @Test
    fun `only device storage counts as local`() {
        assertTrue(SilenceTrim.isLocalScheme("content"))
        assertTrue(SilenceTrim.isLocalScheme("file"))
        assertTrue(SilenceTrim.isLocalScheme("FILE"))
        assertTrue(SilenceTrim.isLocalScheme(null))
        assertFalse(SilenceTrim.isLocalScheme("https"))
        assertFalse(SilenceTrim.isLocalScheme("ytm"))
        assertFalse(SilenceTrim.isLocalScheme("remote"))
        assertFalse(SilenceTrim.isLocalScheme(null, hasUri = false))
    }

    @Test
    fun `a track change right after an automatic skip is the trimmer's`() {
        SilenceTrim.markAutoSkip(10_000)
        assertTrue(SilenceTrim.isRecentAutoSkip(10_500))
        assertFalse(SilenceTrim.isRecentAutoSkip(13_000))
        assertFalse(SilenceTrim.isRecentAutoSkip(9_000))
    }
}
