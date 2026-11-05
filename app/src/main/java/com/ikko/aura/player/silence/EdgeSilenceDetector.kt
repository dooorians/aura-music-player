package com.ikko.aura.player.silence

/**
 * What counts as silence at a track's edges, and how far the trimmer may go.
 *
 * @property thresholdDbfs RMS level under which a window is silent.
 * @property windowMs length of one measured window.
 * @property maxLeadTrimMs most of a track's opening that may be dropped. A track that is still
 *   silent past this is left alone from there: it is deliberate, or it is broken, and neither is
 *   fixed by eating more of it.
 * @property trailingConfirmMs how long the level has to stay under the line before the end of a
 *   track is called. Short enough that the transition comes within a second of the music
 *   stopping, long enough that a rest before the last chord is not mistaken for the end.
 * @property trailingWindowMs only a silence that starts this close to the end counts as trailing.
 *   A break in the middle of a track is part of the track.
 */
data class SilenceTrimConfig(
    val thresholdDbfs: Double = SilenceLevels.DEFAULT_THRESHOLD_DBFS,
    val windowMs: Int = 10,
    val maxLeadTrimMs: Long = 15_000L,
    val trailingConfirmMs: Long = 1_000L,
    val trailingWindowMs: Long = 30_000L,
) {
    init {
        require(windowMs > 0) { "windowMs must be positive" }
        require(maxLeadTrimMs >= 0) { "maxLeadTrimMs must not be negative" }
        require(trailingConfirmMs >= 0) { "trailingConfirmMs must not be negative" }
        require(trailingWindowMs >= 0) { "trailingWindowMs must not be negative" }
    }

    /** The threshold as a linear RMS, computed once rather than per window. */
    val thresholdLinear: Double = SilenceLevels.dbfsToLinear(thresholdDbfs)
}

/**
 * Follows one audio stream window by window and decides two things about its edges:
 *  - at the start, which windows are leading silence and can be dropped;
 *  - near the end, when the music has stopped for good, so the next track can start.
 *
 * Pure bookkeeping — no audio, no Android — so the audio processor that drives it stays thin and
 * every decision here can be tested with plain numbers.
 *
 * Positions are in the track's own time (microseconds from its start), counted on the input:
 * a dropped window still moves the position on, because it is still that much of the track.
 */
class EdgeSilenceDetector(val config: SilenceTrimConfig = SilenceTrimConfig()) {

    enum class Phase {
        /** Dropping silence at the start of the track. */
        LEADING,

        /** Music has started (or the start was not ours to trim): everything passes. */
        MUSIC,
    }

    var phase: Phase = Phase.MUSIC
        private set

    private var sampleRate = 0
    private var startUs = 0L
    private var durationUs = UNKNOWN
    private var framesSeen = 0L
    private var heardSound = false

    /** Frames dropped since [begin]. Reported to the sink so the playback clock skips them too. */
    var droppedFrames: Long = 0L
        private set

    /** Where the current silent run began, in track time, or [UNKNOWN] while there is sound. */
    private var runStartUs = UNKNOWN
    private var runFrames = 0L

    /**
     * Starts a stream.
     *
     * @param startPositionUs where in the track the first frame sits (non-zero after a seek).
     * @param durationUs the track's length, or [UNKNOWN]; without it no end is ever called.
     * @param trimLead whether this stream's opening may be trimmed: the caller decides (a local
     *   track starting from its beginning), the detector only carries it out.
     */
    fun begin(sampleRate: Int, startPositionUs: Long, durationUs: Long, trimLead: Boolean) {
        require(sampleRate > 0) { "sampleRate must be positive" }
        this.sampleRate = sampleRate
        this.startUs = startPositionUs.coerceAtLeast(0L)
        this.durationUs = durationUs
        framesSeen = 0L
        droppedFrames = 0L
        heardSound = false
        runStartUs = UNKNOWN
        runFrames = 0L
        phase = if (trimLead && config.maxLeadTrimMs > 0) Phase.LEADING else Phase.MUSIC
    }

    /** Ends leading trimming where it is: everything from here passes. */
    fun stopLeadTrim() {
        if (phase == Phase.LEADING) phase = Phase.MUSIC
    }

    /** Frames in one measuring window at the current rate. */
    fun windowFrames(): Int = (sampleRate.toLong() * config.windowMs / 1000L).toInt().coerceAtLeast(1)

    /** Track position of the next frame to arrive. */
    fun positionUs(): Long = startUs + framesToUs(framesSeen)

    /**
     * Whether the next window needs measuring at all. False through the body of a track, so the
     * processor can pass it straight through instead of adding up squares nobody will read.
     */
    fun needsLevel(): Boolean = phase == Phase.LEADING || inTailWindow(positionUs())

    /**
     * Feeds one window of [frames] frames at RMS [rmsLinear] (ignored when [needsLevel] said no;
     * pass anything). Returns true when the window should be dropped.
     */
    fun onWindow(frames: Int, rmsLinear: Double): Boolean {
        if (frames <= 0) return false
        val windowStartUs = positionUs()
        framesSeen += frames

        if (phase == Phase.LEADING) {
            val silent = rmsLinear < config.thresholdLinear
            if (silent && droppedFrames + frames <= usToFrames(config.maxLeadTrimMs * 1000L)) {
                droppedFrames += frames
                return true
            }
            phase = Phase.MUSIC
            if (!silent) heardSound = true
            return false
        }

        if (!inTailWindow(windowStartUs)) {
            // The body of the track: nothing measured, and any run is from before the tail.
            heardSound = true
            runStartUs = UNKNOWN
            runFrames = 0L
            return false
        }
        if (rmsLinear < config.thresholdLinear) {
            if (runStartUs == UNKNOWN) runStartUs = windowStartUs
            runFrames += frames
        } else {
            heardSound = true
            runStartUs = UNKNOWN
            runFrames = 0L
        }
        return false
    }

    /**
     * Where the trailing silence starts, once it is confirmed: music was heard, then the level
     * fell under the line inside the last [SilenceTrimConfig.trailingWindowMs] and has stayed
     * there for [SilenceTrimConfig.trailingConfirmMs]. Null otherwise.
     */
    fun trailingSilenceStartUs(): Long? {
        if (!heardSound || runStartUs == UNKNOWN) return null
        if (framesToUs(runFrames) < config.trailingConfirmMs * 1000L) return null
        return runStartUs
    }

    private fun inTailWindow(positionUs: Long): Boolean {
        if (durationUs == UNKNOWN || durationUs <= 0) return false
        // Never the first half of a track, however short it is: a 20-second sketch with a
        // pause in it is not over at second three.
        val tailStart = maxOf(durationUs - config.trailingWindowMs * 1000L, durationUs / 2)
        return positionUs >= tailStart
    }

    private fun framesToUs(frames: Long): Long = if (sampleRate == 0) 0L else frames * 1_000_000L / sampleRate
    private fun usToFrames(us: Long): Long = us * sampleRate / 1_000_000L

    companion object {
        /** No value: an unknown duration, or no silent run in progress. */
        const val UNKNOWN = Long.MIN_VALUE
    }
}
