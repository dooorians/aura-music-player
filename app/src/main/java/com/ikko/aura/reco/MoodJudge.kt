package com.ikko.aura.reco

/**
 * What can be measured of a song without hearing it, and what the app heard of its 30-second preview: the tempo and the loudness a catalogue states, the
 * tags listeners gave this very song, and the [probe] of the audio. Any of it may be missing.
 */
data class SongFeatures(
    /** Beats a minute as a catalogue states them; often an octave off (a ballad at 72 is listed at 144), see [MoodJudge]. */
    val bpm: Double? = null,
    /** The track's loudness in dB (Deezer's "gain": -6 is a loud master, -18 a quiet one). */
    val gainDb: Double? = null,
    /** Last.fm's tags for this song, lowercase, most telling first. */
    val trackTags: List<String> = emptyList(),
    /** What the app's own analyser heard of the preview; null when there was none or it could not be analysed. */
    val probe: AudioProbe? = null,
)

/**
 * Decides whether a song is calm, a night song or an energetic one, from what it is and not from what it is called: a song named "All Night" by a K-pop group,
 * loud, driven by a beat, is not a calm night song; a K-pop ballad, slow and soft, is.
 *
 * Several signals, each worth what it is reliable for, are averaged:
 *  - the tempo (the most telling, but an octave is often wrong: when what else is known says calm, a fast tempo is read as half);
 *  - how loud the audio is (what the app measured of the preview, else the master's loudness a catalogue states), judged against the other songs measured
 *    as much as against a fixed scale: the calmest of a pool is calm by comparison whatever the mastering of the day;
 *  - the texture of the audio (how much is bass, how much the energy moves), also against the pool;
 *  - the tags listeners gave the song, the most direct word there is;
 *  - the artist's style, only for what is loud by definition (metal, punk, techno...): a broad genre holds as many ballads as club songs;
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
    /** Songs measured needed before a song is judged against them. */
    private const val MIN_POOL = 8
    /** A tempo heard with less confidence than this is a guess and gives way to the one a catalogue states. */
    private const val MIN_BEAT_CONFIDENCE = 0.3

    /** [calm]: +1 for the calmest, -1 for the most energetic; [evidence]: how many signals besides the title spoke; [bpm]: the tempo as it was read. */
    class Reading(val calm: Double, val evidence: Int, val bpm: Double?, val gainDb: Double?, val nightCue: Boolean)

    private class Signal(val value: Double, val weight: Double)

    /** The songs measured, sorted, so a value can be placed among them. */
    class PoolStats(
        private val level: List<Double>,
        private val bass: List<Double>,
        private val movement: List<Double>,
    ) {
        enum class Kind { LEVEL, BASS, MOVEMENT }

        /** Share of the pool below [x]: 0 for the lowest, 1 for the highest; null when the pool is too small to judge by. */
        fun rank(kind: Kind, x: Double): Double? {
            val sorted = when (kind) { Kind.LEVEL -> level; Kind.BASS -> bass; Kind.MOVEMENT -> movement }
            if (sorted.size < MIN_POOL) return null
            val below = sorted.count { it < x }
            val equal = sorted.count { it == x }
            return (below + equal / 2.0) / sorted.size
        }

        companion object {
            val EMPTY = PoolStats(emptyList(), emptyList(), emptyList())

            fun of(features: Collection<SongFeatures>): PoolStats = PoolStats(
                level = features.mapNotNull { it.probe?.rmsDb }.sorted(),
                bass = features.mapNotNull { it.probe?.lowShare }.sorted(),
                movement = features.mapNotNull { it.probe?.variability }.sorted(),
            )
        }
    }

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
    /**
     * Styles that are loud by definition. Broad ones (K-pop, pop, hip-hop, R&B, funk, afrobeats...) are not here: they hold as many ballads as club songs, so
     * the song itself (its tempo, its loudness, its tags) decides.
     */
    private val ENERGETIC_ARTIST = setOf(
        "metal", "punk", "hardcore", "drill", "edm", "house", "techno", "trance", "eurodance", "dubstep", "drum and bass", "hardstyle", "pop punk",
    )
    private val CALM_TITLE = setOf("lullaby", "sleep", "night", "moon", "dream", "quiet", "calm", "slow", "lune", "nuit", "dormir", "berceuse", "reve", "rêve")
    private val ENERGETIC_TITLE = setOf("party", "dance", "club", "bang", "fire", "run", "jump", "bounce", "crazy")

    /** The tempo's say: slow is calm, and the faster the less sure it is (an octave is often wrong above 135). */
    private fun tempo(bpm: Double, sure: Boolean): Signal {
        val value = when {
            bpm < 80 -> 0.9
            bpm < 95 -> 0.6
            bpm < 108 -> 0.2
            bpm < 122 -> -0.25
            bpm < 135 -> -0.55
            else -> -0.2
        }
        return Signal(value, (if (bpm >= 135) 0.25 else 0.8) * (if (sure) 1.0 else 0.8))
    }

    /** The loudness's say on a fixed scale (what was heard in dBFS, or the master's stated gain): a loud one leans energetic, a quiet one calm. */
    private fun absoluteLoudness(f: SongFeatures): Double? {
        f.probe?.let { p ->
            return when {
                p.rmsDb >= -11.0 -> -0.5
                p.rmsDb >= -13.5 -> -0.2
                p.rmsDb >= -16.5 -> 0.15
                p.rmsDb >= -20.0 -> 0.5
                else -> 0.85
            }
        }
        val gain = f.gainDb ?: return null
        return when {
            gain >= -8.0 -> -0.5
            gain >= -10.0 -> -0.2
            gain >= -12.5 -> 0.15
            gain >= -14.5 -> 0.5
            else -> 0.85
        }
    }

    private fun mean(signals: List<Signal>): Double {
        val w = signals.sumOf { it.weight }
        return if (w == 0.0) 0.0 else signals.sumOf { it.value * it.weight } / w
    }

    fun read(features: SongFeatures?, artistTags: List<String>, title: String, pool: PoolStats = PoolStats.EMPTY): Reading {
        val signals = ArrayList<Signal>()
        val gain = features?.gainDb
        if (features != null) {
            absoluteLoudness(features)?.let { fixed ->
                // Against the pool as much as against the scale: the calmest of the songs measured is calm.
                val relative = features.probe?.let { pool.rank(PoolStats.Kind.LEVEL, it.rmsDb) }?.let { 1.0 - 2.0 * it }
                signals += Signal(if (relative == null) fixed else 0.5 * fixed + 0.5 * relative, 0.6)
            }
            features.probe?.let { p ->
                // How much the energy moves: the steadier, the calmer. (The share of bass is measured too but sits near 1 for half the songs: it says nothing.)
                val movement = pool.rank(PoolStats.Kind.MOVEMENT, p.variability)
                if (movement != null) signals += Signal(1.0 - 2.0 * movement, 0.4)
            }
        }
        val trackTags = features?.trackTags.orEmpty().map { it.trim().lowercase() }
        val calmHits = trackTags.count { it in CALM_TAGS }
        val energeticHits = trackTags.count { it in ENERGETIC_TAGS }
        if (calmHits + energeticHits > 0) signals += Signal(((calmHits - energeticHits) * 0.6).coerceIn(-1.0, 1.0), 1.0)
        val artist = artistTags.take(4).map { it.trim().lowercase() }
        val artistCalm = artist.count { t -> CALM_ARTIST.any { it == t || t.endsWith(" $it") } }
        val artistEnergetic = artist.count { t -> ENERGETIC_ARTIST.any { it == t || t.endsWith(" $it") } }
        if (artistCalm + artistEnergetic > 0) signals += Signal(((artistCalm - artistEnergetic) * 0.7).coerceIn(-1.0, 1.0), 0.45)
        // The tempo last: what else says decides whether a fast one is a fast song or a slow one counted twice. What was heard beats what is stated.
        val heard = features?.probe?.takeIf { it.bpm > 0.0 && it.beatConfidence >= MIN_BEAT_CONFIDENCE }?.bpm
        var bpm = heard ?: features?.bpm?.takeIf { it > 0.0 }
        if (bpm != null) {
            if (signals.isNotEmpty() && mean(signals) >= 0.4 && bpm in 105.0..190.0) bpm /= 2
            signals += tempo(bpm, sure = heard != null)
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

    fun verdict(mood: Mood, features: SongFeatures?, artistTags: List<String>, title: String, pool: PoolStats = PoolStats.EMPTY): Double? =
        verdict(mood, read(features, artistTags, title, pool))
}
