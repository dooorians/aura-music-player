package com.ikko.aura.reco

import java.text.Normalizer

/**
 * How calm, nocturnal or energetic a song looks from what is really known about it — the words of its title and album,
 * its length, a source's own tags — and nothing else. None of the sources gives a tempo, an energy or a genre, so this
 * is a fallback and says so: a song with no cue has no verdict ([Fit.evidence] is 0) and is never put in an ambience
 * playlist on a guess.
 */
object MoodModel {

    data class Fit(val chill: Double, val night: Double, val energy: Double, val evidence: Int) {
        fun of(mood: Mood) = when (mood) { Mood.CHILL -> chill; Mood.NIGHT -> night; Mood.ENERGY -> energy }
    }

    private val CALM = words("acoustic acoustique chill chillout calm calme soft mellow piano unplugged relax relaxing relaxed lofi lullaby berceuse sleep slowed tender gentle serene peaceful stripped bossa")
    private val NIGHTLY = words("night nuit midnight minuit nocturne nocturnal moonlight lune insomnia twilight 3am 2am ambient nighttime nightfall dusk")
    private val ENERGETIC = words("dance party workout hype edm club energy energie banger pump rave drop bass")
    private val AGAINST_CALM = words("remix club party workout hype bass banger rave drop edm nightcore sped fast scream")
    private val AGAINST_ENERGY = words("acoustic sleep piano ballad lullaby slowed slow calm relax lofi ambient unplugged soft")

    private fun words(list: String): Set<String> = list.split(' ').toSet()

    private fun tokens(vararg texts: String?): List<String> =
        texts.filterNotNull().flatMap { t ->
            Normalizer.normalize(t, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        }

    /** From a song's own words, [durationMs] and the [tags] its source gives. */
    fun fit(title: String, album: String, durationMs: Long, tags: List<String> = emptyList()): Fit {
        val t = tokens(title, album, *tags.toTypedArray())
        val tagWords = tokens(*tags.toTypedArray()).toSet()
        val instrumental = "instrumental" in tagWords || "instrumental" in t
        fun hits(set: Set<String>) = t.count { it in set }

        val calmHits = hits(CALM) + if (instrumental) 1 else 0
        val nightHits = hits(NIGHTLY) + if (instrumental) 1 else 0
        val energyHits = hits(ENERGETIC)
        val againstCalm = hits(AGAINST_CALM)
        val againstEnergy = hits(AGAINST_ENERGY) + if (instrumental) 1 else 0

        val minutes = if (durationMs > 0) durationMs / 60_000.0 else -1.0
        // A length is a weak cue: a prior that moves a score, never a reason to include on its own.
        val calmPrior = when { minutes < 0 -> 0.0; minutes in 2.8..5.5 -> 0.05; minutes < 2.0 -> -0.1; else -> 0.0 }
        val nightPrior = when { minutes < 0 -> 0.0; minutes >= 4.0 -> 0.08; minutes < 2.5 -> -0.1; else -> 0.0 }
        val energyPrior = when { minutes < 0 -> 0.0; minutes in 2.2..3.8 -> 0.06; minutes > 5.5 -> -0.08; else -> 0.0 }

        fun score(pos: Int, neg: Int, prior: Double): Double =
            (0.35 + 0.26 * minOf(pos, 2) - 0.3 * minOf(neg, 2) + prior).coerceIn(0.0, 1.0)

        val chill = score(calmHits, againstCalm, calmPrior)
        val night = score(nightHits, againstCalm.coerceAtMost(1), nightPrior)
        val energy = score(energyHits, againstEnergy, energyPrior)
        return Fit(chill, night, energy, evidence = calmHits + nightHits + energyHits + againstCalm + againstEnergy)
    }

    /** The lowest score a song needs to be put in an ambience playlist, once it has at least one cue for it. */
    const val THRESHOLD = 0.55

    /**
     * The score of [mood] for a candidate: its own words plus, as a lesser cue, being found by a query for that ambience.
     * Returns null — no verdict — when nothing in the song's metadata speaks for the mood.
     */
    fun verdict(c: Candidate, mood: Mood): Double? {
        val f = fit(c.title, c.album, c.durationMs, c.tags)
        val byQuery = c.provenance.any { it.kind == ProvKind.MOOD_QUERY && it.mood == mood }
        val cue = when (mood) {
            Mood.CHILL -> f.chill > 0.5
            Mood.NIGHT -> f.night > 0.5
            Mood.ENERGY -> f.energy > 0.5
        }
        if (!cue && !byQuery) return null
        // A song found only by an ambience query with nothing against it passes at the threshold: the source's own
        // answer to "chill" counts, but weakly.
        val base = f.of(mood)
        return when {
            byQuery && !cue -> if (base >= 0.35) THRESHOLD else base
            byQuery -> minOf(1.0, base + 0.1)
            else -> base
        }
    }
}
