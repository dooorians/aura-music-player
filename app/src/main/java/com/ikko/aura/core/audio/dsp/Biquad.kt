package com.ikko.aura.core.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The three shapes AutoEq's parametric profiles use: PK, LSC and HSC. */
enum class FilterType { PEAKING, LOW_SHELF, HIGH_SHELF }

/**
 * One filter as a person (or AutoEq) writes it: a type, a corner or centre frequency, a Q and a gain.
 * The same shape describes a graphic band (a peaking filter at an ISO frequency) and a line of an AutoEq profile.
 */
data class FilterSpec(
    val type: FilterType,
    val frequencyHz: Double,
    val q: Double,
    val gainDb: Double,
)

/**
 * Biquad design from Robert Bristow-Johnson's Audio EQ Cookbook, normalised so a0 = 1.
 *
 * Coefficients are laid out five to a filter in a flat [DoubleArray]: b0, b1, b2, a1, a2. They are computed and
 * kept in double precision: at 31 Hz and 96 kHz the poles sit within 0.002 of the unit circle, and float coefficients
 * that close to it are where a low band starts to hum or drift. The samples themselves stay 32-bit float.
 */
object Biquad {
    const val STRIDE = 5

    /** Above this fraction of the sample rate a band is folded down, so a 16 kHz band at 32 kHz stays a filter. */
    private const val MAX_FREQUENCY_RATIO = 0.45

    private const val MIN_Q = 0.05
    private const val MAX_Q = 40.0

    /** Writes the identity filter (y = x) at [offset]. */
    fun identity(out: DoubleArray, offset: Int) {
        out[offset] = 1.0
        out[offset + 1] = 0.0
        out[offset + 2] = 0.0
        out[offset + 3] = 0.0
        out[offset + 4] = 0.0
    }

    fun design(spec: FilterSpec, sampleRate: Int, out: DoubleArray, offset: Int) =
        design(spec.type, spec.frequencyHz, spec.q, spec.gainDb, sampleRate, out, offset)

    /**
     * Writes the coefficients of one filter at [offset]. Whatever comes in — a frequency past Nyquist, a Q of zero,
     * a NaN gain from a broken import — what goes out is a stable filter, at worst the identity.
     */
    fun design(
        type: FilterType,
        frequencyHz: Double,
        q: Double,
        gainDb: Double,
        sampleRate: Int,
        out: DoubleArray,
        offset: Int,
    ) {
        if (sampleRate <= 0 || !frequencyHz.isFinite() || frequencyHz <= 0.0 || !gainDb.isFinite() || !q.isFinite() || gainDb == 0.0) {
            identity(out, offset)
            return
        }
        val fc = frequencyHz.coerceAtMost(sampleRate * MAX_FREQUENCY_RATIO)
        val qq = q.coerceIn(MIN_Q, MAX_Q)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * fc / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * qq)

        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        when (type) {
            FilterType.PEAKING -> {
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cosW
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cosW
                a2 = 1.0 - alpha / a
            }
            FilterType.LOW_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW)
                b2 = a * ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha)
                a0 = (a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW)
                a2 = (a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha
            }
            FilterType.HIGH_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW)
                b2 = a * ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha)
                a0 = (a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW)
                a2 = (a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha
            }
        }
        out[offset] = b0 / a0
        out[offset + 1] = b1 / a0
        out[offset + 2] = b2 / a0
        out[offset + 3] = a1 / a0
        out[offset + 4] = a2 / a0
        if (!isStable(out, offset)) identity(out, offset)
    }

    /**
     * Jury's criterion for a second-order denominator 1 + a1 z^-1 + a2 z^-2: both poles strictly inside the unit circle.
     */
    fun isStable(c: DoubleArray, offset: Int): Boolean {
        val a1 = c[offset + 3]
        val a2 = c[offset + 4]
        if (!a1.isFinite() || !a2.isFinite() || !c[offset].isFinite() || !c[offset + 1].isFinite() || !c[offset + 2].isFinite()) return false
        return kotlin.math.abs(a2) < 1.0 && kotlin.math.abs(a1) < 1.0 + a2
    }

    /** |H(e^jw)| of one filter at [frequencyHz], in dB. Allocation-free: called per point of the UI's curve. */
    fun magnitudeDb(c: DoubleArray, offset: Int, frequencyHz: Double, sampleRate: Int): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val cos1 = cos(w)
        val sin1 = sin(w)
        val cos2 = cos(2.0 * w)
        val sin2 = sin(2.0 * w)
        val b0 = c[offset]
        val b1 = c[offset + 1]
        val b2 = c[offset + 2]
        val a1 = c[offset + 3]
        val a2 = c[offset + 4]
        val numRe = b0 + b1 * cos1 + b2 * cos2
        val numIm = -(b1 * sin1 + b2 * sin2)
        val denRe = 1.0 + a1 * cos1 + a2 * cos2
        val denIm = -(a1 * sin1 + a2 * sin2)
        val num = numRe * numRe + numIm * numIm
        val den = denRe * denRe + denIm * denIm
        if (den <= 0.0 || num <= 0.0) return -120.0
        return 10.0 * log10(num / den)
    }
}

/** The standard ISO 266 octave centres of a 10-band graphic equaliser. */
object IsoBands {
    val FREQUENCIES_HZ = doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)
    val LABELS = arrayOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    const val COUNT = 10

    /** One octave wide: Q = sqrt(2) / (2 - 1). Neighbouring bands meet at about -3 dB of each other's gain. */
    const val Q = 1.4142135623730951

    /** What a slider can reach, either way. */
    const val MAX_GAIN_DB = 12f

    fun spec(band: Int, gainDb: Float) = FilterSpec(FilterType.PEAKING, FREQUENCIES_HZ[band], Q, gainDb.toDouble())
}
