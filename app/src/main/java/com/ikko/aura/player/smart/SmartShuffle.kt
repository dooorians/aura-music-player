package com.ikko.aura.player.smart

import com.ikko.aura.data.model.Track
import kotlin.math.abs
import kotlin.random.Random

/**
 * A shuffle ordered so that consecutive tracks are ones Automix can actually do something with.
 *
 * A plain shuffle hands the mixer a 190 BPM punk song to follow a 70 BPM ballad, and the best
 * [planTransition] can do with that pair is a plain fade — which is the one outcome Automix
 * exists to avoid. This picks the order first, using the same evidence the planner reads:
 * tempo (folded into one octave, the way a DJ counts 63 against 126), key (the planner's own
 * harmonic rules), and loudness, so the set does not fall off a cliff between two songs.
 *
 * **Still a shuffle.** Each step scores every remaining track against the one just placed and
 * then picks at random from the best few rather than taking the single best — so pressing the
 * button twice gives two different sets that are both sequenced the same way. Take the maximum
 * every time and the button becomes a sort with a random seed, which is not what was asked for.
 *
 * Tracks with no stored analysis are not held back to the end: a library is mostly unanalysed
 * until it has been played, and a button that ordered eight known tracks and then dumped four
 * hundred would be useless on the day it was installed. They score neutrally, so an analysed
 * track that genuinely matches wins, and everything else still shuffles.
 */
object SmartShuffle {

    /**
     * How many of the best-scoring candidates to choose between at each step. One would make
     * this deterministic; the whole library would make it a plain shuffle. Four is enough that
     * consecutive runs differ audibly while every pick is still among the good ones.
     */
    private const val CANDIDATE_POOL = 4

    /**
     * Scoring every remaining track against the current one is O(n²) over a library, which is
     * seconds of work at a few thousand tracks. Each step looks at a random sample instead —
     * large enough that the pool is still chosen from good matches, fixed so the cost is linear.
     */
    private const val SAMPLE_SIZE = 96

    /** What an unmeasured pairing scores: neither preferred nor penalised. */
    private const val NEUTRAL = 0.5

    fun order(
        tracks: List<Track>,
        analysisOf: (Track) -> TrackAnalysis?,
        random: Random = Random.Default,
    ): List<Track> {
        if (tracks.size <= 2) return tracks.shuffled(random)

        val remaining = tracks.toMutableList()
        val analyses = HashMap<String, TrackAnalysis?>(tracks.size)
        fun analysis(track: Track) = analyses.getOrPut(track.id) { analysisOf(track) }

        // Seeded on something measured where anything is: the first pairing is the one with the
        // most tracks to choose between, so it is the one worth spending the evidence on.
        val seedPool = remaining.filter { analysis(it)?.isUsable == true }.ifEmpty { remaining }
        val ordered = ArrayList<Track>(tracks.size)
        var current = seedPool.random(random)
        remaining.remove(current)
        ordered += current

        while (remaining.isNotEmpty()) {
            val sample = if (remaining.size <= SAMPLE_SIZE) {
                remaining
            } else {
                List(SAMPLE_SIZE) { remaining[random.nextInt(remaining.size)] }.distinct()
            }
            val pool = sample
                .sortedByDescending { compatibility(analysis(current), analysis(it)) }
                .take(CANDIDATE_POOL)
            val next = pool.random(random)
            remaining.remove(next)
            ordered += next
            current = next
        }
        return ordered
    }

    /**
     * How well [next] follows [current], 0..1. [NEUTRAL] whenever either side is unmeasured —
     * an honest "no opinion" rather than a guess dressed as one.
     */
    internal fun compatibility(current: TrackAnalysis?, next: TrackAnalysis?): Double {
        if (current == null || next == null || !current.isUsable || !next.isUsable) return NEUTRAL
        return TEMPO_WEIGHT * tempoScore(current.bpm, next.bpm) +
            KEY_WEIGHT * keyScore(current, next) +
            ENERGY_WEIGHT * energyScore(current, next)
    }

    /**
     * 1 when the two tempos match after octave folding, falling to 0 at [MAX_TEMPO_DRIFT].
     * Folding first is what lets a 70 BPM track follow a 140 BPM one without being scored as
     * the furthest thing in the library — at half time it is the same pulse.
     */
    private fun tempoScore(currentBpm: Double, nextBpm: Double): Double {
        val aligned = alignTempoOctave(currentBpm, nextBpm)
        if (currentBpm <= 0 || aligned <= 0) return NEUTRAL
        val drift = abs(aligned - currentBpm) / currentBpm
        return (1.0 - drift / MAX_TEMPO_DRIFT).coerceIn(0.0, 1.0)
    }

    /**
     * The planner's own harmonic rules, so the order agrees with what the mix will later decide
     * it can do. A key the analyzer was unsure of counts as no key rather than a wrong one.
     */
    private fun keyScore(current: TrackAnalysis, next: TrackAnalysis): Double {
        val currentKey = trustedKey(current)
        val nextKey = trustedKey(next)
        if (currentKey.isBlank() || nextKey.isBlank()) return NEUTRAL
        if (harmonicallyCompatible(currentKey, nextKey)) return 1.0
        val distance = keyDistance(currentKey, nextKey) ?: return NEUTRAL
        return (1.0 - distance / 7.0).coerceIn(0.0, 1.0)
    }

    /**
     * How level the set stays across the join: the outgoing track's ending loudness against the
     * incoming one's opening. Whole-track averages would call a quiet intro on a loud song a
     * mismatch; what is heard is the two ends that actually meet.
     */
    private fun energyScore(current: TrackAnalysis, next: TrackAnalysis): Double {
        val leaving = current.energyCurve.tailMean() ?: return NEUTRAL
        val arriving = next.energyCurve.headMean() ?: return NEUTRAL
        val louder = maxOf(leaving, arriving)
        if (louder <= 0.0) return NEUTRAL
        return (1.0 - abs(leaving - arriving) / louder).coerceIn(0.0, 1.0)
    }

    private fun List<EnergySample>.headMean(): Double? =
        take((size * EDGE_FRACTION).toInt().coerceAtLeast(1))
            .takeIf { isNotEmpty() }
            ?.map { it.energy }
            ?.average()

    private fun List<EnergySample>.tailMean(): Double? =
        takeLast((size * EDGE_FRACTION).toInt().coerceAtLeast(1))
            .takeIf { isNotEmpty() }
            ?.map { it.energy }
            ?.average()

    /** Beyond a quarter off, no amount of stretching makes two tempos the same pulse. */
    private const val MAX_TEMPO_DRIFT = 0.25

    /** The share of a track's energy curve that counts as its opening, or its ending. */
    private const val EDGE_FRACTION = 0.15

    private const val TEMPO_WEIGHT = 0.5
    private const val KEY_WEIGHT = 0.3
    private const val ENERGY_WEIGHT = 0.2
}
