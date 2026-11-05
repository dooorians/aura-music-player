package com.ikko.aura.core.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadTest {

    private val rates = intArrayOf(8_000, 22_050, 32_000, 44_100, 48_000, 88_200, 96_000, 192_000)
    private val gains = doubleArrayOf(-24.0, -12.0, -6.0, -0.1, 0.1, 3.0, 6.0, 12.0, 24.0)
    private val qs = doubleArrayOf(0.1, 0.5, 0.707, 1.414, 4.0, 10.0, 40.0)

    // ---------------------------------------------------------------------------------------------------------------
    // Stability

    @Test
    fun `every type at every ISO frequency, rate, gain and Q has its poles inside the unit circle`() {
        val c = DoubleArray(Biquad.STRIDE)
        for (type in FilterType.entries) for (fs in rates) for (fc in IsoBands.FREQUENCIES_HZ) for (g in gains) for (q in qs) {
            Biquad.design(type, fc, q, g, fs, c, 0)
            assertTrue("$type fc=$fc fs=$fs g=$g q=$q: ${c.toList()}", c.all { it.isFinite() })
            assertTrue("$type fc=$fc fs=$fs g=$g q=$q unstable", Biquad.isStable(c, 0))
            // Strictly inside, with a margin the double-precision state can rely on.
            assertTrue(abs(c[4]) < 1.0)
        }
    }

    @Test
    fun `degenerate input designs the identity, never a broken filter`() {
        val c = DoubleArray(Biquad.STRIDE)
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
        for ((fc, q, g, fs) in listOf(
            listOf(1000.0, 1.0, Double.NaN, 48_000.0),
            listOf(Double.NaN, 1.0, 6.0, 48_000.0),
            listOf(-50.0, 1.0, 6.0, 48_000.0),
            listOf(1000.0, Double.POSITIVE_INFINITY, 6.0, 48_000.0),
            listOf(1000.0, 1.0, 6.0, 0.0),
            listOf(1000.0, 1.0, 0.0, 48_000.0),
        )) {
            Biquad.design(FilterType.PEAKING, fc, q, g, fs.toInt(), c, 0)
            assertArrayEquals("fc=$fc q=$q g=$g fs=$fs", identity, c, 0.0)
        }
    }

    @Test
    fun `a band past Nyquist is folded under it and stays stable`() {
        val c = DoubleArray(Biquad.STRIDE)
        // 16 kHz at 22.05 kHz and 8 kHz: above Nyquist.
        for (fs in intArrayOf(8_000, 22_050, 32_000)) {
            Biquad.design(FilterType.PEAKING, 16_000.0, IsoBands.Q, 12.0, fs, c, 0)
            assertTrue(Biquad.isStable(c, 0))
            assertTrue(c.all { it.isFinite() })
        }
        // Zero Q is clamped rather than divided by.
        Biquad.design(FilterType.LOW_SHELF, 100.0, 0.0, 6.0, 48_000, c, 0)
        assertTrue(Biquad.isStable(c, 0))
    }

    @Test
    fun `ten seconds of noise, impulses, DC and full-scale square through +24 dB filters stay finite and bounded`() {
        for (fs in intArrayOf(44_100, 48_000, 96_000)) {
            val cascade = BiquadCascade()
            cascade.configure(fs, 2)
            cascade.setTargets(
                IsoBands.FREQUENCIES_HZ.indices.map { FilterSpec(FilterType.PEAKING, IsoBands.FREQUENCIES_HZ[it], 10.0, 24.0) } +
                    FilterSpec(FilterType.LOW_SHELF, 20.0, 0.707, 24.0) +
                    FilterSpec(FilterType.HIGH_SHELF, 18_000.0, 0.707, 24.0),
            )
            cascade.snap()
            val signals = listOf(
                DspTestSignals.whiteNoise(1f, fs * 4, 2),
                FloatArray(fs * 2 * 2) { if (it % (fs / 5) == 0) 1f else 0f },
                FloatArray(fs * 2 * 2) { 1f },
                DspTestSignals.square(31.5, 1f, fs, 2, 2.0),
            )
            for (signal in signals) {
                cascade.reset()
                var start = 0
                val frames = signal.size / 2
                while (start < frames) {
                    val n = minOf(512, frames - start)
                    cascade.process(signal, start, n)
                    start += n
                }
                assertTrue("non-finite at $fs Hz", DspTestSignals.allFinite(signal))
                // +24 dB on everything, stacked: big, but a fixed bound, not a filter running away.
                assertTrue("peak ${DspTestSignals.peak(signal)} at $fs Hz", DspTestSignals.peak(signal) < 1e4f)
                assertTrue(!cascade.recovered)
            }
        }
    }

    @Test
    fun `a filter fed a NaN clears itself and recovers`() {
        val cascade = BiquadCascade()
        cascade.configure(48_000, 1)
        cascade.setTargets(listOf(IsoBands.spec(0, 6f)))
        cascade.snap()
        val buffer = FloatArray(256) { 0.1f }
        buffer[10] = Float.NaN
        cascade.process(buffer, 0, 256)
        assertTrue(cascade.recovered)
        cascade.clearRecovered()
        val after = FloatArray(4096) { 0.1f }
        cascade.process(after, 0, after.size)
        assertTrue(DspTestSignals.allFinite(after))
        assertTrue(!cascade.recovered)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Coefficients

    @Test
    fun `peaking coefficients at the ten ISO frequencies match the Audio EQ Cookbook`() {
        val c = DoubleArray(Biquad.STRIDE)
        for (fs in intArrayOf(44_100, 48_000)) for (fc in IsoBands.FREQUENCIES_HZ) for (g in doubleArrayOf(-12.0, 6.0, 12.0)) {
            Biquad.design(FilterType.PEAKING, fc, IsoBands.Q, g, fs, c, 0)
            // Written out again here from RBJ's formulas, independently of Biquad's own code.
            val a = 10.0.pow(g / 40)
            val w = 2 * PI * fc / fs
            val alpha = sin(w) / (2 * IsoBands.Q)
            val a0 = 1 + alpha / a
            val expected = doubleArrayOf((1 + alpha * a) / a0, -2 * cos(w) / a0, (1 - alpha * a) / a0, -2 * cos(w) / a0, (1 - alpha / a) / a0)
            assertArrayEquals("fc=$fc fs=$fs g=$g", expected, c, 1e-12)
        }
    }

    @Test
    fun `each ISO band reaches its gain exactly at its centre and leaves DC and Nyquist alone`() {
        val c = DoubleArray(Biquad.STRIDE)
        val fs = 48_000
        for (fc in IsoBands.FREQUENCIES_HZ) for (g in doubleArrayOf(-12.0, -3.0, 3.0, 12.0)) {
            Biquad.design(FilterType.PEAKING, fc, IsoBands.Q, g, fs, c, 0)
            assertEquals("centre of $fc Hz at $g dB", g, Biquad.magnitudeDb(c, 0, fc, fs), 1e-9)
            assertEquals("DC under $fc Hz", 0.0, Biquad.magnitudeDb(c, 0, 0.0001, fs), 0.01)
            // The 16 kHz band is close enough to 24 kHz to lean on it a little; every other band leaves it alone.
            if (fc < 16_000.0) assertEquals("Nyquist over $fc Hz", 0.0, Biquad.magnitudeDb(c, 0, fs / 2.0 - 1, fs), 0.05)
        }
    }

    @Test
    fun `a cut is the exact mirror of the same boost`() {
        val boost = DoubleArray(Biquad.STRIDE)
        val cut = DoubleArray(Biquad.STRIDE)
        for (fc in IsoBands.FREQUENCIES_HZ) {
            Biquad.design(FilterType.PEAKING, fc, IsoBands.Q, 9.0, 48_000, boost, 0)
            Biquad.design(FilterType.PEAKING, fc, IsoBands.Q, -9.0, 48_000, cut, 0)
            for (f in doubleArrayOf(20.0, 100.0, fc, 3_000.0, 15_000.0)) {
                assertEquals(-Biquad.magnitudeDb(boost, 0, f, 48_000), Biquad.magnitudeDb(cut, 0, f, 48_000), 1e-9)
            }
        }
    }

    @Test
    fun `shelves reach their gain on their own side and 0 dB on the other`() {
        val c = DoubleArray(Biquad.STRIDE)
        Biquad.design(FilterType.LOW_SHELF, 200.0, 0.707, 6.0, 48_000, c, 0)
        assertEquals(6.0, Biquad.magnitudeDb(c, 0, 5.0, 48_000), 0.01)
        assertEquals(0.0, Biquad.magnitudeDb(c, 0, 20_000.0, 48_000), 0.02)
        assertEquals(3.0, Biquad.magnitudeDb(c, 0, 200.0, 48_000), 0.01) // half the gain at the corner, in dB

        Biquad.design(FilterType.HIGH_SHELF, 4_000.0, 0.707, -6.0, 48_000, c, 0)
        assertEquals(-6.0, Biquad.magnitudeDb(c, 0, 23_900.0, 48_000), 0.05)
        assertEquals(0.0, Biquad.magnitudeDb(c, 0, 20.0, 48_000), 0.01)
    }

    @Test
    fun `the cascade's measured response matches the designed one`() {
        // A 1 kHz sine through the 1 kHz band at +6 dB comes out 6 dB louder.
        val fs = 48_000
        val cascade = BiquadCascade()
        cascade.configure(fs, 1)
        cascade.setTargets(listOf(IsoBands.spec(5, 6f)))
        cascade.snap()
        val signal = DspTestSignals.sine(1_000.0, 0.1, fs, 1, 1.0)
        cascade.process(signal, 0, signal.size)
        val tail = signal.copyOfRange(fs / 2, signal.size)
        assertEquals(0.1 * DspTestSignals.dbfs(6.0), DspTestSignals.peak(tail).toDouble(), 0.001)
    }

    @Test
    fun `gains glide to their target instead of jumping`() {
        val cascade = BiquadCascade()
        cascade.configure(48_000, 1)
        cascade.setTargets(listOf(IsoBands.spec(0, 0f)))
        cascade.snap()
        cascade.setTargets(listOf(IsoBands.spec(0, 12f)))
        cascade.glide()
        val first = cascade.currentGainDb(0)
        assertTrue("first step $first", first > 0.0 && first < 2.0)
        repeat(500) { cascade.glide() }
        assertEquals(12.0, cascade.currentGainDb(0), 0.0)
    }

    @Test
    fun `filters removed from the curve glide out and leave the chain`() {
        val cascade = BiquadCascade()
        cascade.configure(48_000, 2)
        cascade.setTargets(DspTestSignals.allBands(6f))
        cascade.snap()
        assertEquals(10, cascade.filterCount)
        cascade.setTargets(listOf(IsoBands.spec(0, 6f)))
        assertEquals(10, cascade.filterCount) // still gliding out
        repeat(500) { cascade.glide() }
        assertEquals(1, cascade.filterCount)
    }
}
