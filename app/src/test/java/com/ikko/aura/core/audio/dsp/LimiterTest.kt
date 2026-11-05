package com.ikko.aura.core.audio.dsp

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimiterTest {

    private fun boostedEngine(sampleRate: Int, channels: Int, preampDb: Double = 0.0, normalization: Boolean = false): DspEngine {
        val config = DspConfig(
            bypass = false,
            // No automatic headroom: the limiter alone has to hold the boost.
            autoPreamp = false,
            preampDb = preampDb,
            normalizationEnabled = normalization,
            filters = DspTestSignals.allBands(12f),
        )
        return DspEngine(DspParameters(config)).apply { configure(sampleRate, channels) }
    }

    @Test
    fun `+12 dB on every band never takes a full-scale sine past 1,0`() {
        for (fs in intArrayOf(44_100, 48_000, 96_000)) for (hz in doubleArrayOf(31.5, 100.0, 1_000.0, 4_000.0, 16_000.0)) {
            val engine = boostedEngine(fs, 2)
            val signal = DspTestSignals.sine(hz, 1.0, fs, 2, 1.0)
            DspTestSignals.processInChunks(engine, signal, 2)
            val peak = DspTestSignals.peak(signal)
            assertTrue("$hz Hz at $fs: peak $peak", peak <= 1.0f)
            assertTrue(DspTestSignals.allFinite(signal))
        }
    }

    @Test
    fun `noise, square waves and a +12 dB preamp on top stay under 1,0`() {
        val fs = 48_000
        val signals = listOf(
            DspTestSignals.whiteNoise(1f, fs * 3, 2),
            DspTestSignals.square(63.0, 1f, fs, 2, 2.0),
            DspTestSignals.square(2_000.0, 1f, fs, 2, 2.0),
            FloatArray(fs * 2) { if (it % 4_800 < 2) 1f else 0f }, // clicks
        )
        for (signal in signals) {
            val engine = boostedEngine(fs, 2, preampDb = 12.0, normalization = true)
            DspTestSignals.processInChunks(engine, signal, 2, chunkFrames = 777)
            assertTrue("peak ${DspTestSignals.peak(signal)}", DspTestSignals.peak(signal) <= 1.0f)
        }
    }

    @Test
    fun `audio already under the knee passes through the limiter untouched`() {
        val limiter = Limiter()
        limiter.configure(48_000, 2)
        val signal = DspTestSignals.sine(440.0, 0.8, 48_000, 2, 0.5)
        val copy = signal.copyOf()
        limiter.process(signal, 0, signal.size / 2)
        for (i in signal.indices) assertEquals(copy[i], signal[i], 0f)
        assertEquals(1.0, limiter.currentGain, 0.0)
    }

    @Test
    fun `the tanh curve meets the knee smoothly, rises, and never passes 1`() {
        val k = Limiter.KNEE
        assertEquals(k, Limiter.curve(k), 1e-12)
        // Slope 1 at the knee: no corner where the limiting starts.
        val slope = (Limiter.curve(k + 1e-7) - Limiter.curve(k)) / 1e-7
        assertEquals(1.0, slope, 1e-5)
        var previous = k
        for (x in listOf(0.9, 1.0, 1.5, 2.0, 4.0, 16.0, 1e6, Double.MAX_VALUE)) {
            val y = Limiter.curve(x)
            assertTrue("curve($x) = $y", y <= 1.0 && y >= previous)
            // Short of the far tail, where tanh rounds to 1 in double precision, it stays under.
            if (x <= 1.5) assertTrue("curve($x) = $y", y < 1.0)
            previous = y
        }
    }

    @Test
    fun `NaN and infinity come out as silence, not as noise`() {
        val limiter = Limiter()
        limiter.configure(48_000, 2)
        val buffer = floatArrayOf(0.5f, 0.5f, Float.NaN, 0.2f, Float.POSITIVE_INFINITY, -0.1f, 0.3f, 0.3f)
        limiter.process(buffer, 0, 4)
        assertTrue(limiter.sawNonFinite)
        assertEquals(0f, buffer[2]); assertEquals(0f, buffer[3])
        assertEquals(0f, buffer[4]); assertEquals(0f, buffer[5])
        assertTrue(DspTestSignals.allFinite(buffer))
    }

    @Test
    fun `the gain comes back up after a peak instead of staying down`() {
        val limiter = Limiter()
        limiter.configure(48_000, 1)
        val burst = FloatArray(480) { 4f }
        limiter.process(burst, 0, burst.size)
        val reduced = limiter.currentGain
        assertTrue(reduced < 0.3)
        val quiet = FloatArray(48_000) { 0.1f }
        limiter.process(quiet, 0, quiet.size)
        assertTrue("gain ${limiter.currentGain}", abs(1.0 - limiter.currentGain) < 1e-3)
    }

    @Test
    fun `the engine survives a NaN in its input and carries on`() {
        val engine = boostedEngine(48_000, 2)
        val signal = DspTestSignals.sine(1_000.0, 0.5, 48_000, 2, 0.5)
        signal[1_000] = Float.NaN
        DspTestSignals.processInChunks(engine, signal, 2)
        assertTrue(DspTestSignals.allFinite(signal))
        val after = DspTestSignals.sine(1_000.0, 0.5, 48_000, 2, 0.5)
        DspTestSignals.processInChunks(engine, after, 2)
        assertTrue(DspTestSignals.allFinite(after))
        assertTrue(DspTestSignals.peak(after) > 0.5f) // still processing, not stuck at silence
    }
}
