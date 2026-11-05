package com.ikko.aura.core.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * The last stage: nothing leaves it above 0 dBFS.
 *
 * A soft-knee peak limiter whose transfer curve is a tanh: a frame peaking under the knee (-1 dBFS) passes as it is;
 * above it, the peak is mapped to `knee + (1 - knee) · tanh((peak - knee) / (1 - knee))`, which leaves the knee with
 * the same slope it arrived with and tends to 1.0 without passing it. That curve is applied as a gain — linked
 * across channels, so the stereo image does not lean — that drops at once when a frame needs it and recovers over
 * [RELEASE_SECONDS]. Shaping the gain rather than each sample is what keeps a +12 dB boost from turning into the
 * buzz of a waveshaper: the waveform is turned down, not bent.
 *
 * The attack being instant, a frame always gets at least the reduction it needs, so the output's magnitude is bound
 * by the tanh curve, at most 1.0. A final clamp makes that true of float rounding too. A frame that is not finite (a
 * NaN from upstream) comes out as silence, and [sawNonFinite] tells the engine.
 */
class Limiter {

    private var channels = 0
    private var release = 0.0
    private var gain = 1.0

    var sawNonFinite = false
        private set

    /** The gain applied to the last frame (1.0 = no reduction). */
    val currentGain: Double get() = gain

    fun configure(sampleRate: Int, channels: Int) {
        this.channels = channels
        release = exp(-1.0 / (RELEASE_SECONDS * sampleRate.coerceAtLeast(1)))
        reset()
    }

    fun reset() {
        gain = 1.0
        sawNonFinite = false
    }

    fun clearNonFinite() {
        sawNonFinite = false
    }

    fun process(buffer: FloatArray, startFrame: Int, frames: Int) {
        val ch = channels
        if (ch == 0) return
        var g = gain
        val r = release
        var idx = startFrame * ch
        val end = (startFrame + frames) * ch
        while (idx < end) {
            var peak = 0.0
            var finite = true
            for (c in 0 until ch) {
                val a = abs(buffer[idx + c])
                // NaN fails the comparison, infinity is past MAX_VALUE: both are caught here.
                if (!(a <= Float.MAX_VALUE)) finite = false else if (a > peak) peak = a.toDouble()
            }
            if (!finite) {
                for (c in 0 until ch) buffer[idx + c] = 0f
                sawNonFinite = true
                idx += ch
                continue
            }
            val needed = if (peak > KNEE) curve(peak) / peak else 1.0
            g = if (needed < g) needed else needed + (g - needed) * r
            val gf = g.toFloat()
            for (c in 0 until ch) {
                val y = buffer[idx + c] * gf
                buffer[idx + c] = if (y > 1f) 1f else if (y < -1f) -1f else y
            }
            idx += ch
        }
        gain = g
    }

    companion object {
        /** Where the knee starts: -1 dBFS. */
        const val KNEE = 0.8912509381337456

        const val RELEASE_SECONDS = 0.12

        private const val SPAN = 1.0 - KNEE

        /** The output peak for an input peak [x] ≥ [KNEE]: continuous in value and slope at the knee, never above 1.0. */
        fun curve(x: Double): Double = (KNEE + SPAN * tanh((x - KNEE) / SPAN)).coerceAtMost(1.0)
    }
}
