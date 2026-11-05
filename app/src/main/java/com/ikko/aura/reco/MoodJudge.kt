package com.ikko.aura.reco

/**
 * What can be measured of a song without hearing it: the tempo and the loudness a catalogue states, and the tags listeners gave this very song. Any of it
 * may be missing.
 */
data class SongFeatures(
    /** Beats a minute as a catalogue states them; often an octave off (a ballad at 72 is listed at 144), see [MoodJudge]. */
    val bpm: Double? = null,
    /** The track's loudness in dB (Deezer's "gain": -6 is a loud master, -18 a quiet one). */
    val gainDb: Double? = null,
    /** Last.fm's tags for this song, lowercase, most telling first. */
    val trackTags: List<String> = emptyList(),
)

/**
 * Decides whether a song is calm, a night song or an energetic one, from what it is and not from what it is called: a song named "All Night" by a K-pop group,
 * loud, in the genre of the dance floor, is not a calm night song.
 *
 * Several signals, each worth what it is reliable for, are averaged:
 *  - the tempo (the most telling, but an octave is often wrong: when what else is known says calm, a fast tempo is read as half);
 *  - the loudness of the master (a loud one is rarely calm, a quiet one rarely not);
 *  - the tags listeners gave the song, the most direct word there is;
 *  - the style of the artist (their first tags);
 *  - the title, which counts for very little and is never evidence by itself.
 * A song is only judged when at least two of the signals other than the title speak: with less, it is in no ambience.
 *
 * Pure, so it is checked without a device.
 */
object MoodJudge {
    const val CHILL_MIN = 0.25
    const val NIGHT_MIN = 0.40
    const val ENERGY_MIN = 0.25
    private const val MIN_EVIDENCE = 2

    /** [calm]: +1 for the calmest, -1 for the most energetic; [evidence]: how many signals besides the title spoke; [bpm]: the tempo as it was read. */
    class Reading(val calm: Double, val evidence: Int, val bpm: Double?, val gainDb: Double?, val nightCue: Boolean)

    private class Signal(val value: Double, val weight: Double)

    private val CALM_TAGS = setOf(
        "chill", "chillout", "chill out", "calm", "relaxing", "relax", "sleep", "mellow", "ambient", "lullaby", "soothing", "peaceful", "dreamy",
        "atmospheric", "soft", "slow", "sad", "melancholy", "melancholic", "lo-fi", "lofi", "downtempo", "acoustic", "piano", "ballad", "nocturnal",
        "night", "late night", "study", "easy listening", "lounge", "quiet", "slowcore", "bedroom pop", "intimate", "gentle", "beautiful",
    )
    private val ENERGETIC_TAGS = setOf(
        "dance", "party", "energetic", "upbeat", "workout", "gym", "hype", "banger", "club", "edm", "house", "techno", "fast", "aggressive", "pump up",
        "fun", "happy", "summer", "uptempo", "dancefloor", "rave", "bass", "hard", "power", "motivation", "running", "catchy", "bouncy",
    )
    private val NIGHT_TAGS = setOf("night", "late night", "nocturnal", "sleep", "insomnia", "midnight", "dark")
    private val CALM_ARTIST = setOf(
        "ambient", "chillout", "downtempo", "lo-fi", "lofi", "new age", "classical", "neoclassical", "acoustic", "singer-songwriter", "folk", "indie folk",
        "dream pop", "slowcore", "trip-hop", "easy listening", "lounge", "instrumental", "piano", "bedroom pop", "soft rock", "chamber pop", "ballad",
    )
    private val ENERGETIC_ARTIST = setOf(
        "metal", "punk", "hardcore", "hip-hop", "hip hop", "rap", "trap", "drill", "dance", "edm", "house", "techno", "trance", "k-pop", "kpop",
        "hyperpop", "eurodance", "reggaeton", "dubstep", "drum and bass", "dancehall", "dance-pop", "dance pop", "pop punk", "afrobeats", "funk",
    )
    private val CALM_TITLE = setOf("lullaby", "sleep", "night", "moon", "dream", "quiet", "calm", "slow", "lune", "nuit", "dormir", "berceuse", "reve", "rêve")
    private val ENERGETIC_TITLE = setOf("party", "dance", "club", "bang", "fire", "run", "jump", "bounce", "crazy")

    /** The tempo's say: slow is calm, and the faster the less sure it is (an octave is often wrong above 135). */
    private fun tempo(bpm: Double): Signal {
        val value = when {
            bpm < 80 -> 0.9
            bpm < 95 -> 0.6
            bpm < 108 -> 0.2
            bpm < 122 -> -0.25
            bpm < 135 -> -0.55
            else -> -0.2
        }
        return Signal(value, if (bpm >= 135) 0.25 else 0.8)
    }

    /** The loudness's say: a loud master leans energetic, a quiet one calm. */
    private fun loudness(gainDb: Double): Signal {
        val value = when {
            gainDb >= -8.0 -> -0.5
            gainDb >= -10.0 -> -0.2
            gainDb >= -12.5 -> 0.15
            gainDb >= -14.5 -> 0.5
            else -> 0.85
        }
        return Signal(value, 0.6)
    }

    private fun mean(signals: List<Signal>): Double {
        val w = signals.sumOf { it.weight }
        return if (w == 0.0) 0.0 else signals.sumOf { it.value * it.weight } / w
    }

    fun read(features: SongFeatures?, artistTags: List<String>, title: String): Reading {
        val signals = ArrayList<Signal>()
        val gain = features?.gainDb
        if (gain != null) signals += loudness(gain)
        val trackTags = features?.trackTags.orEmpty().map { it.trim().lowercase() }
        val calmHits = trackTags.count { it in CALM_TAGS }
        val energeticHits = trackTags.count { it in ENERGETIC_TAGS }
        if (calmHits + energeticHits > 0) signals += Signal(((calmHits - energeticHits) * 0.6).coerceIn(-1.0, 1.0), 1.0)
        val artist = artistTags.take(4).map { it.trim().lowercase() }
        val artistCalm = artist.count { t -> CALM_ARTIST.any { it == t || t.endsWith(" $it") } }
        val artistEnergetic = artist.count { t -> ENERGETIC_ARTIST.any { it == t || t.endsWith(" $it") } }
        if (artistCalm + artistEnergetic > 0) signals += Signal(((artistCalm - artistEnergetic) * 0.7).coerceIn(-1.0, 1.0), 0.45)
        // The tempo last: what else says decides whether a fast one is a fast song or a slow one counted twice.
        var bpm = features?.bpm?.takeIf { it > 0.0 }
        if (bpm != null) {
            if (signals.isNotEmpty() && mean(signals) >= 0.4 && bpm in 105.0..190.0) bpm /= 2
            signals += tempo(bpm)
        }
        val evidence = signals.size
        val words = title.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        val titleCalm = words.count { it in CALM_TITLE }
        val titleEnergetic = words.count { it in ENERGETIC_TITLE }
        if (titleCalm + titleEnergetic > 0) signals += Signal(((titleCalm - titleEnergetic) * 0.6).coerceIn(-1.0, 1.0), 0.12)
        return Reading(mean(signals), evidence, bpm, gain, nightCue = trackTags.any { it in NIGHT_TAGS })
    }

    /** How well the song fits [mood], 0..1; null when it is not of it, or when too little is known of it to say. */
    fun verdict(mood: Mood, r: Reading): Double? {
        if (r.evidence < MIN_EVIDENCE) return null
        return when (mood) {
            Mood.CHILL -> r.calm.takeIf { it >= CHILL_MIN }
            Mood.NIGHT ->
                if (r.calm >= NIGHT_MIN && (r.bpm == null || r.bpm < 125.0) && (r.gainDb == null || r.gainDb < -8.0)) (r.calm + if (r.nightCue) 0.1 else 0.0).coerceAtMost(1.0)
                else null
            Mood.ENERGY -> (-r.calm).takeIf { it >= ENERGY_MIN }
        }
    }

    fun verdict(mood: Mood, features: SongFeatures?, artistTags: List<String>, title: String): Double? = verdict(mood, read(features, artistTags, title))
}
