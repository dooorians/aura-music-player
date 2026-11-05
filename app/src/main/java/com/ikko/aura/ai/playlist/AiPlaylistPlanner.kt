package com.ikko.aura.ai.playlist

import com.ikko.aura.reco.ArcShape
import com.ikko.aura.reco.GenreNormalizer
import com.ikko.aura.reco.PlaylistFlow
import com.ikko.aura.reco.PlaylistSequencer
import com.ikko.aura.reco.RecoKind
import com.ikko.aura.reco.Tempo
import kotlin.math.roundToInt

/** The parameters of [AiPlaylistDao.candidates], as they are bound. */
data class TrackQuery(
    val anyTempo: Boolean,
    val lo: Double = 0.0,
    val hi: Double = 0.0,
    val halfLo: Double = 0.0,
    val halfHi: Double = 0.0,
    val doubleLo: Double = 0.0,
    val doubleHi: Double = 0.0,
    val limit: Int = AiPlaylistPlanner.QUERY_LIMIT,
) {
    /** Whether a known tempo passes the query, exactly as the SQL decides it (for tests and for songs read elsewhere). */
    fun admits(bpm: Double): Boolean =
        anyTempo || bpm <= 0.0 || bpm in lo..hi || bpm in halfLo..halfHi || bpm in doubleLo..doubleHi

    companion object {
        val ANY = TrackQuery(anyTempo = true)
    }
}

/** How far the constraints were loosened to find enough songs; the sheet says so when it is not [NONE]. */
enum class Relaxation { NONE, WIDER_TEMPO, ANY_GENRE, ANY_TEMPO }

/** Everything the generator does with an intent: what to ask the database, how to weigh and pick, how to order. */
data class AiPlaylistPlan(
    val intent: PlaylistIntent,
    val query: TrackQuery,
    val targetBpm: Double?,
    val tolerance: Double?,
    val energyTarget: Double?,
    /** Genres asked for, normalised; a song must match one of them unless [relaxation] is [Relaxation.ANY_GENRE] or beyond. */
    val include: List<String>,
    /** Genres refused, normalised; never loosened. */
    val exclude: List<String>,
    val targetDurationMs: Long,
    val arc: ArcShape,
    val weights: PlaylistSequencer.Weights,
    val relaxation: Relaxation = Relaxation.NONE,
) {
    val strictGenres: Boolean get() = include.isNotEmpty() && relaxation < Relaxation.ANY_GENRE
}

/**
 * From a [PlaylistIntent] to an [AiPlaylistPlan]: every default the intent leaves open is decided here, and only here,
 * so the conversion can be tested as one function.
 */
object AiPlaylistPlanner {

    /** A playlist asked for without a length: about three quarters of an hour, the length of an album side or two. */
    const val DEFAULT_MINUTES = 45
    /** The tolerance of a tempo asked for without one: a DJ's pitch fader (±6 %), never less than ±5 BPM. */
    const val DEFAULT_TOLERANCE_RATIO = 0.06
    const val MIN_TOLERANCE = 5.0
    /** How many rows the database is asked for: more than any playlist needs, few enough to weigh at once. */
    const val QUERY_LIMIT = 4000

    /** The energy (0..1, see [AiPlaylistGenerator.energyOf]) each level aims at. */
    fun energyTarget(level: EnergyLevel?): Double? = when (level) {
        EnergyLevel.LOW -> 0.2
        EnergyLevel.MEDIUM -> 0.5
        EnergyLevel.HIGH -> 0.85
        null -> null
    }

    fun plan(intent: PlaylistIntent, relaxation: Relaxation = Relaxation.NONE): AiPlaylistPlan {
        val bpm = Tempo.sanitize(intent.targetBpm?.toDouble())
        val baseTolerance = bpm?.let { b ->
            intent.bpmTolerance?.toDouble()?.coerceAtLeast(1.0) ?: maxOf(MIN_TOLERANCE, (b * DEFAULT_TOLERANCE_RATIO).roundToInt().toDouble())
        }
        val tolerance = if (baseTolerance != null && relaxation >= Relaxation.WIDER_TEMPO) baseTolerance * 2 else baseTolerance
        val query = if (bpm == null || tolerance == null || relaxation >= Relaxation.ANY_TEMPO) {
            TrackQuery.ANY
        } else {
            val lo = bpm - tolerance
            val hi = bpm + tolerance
            TrackQuery(anyTempo = false, lo = lo, hi = hi, halfLo = lo / 2, halfHi = hi / 2, doubleLo = lo * 2, doubleHi = hi * 2)
        }
        val exclude = intent.genresExcluded.map(::genreKey).filter { it.isNotEmpty() }.distinct()
        val include = intent.genresIncluded.map(::genreKey).filter { it.isNotEmpty() && it !in exclude }.distinct()
        val arc = arcOf(intent)
        return AiPlaylistPlan(
            intent = intent,
            query = query,
            targetBpm = bpm,
            tolerance = tolerance,
            energyTarget = energyTarget(intent.energy),
            include = include,
            exclude = exclude,
            targetDurationMs = (intent.targetDurationMinutes ?: DEFAULT_MINUTES).coerceIn(1, PlaylistIntentJson.MAX_MINUTES) * 60_000L,
            arc = arc,
            weights = weightsOf(arc),
            relaxation = relaxation,
        )
    }

    /**
     * The shape of the energy along the playlist. The atmosphere decides when it is one the app knows; else the
     * intensity: high builds (a workout), low stays level, medium (and nothing said) breathes.
     */
    fun arcOf(intent: PlaylistIntent): ArcShape {
        val mood = intent.mood.orEmpty()
        return when {
            listOf("workout", "party", "sport", "fête", "fete", "running").any { it in mood } -> ArcShape.BUILD
            listOf("sleep", "night", "nuit", "dormir").any { it in mood } && "drive" !in mood -> ArcShape.WIND_DOWN
            listOf("focus", "chill", "calm", "calme", "study", "lecture", "zen").any { it in mood } -> ArcShape.PLATEAU
            intent.energy == EnergyLevel.HIGH -> ArcShape.BUILD
            intent.energy == EnergyLevel.LOW -> ArcShape.PLATEAU
            else -> ArcShape.WAVE
        }
    }

    /** The sequencer's weights of the generated playlist with the same arc (see [PlaylistFlow.weightsOf]). */
    fun weightsOf(arc: ArcShape): PlaylistSequencer.Weights = PlaylistFlow.weightsOf(
        when (arc) {
            ArcShape.BUILD -> RecoKind.ENERGY
            ArcShape.WIND_DOWN -> RecoKind.NIGHT
            ArcShape.PLATEAU -> RecoKind.CHILL
            ArcShape.WAVE -> RecoKind.STYLE
        },
    )

    /** The next, looser plan, or null when nothing is left to loosen. Refused genres are never loosened. */
    fun relax(plan: AiPlaylistPlan): AiPlaylistPlan? {
        val next = Relaxation.entries.drop(plan.relaxation.ordinal + 1).firstOrNull { level ->
            when (level) {
                Relaxation.WIDER_TEMPO, Relaxation.ANY_TEMPO -> plan.targetBpm != null
                Relaxation.ANY_GENRE -> plan.include.isNotEmpty()
                Relaxation.NONE -> false
            }
        } ?: return null
        return plan(plan.intent, next)
    }

    // --- genres ------------------------------------------------------------------------------------------------------

    private val NON_WORD = Regex("[^a-z0-9&]+")

    /** A genre as compared: the app's own name for it ([GenreNormalizer]), lower case, accents and punctuation gone. */
    fun genreKey(raw: String): String {
        val name = GenreNormalizer.normalize(raw) ?: return ""
        return java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace(NON_WORD, " ").trim()
    }

    /**
     * Whether a song's genre [have] answers a genre asked for [want] (both [genreKey]s): the same genre, a more precise
     * one ("rap" is answered by "french rap", "house" by "deep house"), or, when [want] names a whole family
     * ("electronic", "rock", "jazz"), any genre of it. The reverse is not true: asked for French rap, rap is not enough.
     */
    fun genreMatches(want: String, have: String): Boolean {
        if (want.isEmpty() || have.isEmpty()) return false
        if (want == have) return true
        val wantWords = want.split(' ')
        val haveWords = have.split(' ').toSet()
        if (haveWords.containsAll(wantWords)) return true
        val wantFamily = GenreNormalizer.family(want)
        return wantFamily != null && familyName(wantFamily) == want && GenreNormalizer.family(have) == wantFamily
    }

    private fun familyName(family: String): String = genreKey(family)
}
