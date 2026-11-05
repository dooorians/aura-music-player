package com.ikko.aura.core.audio.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Integrated loudness after ITU-R BS.1770-4 / EBU R128: K-weighting (the head's high shelf, then the RLB high-pass),
 * mean square over 400 ms blocks overlapping by 75 %, then the two gates — absolute at -70 LUFS, relative at 10 LU
 * under the loudness of the blocks that passed the first.
 *
 * Blocks are kept as a histogram of 0.1 LU bins rather than a list, so a track of any length is measured in a fixed
 * amount of memory and the integrated value is a sum over 850 bins. Allocates only in [configure].
 */
class LoudnessMeter {

    private var channels = 0
    private var sampleRate = 0

    // K-weighting coefficients (b0, b1, b2, a1, a2) for each of the two stages.
    private val shelf = DoubleArray(5)
    private val highPass = DoubleArray(5)
    private var shelfState = DoubleArray(0)
    private var highPassState = DoubleArray(0)
    private var weights = DoubleArray(0)

    /** Samples per 100 ms step, and the sum of weighted squares of the step being filled. */
    private var stepFrames = 0
    private var stepFilled = 0
    private var stepEnergy = 0.0

    /** The last four steps: a 400 ms block is their mean. */
    private val steps = DoubleArray(4)
    private var stepsSeen = 0

    private val histogram = IntArray(BINS)
    private var blocks = 0

    /** Loudness of the latest 400 ms block, in LUFS; [Double.NEGATIVE_INFINITY] before the first. */
    var momentaryLufs = Double.NEGATIVE_INFINITY
        private set

    /** How much of the track has passed the absolute gate, in seconds of 100 ms steps. */
    val gatedSeconds: Double get() = blocks * 0.1

    fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        designKWeighting(sampleRate)
        shelfState = DoubleArray(channels * 2)
        highPassState = DoubleArray(channels * 2)
        weights = DoubleArray(channels) { channelWeight(it, channels) }
        stepFrames = (sampleRate / 10).coerceAtLeast(1)
        reset()
    }

    /** Forgets the track: a new one is starting. */
    fun reset() {
        shelfState.fill(0.0)
        highPassState.fill(0.0)
        stepFilled = 0
        stepEnergy = 0.0
        steps.fill(0.0)
        stepsSeen = 0
        histogram.fill(0)
        blocks = 0
        momentaryLufs = Double.NEGATIVE_INFINITY
    }

    /** Reads [frames] interleaved frames; the buffer is not changed. */
    fun process(buffer: FloatArray, frames: Int) {
        val ch = channels
        if (ch == 0) return
        val sb0 = shelf[0]; val sb1 = shelf[1]; val sb2 = shelf[2]; val sa1 = shelf[3]; val sa2 = shelf[4]
        val hb0 = highPass[0]; val hb1 = highPass[1]; val hb2 = highPass[2]; val ha1 = highPass[3]; val ha2 = highPass[4]
        var idx = 0
        for (frame in 0 until frames) {
            var energy = 0.0
            for (c in 0 until ch) {
                val w = weights[c]
                val x = buffer[idx + c].toDouble()
                val so = c * 2
                // Stage 1: the shelf.
                val y1 = sb0 * x + shelfState[so]
                shelfState[so] = sb1 * x - sa1 * y1 + shelfState[so + 1]
                shelfState[so + 1] = sb2 * x - sa2 * y1
                // Stage 2: the high-pass.
                val y2 = hb0 * y1 + highPassState[so]
                highPassState[so] = hb1 * y1 - ha1 * y2 + highPassState[so + 1]
                highPassState[so + 1] = hb2 * y1 - ha2 * y2
                if (w != 0.0) energy += w * y2 * y2
            }
            idx += ch
            if (energy.isFinite()) stepEnergy += energy
            if (++stepFilled == stepFrames) closeStep()
        }
    }

    /**
     * The gated integrated loudness so far, in LUFS, or NaN while nothing has passed the absolute gate.
     */
    fun integratedLufs(): Double {
        if (blocks == 0) return Double.NaN
        var sum = 0.0
        var n = 0L
        for (i in 0 until BINS) {
            val count = histogram[i]
            if (count != 0) {
                sum += count * binEnergy(i)
                n += count
            }
        }
        val relativeGate = energyToLufs(sum / n) - RELATIVE_GATE_LU
        val first = binOf(relativeGate).coerceAtLeast(0)
        sum = 0.0
        n = 0L
        for (i in first until BINS) {
            val count = histogram[i]
            if (count != 0) {
                sum += count * binEnergy(i)
                n += count
            }
        }
        return if (n == 0L) Double.NaN else energyToLufs(sum / n)
    }

    private fun closeStep() {
        // A NaN that reached the filters would stay in them for good: they start over instead.
        for (i in shelfState.indices) {
            if (!shelfState[i].isFinite() || !highPassState[i].isFinite()) {
                shelfState.fill(0.0)
                highPassState.fill(0.0)
                break
            }
        }
        steps[stepsSeen and 3] = stepEnergy / stepFrames
        stepsSeen++
        stepFilled = 0
        stepEnergy = 0.0
        if (stepsSeen < 4) return
        val z = (steps[0] + steps[1] + steps[2] + steps[3]) / 4.0
        val lufs = energyToLufs(z)
        momentaryLufs = lufs
        if (lufs > ABSOLUTE_GATE_LUFS) {
            histogram[binOf(lufs).coerceIn(0, BINS - 1)]++
            blocks++
        }
    }

    /**
     * The BS.1770 filters at any rate: the published 48 kHz coefficients come from these analogue prototypes
     * (as derived for libebur128), so other rates get the same curve rather than a resampled one.
     */
    private fun designKWeighting(fs: Int) {
        run {
            val f0 = 1681.974450955533
            val g = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / fs)
            val vh = 10.0.pow(g / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            shelf[0] = (vh + vb * k / q + k * k) / a0
            shelf[1] = 2.0 * (k * k - vh) / a0
            shelf[2] = (vh - vb * k / q + k * k) / a0
            shelf[3] = 2.0 * (k * k - 1.0) / a0
            shelf[4] = (1.0 - k / q + k * k) / a0
        }
        run {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / fs)
            val a0 = 1.0 + k / q + k * k
            highPass[0] = 1.0
            highPass[1] = -2.0
            highPass[2] = 1.0
            highPass[3] = 2.0 * (k * k - 1.0) / a0
            highPass[4] = (1.0 - k / q + k * k) / a0
        }
    }

    companion object {
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = 10.0

        private const val BIN_LU = 0.1
        private const val HISTOGRAM_TOP_LUFS = 15.0
        private val BINS = ((HISTOGRAM_TOP_LUFS - ABSOLUTE_GATE_LUFS) / BIN_LU).toInt()

        private val BIN_ENERGIES = DoubleArray(BINS) { lufsToEnergy(ABSOLUTE_GATE_LUFS + (it + 0.5) * BIN_LU) }

        private fun binOf(lufs: Double): Int = ((lufs - ABSOLUTE_GATE_LUFS) / BIN_LU).toInt()
        private fun binEnergy(i: Int) = BIN_ENERGIES[i]

        fun energyToLufs(z: Double): Double = if (z <= 0.0) Double.NEGATIVE_INFINITY else -0.691 + 10.0 * log10(z)
        fun lufsToEnergy(lufs: Double): Double = 10.0.pow((lufs + 0.691) / 10.0)

        /**
         * BS.1770's channel weights, for the usual layouts: 1.0 for the front channels, 1.41 (+1.5 dB) for the
         * surrounds, and nothing for the LFE of a 5.1 or 7.1 (L R C LFE Ls Rs [Lb Rb]).
         */
        fun channelWeight(channel: Int, channels: Int): Double = when {
            channels < 6 -> 1.0
            channel == 3 -> 0.0
            channel >= 4 -> 1.41
            else -> 1.0
        }
    }
}
