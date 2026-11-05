package com.ikko.aura.core.audio.dsp

import kotlin.math.abs

/**
 * A chain of biquads run in Direct Form II transposed, one state pair per filter and per channel, on interleaved
 * 32-bit float frames in place.
 *
 * Gains glide rather than jump: each block, a filter's gain moves a share of the way to its target and its
 * coefficients are recomputed, so dragging a slider sweeps the curve instead of stepping it (the zipper noise of a
 * coefficient change is a step in the transfer function). Frequency, Q and type are structural and are taken at once.
 *
 * Not thread-safe: owned by the audio thread. Allocates only in [configure].
 */
class BiquadCascade(val capacity: Int = MAX_FILTERS) {

    private var sampleRate = 0
    private var channels = 0
    private var count = 0

    /** How many of the [count] filters were asked for; the rest are gliding out. */
    private var liveCount = 0

    private val types = Array(capacity) { FilterType.PEAKING }
    private val frequencies = DoubleArray(capacity)
    private val qs = DoubleArray(capacity)
    private val targetGains = DoubleArray(capacity)
    private val currentGains = DoubleArray(capacity)
    private val coefficients = DoubleArray(capacity * Biquad.STRIDE)

    /** [filter][channel][s1, s2], flattened. */
    private var state = DoubleArray(0)

    /** Set when a state went non-finite and was cleared: the block it happened in is not to be trusted. */
    var recovered = false
        private set

    val filterCount: Int get() = count

    fun configure(sampleRate: Int, channels: Int) {
        val resized = channels != this.channels
        this.sampleRate = sampleRate
        this.channels = channels
        if (resized || state.size != capacity * channels * 2) state = DoubleArray(capacity * channels * 2)
        reset()
        for (i in 0 until count) redesign(i)
    }

    /** Clears the filters' memory, as after a seek. Coefficients are kept. */
    fun reset() {
        state.fill(0.0)
        recovered = false
    }

    /**
     * Sets what the chain should become. Filters past [specs]' size are retired by gliding their gain to 0 dB, which
     * for every type here is the identity. Returns false (and changes nothing) when there are more filters than room.
     */
    fun setTargets(specs: List<FilterSpec>): Boolean {
        if (specs.size > capacity) return false
        val newCount = maxOf(specs.size, count)
        for (i in 0 until newCount) {
            if (i < specs.size) {
                val s = specs[i]
                val structural = i >= count || types[i] != s.type || frequencies[i] != s.frequencyHz || qs[i] != s.q
                types[i] = s.type
                frequencies[i] = s.frequencyHz
                qs[i] = s.q
                targetGains[i] = if (s.gainDb.isFinite()) s.gainDb else 0.0
                if (structural) redesign(i)
            } else {
                targetGains[i] = 0.0
            }
        }
        count = newCount
        liveCount = specs.size
        return true
    }

    /** Jumps every gain to its target: used when the chain is not audible yet, so there is nothing to glide from. */
    fun snap() {
        for (i in 0 until count) {
            if (currentGains[i] != targetGains[i]) {
                currentGains[i] = targetGains[i]
                redesign(i)
            }
        }
        trimRetired()
    }

    /** One step of the glide; called once per block by the engine. */
    fun glide() {
        var changed = false
        for (i in 0 until count) {
            val target = targetGains[i]
            val current = currentGains[i]
            if (current == target) continue
            val diff = target - current
            currentGains[i] = if (abs(diff) < SNAP_DB) target else current + diff * GLIDE
            // Arrived at 0 dB, the filter is skipped (see process); it comes back later from a clean state.
            if (currentGains[i] == 0.0 && target == 0.0) clearState(i)
            redesign(i)
            changed = true
        }
        if (changed) trimRetired()
    }

    /** Runs [frames] interleaved frames starting at frame [startFrame] through every audible filter. */
    fun process(buffer: FloatArray, startFrame: Int, frames: Int) {
        val ch = channels
        if (ch == 0 || frames <= 0) return
        val c = coefficients
        val st = state
        for (f in 0 until count) {
            // A filter at 0 dB is the identity, and its state stays at zero: skipping it costs nothing audible.
            if (currentGains[f] == 0.0 && targetGains[f] == 0.0) continue
            val o = f * Biquad.STRIDE
            val b0 = c[o]
            val b1 = c[o + 1]
            val b2 = c[o + 2]
            val a1 = c[o + 3]
            val a2 = c[o + 4]
            for (channel in 0 until ch) {
                val so = (f * ch + channel) * 2
                var s1 = st[so]
                var s2 = st[so + 1]
                var idx = startFrame * ch + channel
                val end = (startFrame + frames) * ch
                while (idx < end) {
                    val x = buffer[idx].toDouble()
                    val y = b0 * x + s1
                    s1 = b1 * x - a1 * y + s2
                    s2 = b2 * x - a2 * y
                    buffer[idx] = y.toFloat()
                    idx += ch
                }
                // NaN fails every comparison, so this also catches it. A state this large is a filter gone wrong.
                if (!(abs(s1) < STATE_LIMIT && abs(s2) < STATE_LIMIT)) {
                    s1 = 0.0
                    s2 = 0.0
                    recovered = true
                }
                st[so] = s1
                st[so + 1] = s2
            }
        }
    }

    fun clearRecovered() {
        recovered = false
    }

    /** The coefficients of filter [index] as they are now, for tests and the response curve. */
    fun coefficientsOf(index: Int, out: DoubleArray, offset: Int = 0) {
        System.arraycopy(coefficients, index * Biquad.STRIDE, out, offset, Biquad.STRIDE)
    }

    fun currentGainDb(index: Int): Double = currentGains[index]

    private fun redesign(i: Int) {
        Biquad.design(types[i], frequencies[i], qs[i], currentGains[i], sampleRate, coefficients, i * Biquad.STRIDE)
    }

    /** Filters retired by [setTargets] leave the chain once they have glided to 0 dB. */
    private fun trimRetired() {
        while (count > liveCount && currentGains[count - 1] == 0.0 && targetGains[count - 1] == 0.0) {
            clearState(count - 1)
            count--
        }
    }

    private fun clearState(i: Int) {
        val ch = channels
        for (channel in 0 until ch) {
            val so = (i * ch + channel) * 2
            state[so] = 0.0
            state[so + 1] = 0.0
        }
    }

    companion object {
        /** The ten graphic bands, or an AutoEq profile (they rarely use more than ten). */
        const val MAX_FILTERS = 16

        /** The share of the remaining distance a gain covers each block: about 13 ms of time constant at 48 kHz. */
        private const val GLIDE = 0.1
        private const val SNAP_DB = 0.005
        private const val STATE_LIMIT = 1e6
    }
}
