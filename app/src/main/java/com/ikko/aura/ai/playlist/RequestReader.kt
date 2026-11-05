package com.ikko.aura.ai.playlist

import java.text.Normalizer
import kotlin.math.roundToInt

/**
 * What a request says in so many words, read without any model: tempos ("135+ BPM", "autour de 90 bpm", "120-128 bpm"),
 * lengths ("1h30", "45 min", "une heure et demie"), genres asked for or refused ("sans rap ni metal", "no trap"), and
 * the words that set an intensity or an atmosphere ("workout", "calme", "night drive").
 *
 * Two uses. With the model, these are the facts it may not override: a 0.5B model reads "2 heures" as 2 minutes often
 * enough, and a number written by the listener is never ambiguous ([IntentMerge]). Without the model (not downloaded,
 * or the native library missing), it is the whole interpretation, and the assistant still works, more literally.
 */
object RequestReader {

    fun read(request: String): PlaylistIntent {
        val text = normalize(request)
        if (text.isBlank()) return PlaylistIntent.EMPTY
        val (bpm, tolerance) = tempo(text)
        val (included, excluded) = genres(text)
        return PlaylistIntent(
            targetBpm = bpm,
            bpmTolerance = tolerance,
            energy = energy(text),
            genresIncluded = included.filterNot { it in excluded },
            genresExcluded = excluded,
            targetDurationMinutes = minutes(text),
            mood = mood(text),
        )
    }

    /** Whether the request gives any ground for a tempo: a number, or a word that implies one. */
    fun impliesTempo(request: String): Boolean {
        val text = normalize(request)
        return DIGIT.containsMatchIn(text) || TEMPO_WORDS.any { containsWord(text, it) }
    }

    /** Whether the request gives any ground for a length. */
    fun impliesDuration(request: String): Boolean {
        val text = normalize(request)
        return DIGIT.containsMatchIn(text) || DURATION_WORDS.any { containsWord(text, it) }
    }

    // --- tempo -------------------------------------------------------------------------------------------------------

    private val RANGE = Regex("""(\d{2,3})\s*(?:-|–|a|to|et)\s*(\d{2,3})\s*bpm""")
    private val AT_LEAST = Regex("""(\d{2,3})\s*\+\s*bpm|(\d{2,3})\s*bpm\s*\+|(?:plus de|au moins|minimum|min|over|above|at least|more than|>)\s*(\d{2,3})\s*bpm""")
    private val AT_MOST = Regex("""(?:moins de|au plus|maximum|max|under|below|less than|<)\s*(\d{2,3})\s*bpm""")
    private val EXACT = Regex("""(\d{2,3})\s*bpm|bpm\s*(\d{2,3})""")

    /** How far a bound ("135+") reaches: the target sits this far inside it, with the same tolerance. */
    private const val BOUND_SPAN = 15

    private fun tempo(text: String): Pair<Int?, Int?> {
        fun ok(v: Int) = v in PlaylistIntentJson.MIN_BPM..PlaylistIntentJson.MAX_BPM
        RANGE.find(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            if (ok(lo) && ok(hi)) return ((lo + hi) / 2.0).roundToInt() to maxOf(3, ((hi - lo) / 2.0).roundToInt())
        }
        AT_LEAST.find(text)?.let { m ->
            val v = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toInt()
            if (v != null && ok(v)) return minOf(v + BOUND_SPAN, PlaylistIntentJson.MAX_BPM) to BOUND_SPAN
        }
        AT_MOST.find(text)?.let { m ->
            val v = m.groupValues[1].toInt()
            if (ok(v)) return maxOf(v - BOUND_SPAN, PlaylistIntentJson.MIN_BPM) to BOUND_SPAN
        }
        EXACT.find(text)?.let { m ->
            val v = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toInt()
            if (v != null && ok(v)) return v to null
        }
        return null to null
    }

    // --- length ------------------------------------------------------------------------------------------------------

    private val H_MIN = Regex("""\b(\d{1,2})\s*h\s*(\d{1,2})\b""")
    private val HOURS = Regex("""\b(\d{1,2})\s*(?:h|heures?|hours?|hrs?)\b(\s*(?:et demie?|and a half))?""")
    private val MINUTES = Regex("""\b(\d{1,3})\s*(?:min|mins|minutes?|mn)\b""")
    private val WORD_HOURS = listOf(
        "une heure et demie" to 90, "an hour and a half" to 90, "one hour and a half" to 90,
        "deux heures" to 120, "two hours" to 120, "trois heures" to 180, "three hours" to 180,
        "une demi-heure" to 30, "demi-heure" to 30, "une demi heure" to 30, "une demie heure" to 30, "demi heure" to 30, "half an hour" to 30, "half hour" to 30,
        "une heure" to 60, "an hour" to 60, "one hour" to 60, "quart d heure" to 15,
    )

    private fun minutes(text: String): Int? {
        val found = H_MIN.find(text)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
            ?: HOURS.find(text)?.let { it.groupValues[1].toInt() * 60 + if (it.groupValues[2].isNotBlank()) 30 else 0 }
            ?: MINUTES.find(text)?.groupValues?.get(1)?.toInt()
            ?: WORD_HOURS.firstOrNull { (phrase, _) -> containsWord(text, phrase) }?.second
        return found?.takeIf { it in 1..PlaylistIntentJson.MAX_MINUTES }
    }

    // --- genres ------------------------------------------------------------------------------------------------------

    /**
     * Surface forms (lower case, without accents) and the genre they name, as the model is asked to write them. Longer
     * forms first: "rap francais" is French rap before it is rap, "deep house" deep house before house.
     */
    private val LEXICON: List<Pair<String, String>> = listOf(
        "musique classique" to "classical", "chanson francaise" to "chanson française", "variete francaise" to "chanson française",
        "rap francais" to "french rap", "rap fr" to "french rap", "french rap" to "french rap",
        "musique de film" to "soundtrack", "bande originale" to "soundtrack", "drum and bass" to "drum & bass", "drum & bass" to "drum & bass",
        "deep house" to "deep house", "hard rock" to "hard rock", "indie rock" to "indie rock", "lo fi" to "lo-fi", "lo-fi" to "lo-fi",
        "hip hop" to "hip-hop", "hip-hop" to "hip-hop", "k-pop" to "k-pop", "r&b" to "r&b", "r'n'b" to "r&b",
        "hiphop" to "hip-hop", "rap" to "hip-hop", "trap" to "trap", "drill" to "drill", "lofi" to "lo-fi", "kpop" to "k-pop", "rnb" to "r&b",
        "metal" to "metal", "rock" to "rock", "punk" to "punk", "grunge" to "grunge", "indie" to "indie", "pop" to "pop",
        "jazz" to "jazz", "blues" to "blues", "soul" to "soul", "funk" to "funk", "disco" to "disco", "gospel" to "gospel",
        "house" to "house", "techno" to "techno", "trance" to "trance", "dubstep" to "dubstep", "dnb" to "drum & bass",
        "electro" to "electronic", "electronique" to "electronic", "electronic" to "electronic", "edm" to "electronic",
        "synthwave" to "synthwave", "ambient" to "ambient", "classique" to "classical", "classical" to "classical",
        "reggaeton" to "reggaeton", "reggae" to "reggae", "latino" to "latin", "latin" to "latin", "salsa" to "salsa",
        "afrobeats" to "afrobeats", "afrobeat" to "afrobeats", "afro" to "afrobeats", "country" to "country", "folk" to "folk",
        "acoustique" to "acoustic", "acoustic" to "acoustic", "chanson" to "chanson française", "variete" to "chanson française",
        "soundtrack" to "soundtrack", "ost" to "soundtrack", "opera" to "classical", "bossa nova" to "bossa nova",
    )

    /** Words after which a genre is refused, until the clause ends. */
    private val NEGATIONS = listOf("sans", "pas de", "pas d", "pas du", "pas le", "pas la", "pas les", "pas trop", "ni", "aucun", "aucune", "zero", "no", "not", "without", "except", "sauf", "hors", "excluding", "evite", "avoid")
    private val CLAUSE_BREAK = Regex("""[,.;!?]|\b(?:mais|but|avec|with|plutot|rather|juste|only|surtout)\b""")

    private fun genres(text: String): Pair<List<String>, List<String>> {
        val taken = BooleanArray(text.length)
        val included = ArrayList<Pair<Int, String>>()
        val excluded = ArrayList<Pair<Int, String>>()
        for ((form, genre) in LEXICON) {
            for (m in wordRegex(form).findAll(text)) {
                val r = m.range
                if (r.any { taken[it] }) continue
                r.forEach { taken[it] = true }
                (if (negated(text, r.first)) excluded else included) += r.first to genre
            }
        }
        fun ordered(l: List<Pair<Int, String>>) = l.sortedBy { it.first }.map { it.second }.distinct().take(PlaylistIntentJson.MAX_GENRES)
        return ordered(included) to ordered(excluded)
    }

    /** Whether a negation governs the position [at]: one occurs earlier in the same clause. */
    private fun negated(text: String, at: Int): Boolean {
        val before = text.substring(0, at)
        val clauseStart = CLAUSE_BREAK.findAll(before).lastOrNull()?.range?.last?.plus(1) ?: 0
        val clause = before.substring(clauseStart)
        return NEGATIONS.any { containsWord(clause, it) }
    }

    // --- intensity and atmosphere ------------------------------------------------------------------------------------

    private val HIGH_WORDS = listOf(
        "workout", "sport", "gym", "running", "course", "cardio", "energique", "energetic", "intense", "hype", "party", "fete",
        "bouge", "qui bouge", "danser", "banger", "motivation", "pump", "rave", "muscu", "musculation", "puissant", "upbeat",
    )
    private val LOW_WORDS = listOf(
        "calme", "calm", "chill", "relax", "relaxant", "detente", "doux", "douce", "soft", "sleep", "dormir", "sommeil", "focus",
        "concentration", "etude", "study", "studying", "lire", "lecture", "reading", "mellow", "zen", "meditation", "acoustique",
        "acoustic", "tranquille", "apaisant", "apaisante", "lent", "lente", "slow", "cosy", "cozy", "pluie", "rain",
    )
    private val MEDIUM_WORDS = listOf("drive", "route", "voyage", "road trip", "roadtrip", "groove", "cuisine", "cooking", "balade", "walk", "marche")

    private fun energy(text: String): EnergyLevel? {
        val high = HIGH_WORDS.count { containsWord(text, it) }
        val low = LOW_WORDS.count { containsWord(text, it) }
        return when {
            high > low -> EnergyLevel.HIGH
            low > high -> EnergyLevel.LOW
            high == 0 && MEDIUM_WORDS.any { containsWord(text, it) } -> EnergyLevel.MEDIUM
            else -> null
        }
    }

    /** The atmosphere, as one of a few names the planner knows (see [AiPlaylistPlanner.arcOf]); the first that fits. */
    private val MOODS = listOf(
        "night drive" to listOf("night drive", "conduite de nuit", "rouler la nuit", "drive de nuit"),
        "workout" to listOf("workout", "sport", "gym", "running", "course", "cardio", "muscu", "musculation"),
        "focus" to listOf("focus", "concentration", "etude", "study", "studying", "travail", "work", "lire", "lecture", "reading"),
        "sleep" to listOf("sleep", "dormir", "sommeil", "endormir"),
        "party" to listOf("party", "fete", "soiree", "danser", "rave"),
        "road trip" to listOf("road trip", "roadtrip", "voyage", "route"),
        "chill" to listOf("chill", "detente", "relax", "relaxant", "cosy", "cozy"),
        "calm" to listOf("calme", "calm", "tranquille", "zen", "apaisant", "apaisante", "meditation"),
        "romantic" to listOf("romantique", "romantic", "amour", "love"),
        "melancholic" to listOf("triste", "sad", "melancolique", "melancholic", "melancolie"),
        "happy" to listOf("joyeux", "joyeuse", "happy", "heureux", "feel good", "bonne humeur", "ensoleille", "sunny"),
        "night" to listOf("nuit", "night", "nocturne", "minuit"),
    )

    private fun mood(text: String): String? = MOODS.firstOrNull { (_, cues) -> cues.any { containsWord(text, it) } }?.first

    private val TEMPO_WORDS = listOf("bpm", "tempo", "rapide", "fast", "lent", "slow", "workout", "sport", "running", "course", "cardio", "danser", "dance", "party", "fete")
    private val DURATION_WORDS = listOf("heure", "heures", "hour", "hours", "min", "minute", "minutes", "long", "longue", "court", "courte", "short", "demi")

    // --- text --------------------------------------------------------------------------------------------------------

    private val MARKS = Regex("\\p{Mn}+")
    private val DIGIT = Regex("\\d")
    private val SPACES = Regex("\\s+")

    /** Lower case, accents removed, apostrophes as spaces ("d'une" is "d une"), spaces collapsed. */
    internal fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
            .replace('’', ' ').replace('\'', ' ').replace(SPACES, " ").trim()

    private val wordRegexes = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun wordRegex(phrase: String): Regex = wordRegexes.getOrPut(phrase) {
        Regex("(?<![a-z0-9])" + Regex.escape(phrase) + "(?![a-z0-9])")
    }

    private fun containsWord(text: String, phrase: String): Boolean = wordRegex(phrase).containsMatchIn(text)
}

/**
 * The model's reading and the listener's own words, together. The model wins on what needs judgement (the
 * atmosphere, the intensity, which genres an "ambiance" means); the request's explicit facts win on what is written
 * (a tempo, a length, a refused genre). A number the model wrote with no ground in the request is dropped: "Night
 * Drive" says nothing of a length, so a length the model gives it is invented.
 */
object IntentMerge {

    fun merge(request: String, model: PlaylistIntent?, rules: PlaylistIntent = RequestReader.read(request)): PlaylistIntent {
        if (model == null) return rules
        val modelBpm = model.targetBpm?.takeIf { RequestReader.impliesTempo(request) }
        val modelMinutes = model.targetDurationMinutes?.takeIf { RequestReader.impliesDuration(request) }
        val excluded = (rules.genresExcluded + model.genresExcluded).distinct().take(PlaylistIntentJson.MAX_GENRES)
        val included = (model.genresIncluded + rules.genresIncluded).distinct().filterNot { it in excluded }.take(PlaylistIntentJson.MAX_GENRES)
        return PlaylistIntent(
            targetBpm = rules.targetBpm ?: modelBpm,
            bpmTolerance = if (rules.targetBpm != null) rules.bpmTolerance else model.bpmTolerance.takeIf { modelBpm != null },
            energy = model.energy ?: rules.energy,
            genresIncluded = included,
            genresExcluded = excluded,
            targetDurationMinutes = rules.targetDurationMinutes ?: modelMinutes,
            mood = model.mood ?: rules.mood,
        )
    }
}
