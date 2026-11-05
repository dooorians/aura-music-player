package com.ikko.aura.player.silence

import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The arithmetic behind "is this silent": decibels relative to full scale (dBFS) on one side,
 * linear amplitude on the other.
 *
 * Levels are RMS over a short window rather than peaks. A single stray sample (a click, dither,
 * the edge of a fade) should not count as music, and a quiet but real passage should not count as
 * silence because it happens to have no peak above the line.
 *
 * Full scale is 1.0: a 16-bit sample is divided by 32768, a float sample is taken as is.
 */
object SilenceLevels {

    /** The default line under which audio is treated as silence. -50 dBFS is about 104 on the 16-bit scale. */
    const val DEFAULT_THRESHOLD_DBFS = -50.0

    private const val PCM16_FULL_SCALE = 32768.0

    /** -50 dBFS → 0.00316. */
    fun dbfsToLinear(dbfs: Double): Double = 10.0.pow(dbfs / 20.0)

    /** 0.00316 → -50 dBFS. Zero (digital silence) is minus infinity. */
    fun linearToDbfs(linear: Double): Double =
        if (linear <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(linear)

    /** True when [rmsLinear] is strictly under [thresholdDbfs]. Exactly on the line counts as sound. */
    fun isSilent(rmsLinear: Double, thresholdDbfs: Double): Boolean = rmsLinear < dbfsToLinear(thresholdDbfs)

    /** RMS of [count] interleaved 16-bit samples, in full-scale units (0..1). */
    fun rmsPcm16(samples: ShortArray, offset: Int = 0, count: Int = samples.size - offset): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in offset until offset + count) {
            val s = samples[i] / PCM16_FULL_SCALE
            sum += s * s
        }
        return sqrt(sum / count)
    }

    /** RMS of [count] interleaved float samples. */
    fun rmsFloat(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in offset until offset + count) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / count)
    }

    /**
     * RMS of [byteCount] bytes of 16-bit PCM starting at [byteOffset] in [buffer], read with absolute
     * indexes so the buffer's position is left alone. The buffer must already be in native order.
     */
    fun rmsPcm16(buffer: ByteBuffer, byteOffset: Int, byteCount: Int): Double {
        val sampleCount = byteCount / 2
        if (sampleCount <= 0) return 0.0
        var sum = 0.0
        var index = byteOffset
        repeat(sampleCount) {
            val s = buffer.getShort(index) / PCM16_FULL_SCALE
            sum += s * s
            index += 2
        }
        return sqrt(sum / sampleCount)
    }

    /** Same as the 16-bit overload, for 32-bit float PCM. */
    fun rmsFloat(buffer: ByteBuffer, byteOffset: Int, byteCount: Int): Double {
        val sampleCount = byteCount / 4
        if (sampleCount <= 0) return 0.0
        var sum = 0.0
        var index = byteOffset
        repeat(sampleCount) {
            val s = buffer.getFloat(index).toDouble()
            sum += s * s
            index += 4
        }
        return sqrt(sum / sampleCount)
    }
}
