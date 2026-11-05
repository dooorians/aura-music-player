package com.ikko.aura.core.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * The whole chain on interleaved 32-bit float frames, in place:
 *
 *     in ──┬── loudness gain + preamp ── biquad cascade ── limiter ──┐
 *          │                                                         ├── crossfade ── out
 *          └──────────────────────────── dry ────────────────────────┘
 *
 * Work is done in blocks of [BLOCK_FRAMES]: parameters (the filters' gains, the gain in front of them) move once per
 * block and are ramped inside it, so nothing the listener changes arrives as a step. Switching the bypass fades
 * between the dry and the processed signal over [BYPASS_FADE_S] rather than cutting from one to the other; while
 * fully bypassed, the frames are left exactly as they came.
 *
 * Pure Kotlin, no Android: what the unit tests drive directly. Owned by one audio thread; allocates only in
 * [configure].
 */
class DspEngine(private val parameters: DspParameters) {

    private val cascade = BiquadCascade()
    private val limiter = Limiter()
    private val normalizer = LoudnessNormalizer()

    var sampleRate = 0
        private set
    var channels = 0
        private set

    private var applied: DspConfig? = null
    private var dry = FloatArray(0)

    /** Share of the processed signal in the output: 1 processing, 0 bypassed. */
    private var wet = 1f
    private var wetStep = 0f

    /** The gain in front of the filters, linear, as of the end of the last block. */
    private var gain = 1.0
    private var gainGlide = 1.0

    /** The leveller's gain now, in dB: for the screen and the tests. */
    val normalizationGainDb: Double get() = normalizer.gainDb

    val isFullyBypassed: Boolean get() = wet == 0f && applied?.bypass == true

    fun configure(sampleRate: Int, channels: Int) {
        require(sampleRate > 0 && channels > 0) { "sampleRate=$sampleRate channels=$channels" }
        this.sampleRate = sampleRate
        this.channels = channels
        cascade.configure(sampleRate, channels)
        limiter.configure(sampleRate, channels)
        normalizer.configure(sampleRate, channels)
        dry = FloatArray(BLOCK_FRAMES * channels)
        wetStep = (1.0 / (BYPASS_FADE_S * sampleRate)).toFloat()
        gainGlide = 1.0 - exp(-BLOCK_FRAMES / (GAIN_TIME_CONSTANT_S * sampleRate))
        applied = null
    }

    /** A discontinuity in the same track (a seek): the filters forget, the loudness measurement does not. */
    fun flush() {
        cascade.reset()
        limiter.reset()
    }

    /** A new track begins: measured afresh, or levelled from its tag. */
    fun startTrack(trackGain: TrackGain?) {
        normalizer.startTrack(trackGain)
        flush()
    }

    fun process(buffer: FloatArray, frames: Int) {
        if (channels == 0 || frames <= 0) return
        // The leveller first, on the incoming audio: a tagged track's gain is then known before its first block.
        val incoming = parameters.config
        if (normalizer.needsMeasurement(incoming.normalizationEnabled)) normalizer.meter.process(buffer, frames)
        normalizer.advance(frames, incoming.targetLufs, incoming.normalizationEnabled)
        val config = sync()

        val targetWet = if (config.bypass) 0f else 1f
        if (wet == 0f && targetWet == 0f) return
        if (wet == 0f) {
            // Coming out of bypass: the chain was not running, so it starts from rest and at its settings, and the
            // fade in from the dry signal covers its first milliseconds.
            cascade.reset()
            cascade.snap()
            limiter.reset()
            gain = targetGain(config)
        }

        val target = targetGain(config)
        val ch = channels
        var start = 0
        while (start < frames) {
            val n = min(BLOCK_FRAMES, frames - start)
            val mixing = wet != targetWet || wet < 1f
            if (mixing) System.arraycopy(buffer, start * ch, dry, 0, n * ch)

            applyGainRamp(buffer, start, n, target)
            cascade.glide()
            cascade.process(buffer, start, n)
            limiter.process(buffer, start, n)
            if (cascade.recovered || limiter.sawNonFinite) {
                cascade.reset()
                cascade.clearRecovered()
                limiter.clearNonFinite()
            }

            if (mixing) crossfade(buffer, start, n, targetWet)
            start += n
        }
    }

    private fun sync(): DspConfig {
        val config = parameters.config
        if (config === applied) return config
        val filters = if (config.filters.size > cascade.capacity) config.filters.subList(0, cascade.capacity) else config.filters
        cascade.setTargets(filters)
        if (applied == null) {
            // The first settings of a stream: nothing has been heard yet, so nothing to glide from.
            cascade.snap()
            wet = if (config.bypass) 0f else 1f
            gain = targetGain(config)
        }
        applied = config
        return config
    }

    private fun targetGain(config: DspConfig): Double = dbToLinear(normalizer.gainDb + config.totalPreampDb)

    /** Moves the gain a block's share of the way to [target], ramping it linearly across the block's frames. */
    private fun applyGainRamp(buffer: FloatArray, start: Int, frames: Int, target: Double) {
        val from = gain
        var to = from + (target - from) * gainGlide
        if (abs(to - target) < 1e-6) to = target
        gain = to
        val ch = channels
        if (from == 1.0 && to == 1.0) return
        val step = (to - from) / frames
        var g = from
        var idx = start * ch
        for (i in 0 until frames) {
            g += step
            val gf = g.toFloat()
            for (c in 0 until ch) buffer[idx + c] *= gf
            idx += ch
        }
    }

    private fun crossfade(buffer: FloatArray, start: Int, frames: Int, targetWet: Float) {
        val ch = channels
        var w = wet
        var idx = start * ch
        var d = 0
        for (i in 0 until frames) {
            w = if (targetWet > w) min(targetWet, w + wetStep) else maxOf(targetWet, w - wetStep)
            for (c in 0 until ch) {
                val dryValue = dry[d + c]
                buffer[idx + c] = dryValue + (buffer[idx + c] - dryValue) * w
            }
            idx += ch
            d += ch
        }
        wet = w
    }

    companion object {
        const val BLOCK_FRAMES = 64
        const val BYPASS_FADE_S = 0.03
        private const val GAIN_TIME_CONSTANT_S = 0.05

        fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
    }
}
