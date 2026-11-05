package com.ikko.aura.core.audio.dsp

import kotlin.math.exp

/**
 * What a track's tags say about its loudness: the gain that brings it to [referenceLufs].
 * ReplayGain 2.0 measures against -18 LUFS, Opus's R128_TRACK_GAIN against -23.
 */
data class TrackGain(val gainDb: Double, val referenceLufs: Double) {
    /** The gain that brings the track to [targetLufs] instead. */
    fun toTarget(targetLufs: Double): Double = gainDb + (targetLufs - referenceLufs)

    companion object {
        const val REPLAYGAIN_REFERENCE_LUFS = -18.0
        const val R128_REFERENCE_LUFS = -23.0
    }
}

/**
 * The volume leveller in front of the equaliser: brings each track to the target loudness (-14 LUFS by default,
 * what the streaming services play at).
 *
 * A tagged track is known before its first sample, and gets its gain at once. An untagged one is measured as it
 * plays ([LoudnessMeter]); the gain follows the *integrated* loudness, which settles as the track goes on, through a
 * slow one-pole — quicker in the first seconds, when the estimate is still moving and the previous track's gain is
 * all there is to go on, then [SETTLED_TIME_CONSTANT_S]. A loud chorus moves the integrated value by a fraction of a
 * dB, so the gain does not breathe with the music: there is no pumping, by construction.
 */
class LoudnessNormalizer {

    val meter = LoudnessMeter()

    private var sampleRate = 0
    private var tag: TrackGain? = null
    private var trackSeconds = 0.0

    /** The gain the leveller asks for now, in dB. The engine smooths it into the signal. */
    var gainDb = 0.0
        private set

    val usesTag: Boolean get() = tag != null

    fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        meter.configure(sampleRate, channels)
    }

    /**
     * A new track. Its tag if it has one; otherwise measurement starts over, and the gain carries on from the
     * previous track's until the new one has been heard long enough to say better.
     */
    fun startTrack(trackGain: TrackGain?) {
        tag = trackGain?.takeIf { it.gainDb.isFinite() && it.referenceLufs.isFinite() }
        meter.reset()
        trackSeconds = 0.0
    }

    /** Whether the meter has to listen to the incoming audio. */
    fun needsMeasurement(enabled: Boolean): Boolean = enabled && tag == null

    /** Moves [gainDb] on by [frames] frames of audio; [process]ing the meter is the caller's job. */
    fun advance(frames: Int, targetLufs: Double, enabled: Boolean) {
        if (sampleRate <= 0) return
        val dt = frames.toDouble() / sampleRate
        trackSeconds += dt
        if (!enabled) {
            gainDb = 0.0
            return
        }
        val tagged = tag
        if (tagged != null) {
            gainDb = clamp(tagged.toTarget(targetLufs))
            return
        }
        if (meter.gatedSeconds < MIN_MEASURED_S) return
        val integrated = meter.integratedLufs()
        if (!integrated.isFinite()) return
        val desired = clamp(targetLufs - integrated)
        val tau = if (trackSeconds < SETTLING_WINDOW_S) SETTLING_TIME_CONSTANT_S else SETTLED_TIME_CONSTANT_S
        gainDb += (desired - gainDb) * (1.0 - exp(-dt / tau))
    }

    private fun clamp(db: Double): Double = db.coerceIn(-MAX_CUT_DB, MAX_BOOST_DB)

    companion object {
        const val DEFAULT_TARGET_LUFS = -14.0

        /** A quiet recording is lifted at most this much: past it, it is the noise floor being turned up. */
        const val MAX_BOOST_DB = 9.0
        const val MAX_CUT_DB = 20.0

        private const val MIN_MEASURED_S = 1.0
        private const val SETTLING_WINDOW_S = 6.0
        private const val SETTLING_TIME_CONSTANT_S = 1.5
        private const val SETTLED_TIME_CONSTANT_S = 6.0
    }
}
