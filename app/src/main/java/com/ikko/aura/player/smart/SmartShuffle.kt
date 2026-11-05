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

    private class Cue(
        val bpm: Double,
        val key: String,
        val headEnergy: Double?,
        val tailEnergy: Double?,
    )

    private fun cueOf(a: TrackAnalysis?): Cue? {
        if (a == null || !a.isUsable) return null
        return Cue(
            bpm = a.bpm,
            key = trustedKey(a),
            headEnergy = a.energyCurve.headMean(),
            tailEnergy = a.energyCurve.tailMean(),
        )
    }

    private fun compatibility(current: Cue?, next: Cue?): Double {
        if (current == null || next == null) return NEUTRAL
        val kScore = if (current.key.isBlank() || next.key.isBlank()) {
            NEUTRAL
        } else if (harmonicallyCompatible(current.key, next.key)) {
            1.0
        } else {
            val distance = keyDistance(current.key, next.key)
            if (distance == null) NEUTRAL else (1.0 - distance / 7.0).coerceIn(0.0, 1.0)
        }
        val eScore = run {
            val leaving = current.tailEnergy ?: return@run NEUTRAL
            val arriving = next.headEnergy ?: return@run NEUTRAL
            val louder = maxOf(leaving, arriving)
            if (louder <= 0.0) NEUTRAL else (1.0 - abs(leaving - arriving) / louder).coerceIn(0.0, 1.0)
        }
        return TEMPO_WEIGHT * tempoScore(current.bpm, next.bpm) +
            KEY_WEIGHT * kScore +
            ENERGY_WEIGHT * eScore
    }

    fun order(
        tracks: List<Track>,
        analysisOf: (Track) -> TrackAnalysis?,
        random: Random = Random.Default,
    ): List<Track> {
        if (tracks.size <= 2) return tracks.shuffled(random)

        val cues = HashMap<String, Cue>(tracks.size)
        for (t in tracks) {
            cueOf(analysisOf(t))?.let { cues[t.id] = it }
        }
        // Nothing analysed: still a shuffle that keeps one artist from following itself (the loop below does both).

        // Two songs of one artist back to back read as a skip in the shuffle, whatever their tempo: the last two artists placed cost something.
        val artistOf = HashMap<String, String>(tracks.size)
        for (t in tracks) artistOf[t.id] = com.ikko.aura.reco.ArtistNames.primaryKey(t.artist)
        val lastArtists = ArrayDeque<String>(2)
        fun spacing(t: Track): Double {
            val a = artistOf[t.id].orEmpty()
            if (a.isEmpty()) return 0.0
            return when (a) {
                lastArtists.lastOrNull() -> SAME_ARTIST_PENALTY
                lastArtists.firstOrNull() -> SAME_ARTIST_PENALTY / 2
                else -> 0.0
            }
        }
        fun placed(t: Track) {
            lastArtists.addLast(artistOf[t.id].orEmpty())
            while (lastArtists.size > 2) lastArtists.removeFirst()
        }

        val remaining = ArrayList<Track>(tracks)
        fun removeAtFast(index: Int): Track {
            val picked = remaining[index]
            val last = remaining.removeAt(remaining.lastIndex)
            if (index < remaining.size) remaining[index] = last
            return picked
        }

        // Seeded on something measured where anything is: the first pairing is the one with the
        // most tracks to choose between, so it is the one worth spending the evidence on.
        val seedIndices = remaining.indices.filter { cues.containsKey(remaining[it].id) }
        val firstIdx = if (seedIndices.isNotEmpty()) seedIndices.random(random) else random.nextInt(remaining.size)
        val ordered = ArrayList<Track>(tracks.size)
        var current = removeAtFast(firstIdx)
        ordered += current
        placed(current)

        val bestIndices = IntArray(CANDIDATE_POOL)
        val bestScores = DoubleArray(CANDIDATE_POOL)

        while (remaining.isNotEmpty()) {
            val currentCue = cues[current.id]
            if (currentCue == null || remaining.size == 1) {
                // Nothing measured to go by: a random song, but not of the artist just heard when a few draws can avoid it.
                var idx = random.nextInt(remaining.size)
                var tries = 1
                while (tries < RANDOM_TRIES && spacing(remaining[idx]) > 0.0) { idx = random.nextInt(remaining.size); tries++ }
                current = removeAtFast(idx)
                ordered += current
                placed(current)
                continue
            }
            val count = minOf(remaining.size, SAMPLE_SIZE)
            var poolSize = 0
            for (s in 0 until count) {
                val idx = if (remaining.size <= SAMPLE_SIZE) s else random.nextInt(remaining.size)
                val score = compatibility(currentCue, cues[remaining[idx].id]) - spacing(remaining[idx])
                if (poolSize < CANDIDATE_POOL) {
                    var pos = poolSize
                    while (pos > 0 && score > bestScores[pos - 1]) {
                        bestScores[pos] = bestScores[pos - 1]
                        bestIndices[pos] = bestIndices[pos - 1]
                        pos--
                    }
                    bestScores[pos] = score
                    bestIndices[pos] = idx
                    poolSize++
                } else if (score > bestScores[CANDIDATE_POOL - 1]) {
                    var pos = CANDIDATE_POOL - 1
                    while (pos > 0 && score > bestScores[pos - 1]) {
                        bestScores[pos] = bestScores[pos - 1]
                        bestIndices[pos] = bestIndices[pos - 1]
                        pos--
                    }
                    bestScores[pos] = score
                    bestIndices[pos] = idx
                }
            }
            val chosenIdx = bestIndices[random.nextInt(poolSize)]
            current = removeAtFast(chosenIdx)
            ordered += current
            placed(current)
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

    /** What following the artist just heard costs on the 0..1 compatibility scale (half of it for the one before): more than any tempo match earns. */
    private const val SAME_ARTIST_PENALTY = 0.6
    private const val RANDOM_TRIES = 8

    private const val TEMPO_WEIGHT = 0.5
    private const val KEY_WEIGHT = 0.3
    private const val ENERGY_WEIGHT = 0.2
}
