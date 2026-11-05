package com.ikko.aura.reco

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Tempo arithmetic for the recommendations: what counts as a tempo at all, and how well one tempo follows another.
 *
 * Two rules carry everything here:
 *  - **An unknown tempo is no evidence.** Null, zero, negative, NaN, infinite or absurd (a tag that says 999) all read as "not known", and a song
 *    whose tempo is not known is never scored as a bad match: callers get null and fall back to [NEUTRAL].
 *  - **Half time and double time are the same pulse.** A 70 BPM ballad and a 140 BPM track share every other beat; a catalogue often lists one
 *    octave for the other. Tempos are compared after the best alignment among ×1, ×2 and ×½ (and, more weakly, the 3:2 of a shuffle feel).
 *
 * Pure, so it is checked on the JVM.
 */
object Tempo {
    /** Slower than this is not a pulse a listener follows; faster is a tagging error or a measure of something else. */
    const val MIN_BPM = 40.0
    const val MAX_BPM = 250.0

    /** What a transition scores when either tempo is unknown: no opinion, neither preferred nor penalised. */
    const val NEUTRAL = 0.5

    /**
     * How far the aligned tempos may drift before the match fades: the score is exp(-(drift / σ)²), so a drift of σ (7 %) keeps 37 %. A DJ's pitch
     * fader rides ±6–8 % without the change being heard; beyond about a fifth, no stretching makes them one pulse.
     */
    const val DRIFT_SIGMA = 0.07

    /** What a half- or double-time match is worth against a match in the same octave: the same pulse, a slightly different feel. */
    const val OCTAVE_FACTOR = 0.92

    /** What a 3:2 relation (a straight beat against a shuffle) is worth: related, rarely seamless. */
    const val TRIPLET_FACTOR = 0.55

    enum class Relation { SAME, OCTAVE, TRIPLET }

    /** How two tempos line up: the [relation] that aligns them best and the relative [drift] that remains (0 for a perfect match). */
    data class Match(val relation: Relation, val drift: Double)

    /** [bpm] when it is a tempo a song can have, else null. */
    fun sanitize(bpm: Double?): Double? = bpm?.takeIf { it.isFinite() && it >= MIN_BPM && it <= MAX_BPM }

    /** Whether [bpm] is a usable tempo. */
    fun isKnown(bpm: Double?): Boolean = sanitize(bpm) != null

    /**
     * [bpm] folded into one octave, [low] ≤ result < 2·[low]: 70, 140 and 280 all land on the same value. What tempo buckets and session profiles
     * should compare, so a double-time reading does not split one tempo into two. NaN for an unknown tempo.
     */
    fun fold(bpm: Double, low: Double = 80.0): Double {
        if (!bpm.isFinite() || bpm <= 0.0 || low <= 0.0) return Double.NaN
        var x = bpm
        while (x >= 2 * low) x /= 2
        while (x < low) x *= 2
        return x
    }

    private val RATIOS = doubleArrayOf(1.0, 2.0, 0.5, 1.5, 2.0 / 3.0)
    private val RELATIONS = arrayOf(Relation.SAME, Relation.OCTAVE, Relation.OCTAVE, Relation.TRIPLET, Relation.TRIPLET)

    /** The best alignment of [next] onto [current], or null when either tempo is unknown. Symmetric in its drift. */
    fun match(current: Double?, next: Double?): Match? {
        val a = sanitize(current) ?: return null
        val b = sanitize(next) ?: return null
        var best: Match? = null
        var bestScore = -1.0
        for (i in RATIOS.indices) {
            // Drift measured on a log scale: 120→126 and 126→120 are the same distance.
            val drift = abs(ln(b * RATIOS[i] / a))
            val score = factorOf(RELATIONS[i]) * gaussian(drift)
            if (score > bestScore) { bestScore = score; best = Match(RELATIONS[i], drift) }
        }
        return best
    }

    /**
     * 0..1: how smoothly a song at [next] BPM follows one at [current] BPM. 1 for the same tempo, about 0.92 for an exact half or double time,
     * falling with the drift. Null when either is unknown — callers use [NEUTRAL], never 0.
     */
    fun affinity(current: Double?, next: Double?): Double? {
        val m = match(current, next) ?: return null
        return factorOf(m.relation) * gaussian(m.drift)
    }

    /** [affinity], or [NEUTRAL] when it cannot be told. */
    fun affinityOrNeutral(current: Double?, next: Double?): Double = affinity(current, next) ?: NEUTRAL

    private fun factorOf(r: Relation) = when (r) {
        Relation.SAME -> 1.0
        Relation.OCTAVE -> OCTAVE_FACTOR
        Relation.TRIPLET -> TRIPLET_FACTOR
    }

    private fun gaussian(drift: Double): Double {
        val z = drift / DRIFT_SIGMA
        return exp(-z * z)
    }
}
