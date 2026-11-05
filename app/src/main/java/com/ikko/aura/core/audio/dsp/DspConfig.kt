package com.ikko.aura.core.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Everything the audio thread needs to know, as one immutable value. The UI publishes a new one; each processor
 * notices the new reference at its next buffer and takes it whole, so it never sees half of a change.
 */
data class DspConfig(
    /** Off: the audio goes through untouched (crossfaded, see [DspEngine]). */
    val bypass: Boolean = false,
    val normalizationEnabled: Boolean = false,
    val targetLufs: Double = LoudnessNormalizer.DEFAULT_TARGET_LUFS,
    /** The listener's own preamp, in dB, on top of the automatic headroom. */
    val preampDb: Double = 0.0,
    /** Takes away as much gain up front as the curve adds at its highest, so a boost does not run into the limiter. */
    val autoPreamp: Boolean = true,
    val filters: List<FilterSpec> = IsoBands.FREQUENCIES_HZ.indices.map { IsoBands.spec(it, 0f) },
) {
    /** The automatic part of the preamp: minus the curve's highest boost, never a gain. */
    val headroomDb: Double = if (autoPreamp) -EqResponse.maxBoostDb(filters) else 0.0

    /** All the gain in front of the filters, apart from loudness normalisation. */
    val totalPreampDb: Double get() = preampDb + headroomDb
}

/**
 * The shared, current [DspConfig]. One instance for the app: both of the service's players read the same one, so
 * the curve applies to whichever is audible, through a crossfade too.
 *
 * Starts bypassed: until the saved settings are read, the audio goes through as it is rather than through a
 * default curve.
 */
class DspParameters(initial: DspConfig = DspConfig(bypass = true)) {
    @Volatile
    var config: DspConfig = initial
        private set

    @Synchronized
    fun update(transform: (DspConfig) -> DspConfig) {
        config = transform(config)
    }

    fun set(config: DspConfig) = update { config }
}

/**
 * The combined magnitude response of a set of filters on a fixed logarithmic grid of frequencies.
 *
 * The grid's sines and cosines are worked out once, so an evaluation is multiplications and one log per point and
 * filter: cheap enough to run on every frame of a slider drag, and allocation-free after construction.
 */
class EqResponse(
    val points: Int = DEFAULT_POINTS,
    val minHz: Double = 20.0,
    val maxHz: Double = 20_000.0,
    private val sampleRate: Int = REFERENCE_RATE,
) {
    val frequencies = DoubleArray(points) { i -> minHz * (maxHz / minHz).pow(i / (points - 1.0)) }
    private val cos1 = DoubleArray(points)
    private val sin1 = DoubleArray(points)
    private val cos2 = DoubleArray(points)
    private val sin2 = DoubleArray(points)
    private val coefficients = DoubleArray(BiquadCascade.MAX_FILTERS * Biquad.STRIDE)

    init {
        for (i in 0 until points) {
            val w = 2.0 * PI * frequencies[i] / sampleRate
            cos1[i] = cos(w); sin1[i] = sin(w); cos2[i] = cos(2 * w); sin2[i] = sin(2 * w)
        }
    }

    /** Where frequency [hz] falls along the grid's width, from 0 to 1. */
    fun position(hz: Double): Float = (ln(hz / minHz) / ln(maxHz / minHz)).toFloat()

    /** The response of [filters], in dB, into [out] (at least [points] long). */
    fun compute(filters: List<FilterSpec>, out: FloatArray) {
        val n = minOf(filters.size, BiquadCascade.MAX_FILTERS)
        for (f in 0 until n) Biquad.design(filters[f], sampleRate, coefficients, f * Biquad.STRIDE)
        evaluate(n, out)
    }

    /** The response of the ten ISO bands at [gainsDb], without building any [FilterSpec]. */
    fun computeGraphic(gainsDb: FloatArray, out: FloatArray) {
        val n = minOf(gainsDb.size, IsoBands.COUNT)
        for (f in 0 until n) {
            Biquad.design(FilterType.PEAKING, IsoBands.FREQUENCIES_HZ[f], IsoBands.Q, gainsDb[f].toDouble(), sampleRate, coefficients, f * Biquad.STRIDE)
        }
        evaluate(n, out)
    }

    private fun evaluate(filterCount: Int, out: FloatArray) {
        val c = coefficients
        for (i in 0 until points) {
            var ratio = 1.0
            for (f in 0 until filterCount) {
                val o = f * Biquad.STRIDE
                val numRe = c[o] + c[o + 1] * cos1[i] + c[o + 2] * cos2[i]
                val numIm = -(c[o + 1] * sin1[i] + c[o + 2] * sin2[i])
                val denRe = 1.0 + c[o + 3] * cos1[i] + c[o + 4] * cos2[i]
                val denIm = -(c[o + 3] * sin1[i] + c[o + 4] * sin2[i])
                ratio *= (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm)
            }
            out[i] = if (ratio > 0.0 && ratio.isFinite()) (10.0 * log10(ratio)).toFloat() else -120f
        }
    }

    companion object {
        const val DEFAULT_POINTS = 128
        const val REFERENCE_RATE = 48_000

        private val shared = ThreadLocal.withInitial { EqResponse(points = 160) }
        private val scratch = ThreadLocal.withInitial { FloatArray(160) }

        /** The highest point of the curve, in dB, or 0 if it only cuts. */
        fun maxBoostDb(filters: List<FilterSpec>): Double {
            var any = false
            for (i in filters.indices) if (filters[i].gainDb > 0.0) { any = true; break }
            if (!any) return 0.0
            val response = shared.get()!!
            val out = scratch.get()!!
            response.compute(filters, out)
            var peak = 0f
            for (i in 0 until response.points) peak = max(peak, out[i])
            return peak.toDouble()
        }
    }
}
