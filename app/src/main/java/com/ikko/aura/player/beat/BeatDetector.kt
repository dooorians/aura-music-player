package com.ikko.aura.player.beat

import androidx.media3.common.C
import com.ikko.aura.player.smart.BeatGrid
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Listens to the audio being played and follows its tempo, for the screens that move with the music.
 *
 * It sits on the audio sink (see [BeatAudioSink]), so it hears whatever the player is decoding — any format, a stream
 * as much as a file — and costs a few multiplications a sample, where an offline analysis of the whole track takes
 * minutes of decoding. What it does:
 *
 *  - an onset curve at about 86 steps a second: how much louder the low end (kick, bass) and the high end (hats,
 *    snares) got since the step before, on a log scale;
 *  - every ~0.75 s, once there are ~4 s of it, the tempo: the lag at which that curve resembles itself most, leaning
 *    towards the tempi music is usually counted in (so 62 is read as 124, not the other way round);
 *  - the phase: of the beats that tempo allows, the row that lands on the most onsets;
 *  - the answer is a [BeatGrid] in the track's own time, so a seek does not undo it, and it keeps extrapolating until
 *    a better one replaces it. A new track starts again ([reset]).
 *
 * Called from the playback thread; [grid] is read from anywhere.
 */
class BeatDetector {
    @Volatile
    var grid: BeatGrid? = null
        private set

    // Where the audio being heard is, in the same clock as the beats (the renderer's), as of a moment on the wall clock.
    @Volatile private var audibleSeconds = Double.NaN
    @Volatile private var audibleWallSeconds = 0.0

    /** Called as audio goes through: the sink's own reckoning of what is being heard now, output delay included. */
    fun noteAudible(seconds: Double, wallSeconds: Double) {
        audibleSeconds = seconds
        audibleWallSeconds = wallSeconds
    }

    /**
     * How long ago the last beat was, at this very moment: null while the tempo is unknown or the audio has not moved
     * for a while. Beats and the audible position share one clock, so nothing about where the player thinks it is
     * (and no offset between the two) comes into it.
     */
    fun sinceBeat(wallSeconds: Double): Double? {
        val g = grid ?: return null
        val heard = audibleSeconds
        if (heard.isNaN() || wallSeconds - audibleWallSeconds > STALE_SECONDS) return null
        val now = heard + (wallSeconds - audibleWallSeconds)
        return ((now - g.firstBeat) % g.interval + g.interval) % g.interval
    }

    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var hopFrames = 512
    private var hopSeconds = 512.0 / 44100.0
    private var lowCoeff = 0.02f

    // Running state of the hop being filled.
    private var low = 0f
    private var previous = 0f
    private var accLow = 0.0
    private var accHigh = 0.0
    private var accCount = 0
    private var hopStartSeconds = 0.0
    private var previousLow = 0f
    private var previousHigh = 0f

    private val onset = FloatArray(RING)
    private val times = DoubleArray(RING)
    private var written = 0
    private var sinceEstimate = 0

    // The tempo being followed, in hops, and the one waiting to be confirmed.
    private var lag = 0.0
    private var pendingLag = 0.0
    private var phaseAnchor = Double.NaN

    fun configure(sampleRate: Int, channels: Int, encoding: Int) {
        if (sampleRate == this.sampleRate && channels == this.channels && encoding == this.encoding) return
        this.sampleRate = sampleRate
        this.channels = channels
        this.encoding = encoding
        hopFrames = (sampleRate * HOP_SECONDS).roundToInt().coerceAtLeast(64)
        hopSeconds = hopFrames.toDouble() / sampleRate
        // One-pole low-pass at about 150 Hz.
        lowCoeff = (1.0 - exp(-2.0 * Math.PI * LOW_CUTOFF_HZ / sampleRate)).toFloat()
        clearCurve()
    }

    /** A discontinuity of the stream (a seek): what was heard before says nothing of what comes next, but the grid stays. */
    fun flush() = clearCurve()

    /** A new track: nothing known about it yet. */
    fun reset() {
        clearCurve()
        lag = 0.0
        pendingLag = 0.0
        phaseAnchor = Double.NaN
        grid = null
    }

    private fun clearCurve() {
        low = 0f; previous = 0f
        accLow = 0.0; accHigh = 0.0; accCount = 0
        previousLow = 0f; previousHigh = 0f
        written = 0
        sinceEstimate = 0
    }

    /** [buffer] is decoded audio starting at [presentationUs] of the track; it is read, never consumed. */
    fun onBuffer(buffer: ByteBuffer, presentationUs: Long) {
        if (sampleRate <= 0 || channels <= 0) return
        val bytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            else -> return
        }
        val view = buffer.duplicate().order(buffer.order())
        val frames = view.remaining() / (bytes * channels)
        if (frames <= 0) return
        val start = view.position()
        val baseSeconds = presentationUs / 1_000_000.0
        val frameBytes = bytes * channels
        var lowState = low
        var prev = previous
        var aLow = accLow
        var aHigh = accHigh
        var count = accCount
        for (f in 0 until frames) {
            val at = start + f * frameBytes
            var x = sampleAt(view, at, bytes)
            if (channels > 1) x = (x + sampleAt(view, at + bytes, bytes)) * 0.5f
            lowState += lowCoeff * (x - lowState)
            val d = x - prev
            prev = x
            aLow += (lowState * lowState).toDouble()
            aHigh += (d * d).toDouble()
            if (count == 0) hopStartSeconds = baseSeconds + f.toDouble() / sampleRate
            count++
            if (count >= hopFrames) {
                finishHop(aLow / count, aHigh / count)
                aLow = 0.0; aHigh = 0.0; count = 0
            }
        }
        low = lowState
        previous = prev
        accLow = aLow
        accHigh = aHigh
        accCount = count
    }

    private fun sampleAt(b: ByteBuffer, index: Int, bytes: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> b.getShort(index) / 32768f
        C.ENCODING_PCM_FLOAT -> b.getFloat(index)
        C.ENCODING_PCM_24BIT -> {
            val v = (b.get(index).toInt() and 0xFF) or ((b.get(index + 1).toInt() and 0xFF) shl 8) or (b.get(index + 2).toInt() shl 16)
            v / 8388608f
        }
        else -> b.getInt(index) / 2147483648f
    }

    private fun finishHop(energyLow: Double, energyHigh: Double) {
        val el = ln(1.0 + 1000.0 * energyLow).toFloat()
        val eh = ln(1.0 + 150.0 * energyHigh).toFloat()
        val flux = max(0f, el - previousLow) + 0.6f * max(0f, eh - previousHigh)
        previousLow = el
        previousHigh = eh
        val slot = written % RING
        onset[slot] = flux
        times[slot] = hopStartSeconds + hopSeconds / 2.0
        written++
        sinceEstimate++
        if (written >= MIN_HOPS && sinceEstimate >= ESTIMATE_EVERY) {
            sinceEstimate = 0
            estimate()
        }
    }

    private fun estimate() {
        val n = min(written, RING)
        val o = FloatArray(n)
        val t = DoubleArray(n)
        val first = written - n
        for (i in 0 until n) {
            val slot = (first + i) % RING
            o[i] = onset[slot]
            t[i] = times[slot]
        }
        // Mean removed, so a steady loudness is not read as a beat, and spikes tamed so one loud hit does not rule.
        var mean = 0.0
        for (v in o) mean += v
        mean /= n
        var variance = 0.0
        for (v in o) variance += (v - mean) * (v - mean)
        val sigma = sqrt(variance / n).toFloat()
        if (sigma < MIN_SIGMA) return          // silence, or nothing moving
        val cap = 4f * sigma
        for (i in 0 until n) o[i] = min(o[i] - mean.toFloat(), cap)

        val minLag = max(2, (60.0 / MAX_BPM / hopSeconds).toInt())
        val maxLag = min(n / 3, ceil(60.0 / MIN_BPM / hopSeconds).toInt())
        if (maxLag <= minLag + 2) return
        val top = min(n - 1, 2 * maxLag + 2)
        val acf = FloatArray(top + 2)
        for (l in minLag - 1..top) {
            var sum = 0f
            for (i in l until n) sum += o[i] * o[i - l]
            acf[l] = sum / (n - l)
        }
        var bestScore = -1f
        var bestLag = 0
        var total = 0f
        var counted = 0
        for (l in minLag..maxLag) {
            val bpm = 60.0 / (l * hopSeconds)
            val octaves = ln(bpm / PRIOR_BPM) / ln(2.0)
            val prior = exp(-0.5 * (octaves / PRIOR_SPREAD) * (octaves / PRIOR_SPREAD)).toFloat()
            // A beat period shows up again at its multiples: add them in, so the tempo that every one of them agrees on wins.
            val support = acf[l] + 0.5f * (if (2 * l <= top) acf[2 * l] else 0f)
            val score = support * prior
            total += max(score, 0f)
            counted++
            if (score > bestScore) { bestScore = score; bestLag = l }
        }
        if (bestLag == 0 || bestScore <= 0f) return
        val average = total / counted
        if (bestScore < CONFIDENCE * average) return
        // A fast tempo is usually the half-time pulse counted twice (a track at 81 shows its sixteenths at 162): if the
        // slower pulse is nearly as strong, that is the beat, as a foot would tap it.
        while (60.0 / (bestLag * hopSeconds) > FAST_BPM && 2 * bestLag <= maxLag && acf[2 * bestLag] >= HALVE_RATIO * acf[bestLag]) {
            bestLag *= 2
        }

        // The peak between the whole lags, from the three around it.
        val a = acf[bestLag - 1]; val b = acf[bestLag]; val c = acf[bestLag + 1]
        val denom = a - 2f * b + c
        val refined = bestLag + if (abs(denom) > 1e-9f) (0.5f * (a - c) / denom).coerceIn(-0.5f, 0.5f) else 0f

        // A tempo is believed once it has been heard twice in a row; after that it only drifts.
        if (lag == 0.0) {
            if (pendingLag != 0.0 && abs(refined - pendingLag) / pendingLag < LAG_TOLERANCE) {
                lag = (refined + pendingLag) / 2.0
            } else {
                pendingLag = refined.toDouble()
                return
            }
        } else if (abs(refined - lag) / lag < LAG_TOLERANCE) {
            lag = lag * 0.7 + refined * 0.3
            pendingLag = 0.0
        } else if (abs(refined * 2 - lag) / lag < LAG_TOLERANCE || abs(refined - lag * 2) / (lag * 2) < LAG_TOLERANCE) {
            // The same pulse counted twice or in halves: the beat being followed is as good as it was, and swapping
            // between the two every few seconds is what the eye would notice.
            pendingLag = 0.0
        } else {
            if (pendingLag != 0.0 && abs(refined - pendingLag) / pendingLag < LAG_TOLERANCE) {
                lag = refined.toDouble(); pendingLag = 0.0
                phaseAnchor = Double.NaN
            } else {
                pendingLag = refined.toDouble()
            }
        }

        // The phase: of the rows the tempo allows, the one whose beats collect the most onset.
        val period = lag
        val rows = ceil(period).toInt().coerceAtLeast(1)
        var bestRow = 0
        var bestSum = -Float.MAX_VALUE
        for (row in 0 until rows) {
            var sum = 0f
            var pos = row.toDouble()
            while (pos < n) {
                val i = pos.roundToInt().coerceIn(0, n - 1)
                sum += 0.5f * o[i] + 0.25f * (o[max(i - 1, 0)] + o[min(i + 1, n - 1)])
                pos += period
            }
            if (sum > bestSum) { bestSum = sum; bestRow = row }
        }
        // The last beat that fits in what has been heard, and when it was.
        var pos = bestRow.toDouble()
        while (pos + period <= n - 1) pos += period
        val lastIndex = pos.roundToInt().coerceIn(0, n - 1)
        val measured = t[lastIndex]
        val interval = period * hopSeconds
        phaseAnchor = if (phaseAnchor.isNaN()) measured else {
            // Pulled gently towards each new measurement: a beat that wanders a few milliseconds is noise, not music.
            var error = (measured - phaseAnchor) % interval
            if (error > interval / 2) error -= interval
            if (error < -interval / 2) error += interval
            phaseAnchor + error * PHASE_PULL
        }
        grid = BeatGrid(phaseAnchor, interval)
    }

    private companion object {
        const val HOP_SECONDS = 0.0116
        const val LOW_CUTOFF_HZ = 150.0
        const val RING = 1024
        const val MIN_HOPS = 340
        const val ESTIMATE_EVERY = 64
        const val MIN_BPM = 65.0
        const val MAX_BPM = 190.0
        const val PRIOR_BPM = 125.0
        const val PRIOR_SPREAD = 0.75
        const val CONFIDENCE = 1.8f
        const val FAST_BPM = 150.0
        const val HALVE_RATIO = 0.6f
        const val LAG_TOLERANCE = 0.04
        const val PHASE_PULL = 0.3
        const val MIN_SIGMA = 0.02f
        const val STALE_SECONDS = 1.0
    }
}
