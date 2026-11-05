package com.ikko.aura.core.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against EBU Tech 3341's reference signals, which any R128 meter has to read to ±0.1 LU. */
class LoudnessTest {

    private fun measure(sampleRate: Int, vararg parts: Pair<Double, Double>): Double {
        val meter = LoudnessMeter()
        meter.configure(sampleRate, 2)
        for ((dbfs, seconds) in parts) {
            val signal = DspTestSignals.sine(1_000.0, DspTestSignals.dbfs(dbfs), sampleRate, 2, seconds)
            meter.process(signal, signal.size / 2)
        }
        return meter.integratedLufs()
    }

    @Test
    fun `a 1 kHz stereo sine at -23 dBFS reads -23 LUFS, at 48 and 44,1 kHz`() {
        assertEquals(-23.0, measure(48_000, -23.0 to 20.0), 0.1)
        assertEquals(-23.0, measure(44_100, -23.0 to 20.0), 0.1)
        assertEquals(-33.0, measure(48_000, -33.0 to 20.0), 0.1)
    }

    @Test
    fun `quiet passages under the relative gate do not drag the integrated value down (Tech 3341 case 3)`() {
        assertEquals(-23.0, measure(48_000, -36.0 to 10.0, -23.0 to 60.0, -36.0 to 10.0), 0.1)
    }

    @Test
    fun `silence is gated out entirely`() {
        val meter = LoudnessMeter()
        meter.configure(48_000, 2)
        meter.process(FloatArray(48_000 * 2 * 5), 48_000 * 5)
        assertTrue(meter.integratedLufs().isNaN())
        val tone = DspTestSignals.sine(1_000.0, DspTestSignals.dbfs(-20.0), 48_000, 2, 10.0)
        meter.process(tone, tone.size / 2)
        meter.process(FloatArray(48_000 * 2 * 20), 48_000 * 20)
        assertEquals(-20.0, meter.integratedLufs(), 0.1)
    }

    @Test
    fun `the 5,1 LFE does not count and the surrounds count for 1,41`() {
        assertEquals(0.0, LoudnessMeter.channelWeight(3, 6), 0.0)
        assertEquals(1.41, LoudnessMeter.channelWeight(4, 6), 0.0)
        assertEquals(1.0, LoudnessMeter.channelWeight(1, 2), 0.0)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The leveller

    @Test
    fun `a tagged track gets its gain at once, relative to the target`() {
        val n = LoudnessNormalizer()
        n.configure(48_000, 2)
        n.startTrack(TrackGain(-6.5, TrackGain.REPLAYGAIN_REFERENCE_LUFS))
        n.advance(1, -14.0, enabled = true)
        assertEquals(-2.5, n.gainDb, 1e-9)
        assertTrue(n.usesTag)
        // Opus R128 tags are against -23 LUFS.
        n.startTrack(TrackGain(-1.0, TrackGain.R128_REFERENCE_LUFS))
        n.advance(1, -14.0, enabled = true)
        assertEquals(8.0, n.gainDb, 1e-9)
        // Switched off, no gain at all.
        n.advance(1, -14.0, enabled = false)
        assertEquals(0.0, n.gainDb, 0.0)
    }

    @Test
    fun `an untagged track is measured and levelled to the target`() {
        val engine = DspEngine(DspParameters(DspConfig(bypass = false, normalizationEnabled = true, targetLufs = -14.0)))
        engine.configure(48_000, 2)
        engine.startTrack(null)
        // -20 LUFS of programme: +6 dB to reach -14.
        val signal = DspTestSignals.sine(1_000.0, DspTestSignals.dbfs(-20.0), 48_000, 2, 40.0)
        DspTestSignals.processInChunks(engine, signal, 2, chunkFrames = 2_048)
        assertEquals(6.0, engine.normalizationGainDb, 0.3)
        // What came out at the end is at the target.
        val meter = LoudnessMeter().apply { configure(48_000, 2) }
        val tail = signal.copyOfRange(signal.size - 48_000 * 2 * 5, signal.size)
        meter.process(tail, tail.size / 2)
        assertEquals(-14.0, meter.integratedLufs(), 0.4)
    }

    @Test
    fun `the gain does not pump with the music`() {
        val n = LoudnessNormalizer()
        n.configure(48_000, 2)
        n.startTrack(null)
        // Two seconds loud, two seconds quiet, for a minute: a dynamic track.
        val loud = DspTestSignals.sine(440.0, DspTestSignals.dbfs(-12.0), 48_000, 2, 2.0)
        val quiet = DspTestSignals.sine(440.0, DspTestSignals.dbfs(-28.0), 48_000, 2, 2.0)
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        for (cycle in 0 until 15) {
            for (part in listOf(loud, quiet)) {
                var start = 0
                val frames = part.size / 2
                while (start < frames) {
                    val count = minOf(1_024, frames - start)
                    val chunk = part.copyOfRange(start * 2, (start + count) * 2)
                    n.meter.process(chunk, count)
                    n.advance(count, -14.0, enabled = true)
                    start += count
                    if (cycle >= 5) {
                        min = minOf(min, n.gainDb)
                        max = maxOf(max, n.gainDb)
                    }
                }
            }
        }
        // 16 dB of programme dynamics; the gain, once settled, moves by well under one.
        assertTrue("gain swung ${max - min} dB", max - min < 1.0)
    }

    @Test
    fun `a quiet recording is lifted at most +9 dB`() {
        val n = LoudnessNormalizer()
        n.configure(48_000, 1)
        n.startTrack(TrackGain(30.0, TrackGain.REPLAYGAIN_REFERENCE_LUFS))
        n.advance(1, -14.0, enabled = true)
        assertEquals(LoudnessNormalizer.MAX_BOOST_DB, n.gainDb, 0.0)
    }
}
