package com.ikko.aura.reco

import java.time.LocalDate

/** How often a playlist is made again. */
enum class Cadence { DAILY, WEEKLY }

/**
 * The seven playlists. [key] is the Home card's id and the automatic playlist's key, so a card, its playlist and its
 * recommendations are one thing everywhere.
 */
enum class RecoKind(val key: String, val title: String, val cadence: Cadence) {
    DISCOVERY("disc", "Découverte", Cadence.WEEKLY),
    STYLE("style", "Dans ton style", Cadence.WEEKLY),
    DAILY("daily", "Mix du jour", Cadence.DAILY),
    ENERGY("energy", "Ça bouge !", Cadence.WEEKLY),
    NEW("new", "Nouveautés", Cadence.WEEKLY),
    CHILL("chill", "Chill", Cadence.WEEKLY),
    NIGHT("night", "Nuit", Cadence.WEEKLY);

    companion object {
        fun ofKey(key: String?): RecoKind? = entries.firstOrNull { it.key == key }
    }
}

/** How a release date is known. A date that is only the day a file was uploaded is not a release date. */
enum class DateReliability { EXACT, YEAR, UNKNOWN }

data class Release(val date: LocalDate?, val year: Int, val reliability: DateReliability) {
    companion object {
        val Unknown = Release(null, 0, DateReliability.UNKNOWN)
        fun ofYear(year: Int) = if (year in 1900..2100) Release(null, year, DateReliability.YEAR) else Unknown
        fun ofDate(date: LocalDate) = Release(date, date.year, DateReliability.EXACT)
    }
}

/** Why a candidate was found: the signal behind its explanation. */
enum class ProvKind {
    /** By one of the listener's artists. */
    ARTIST,
    /** Listed by a source as close to a song the listener plays. */
    RELATED_TRACK,
    /** Credited together with one of the listener's artists. */
    COLLAB,
    /** By a genre the listener's files carry. */
    GENRE,
    /** Found by a query for an ambience. */
    MOOD_QUERY,
}

data class Provenance(val kind: ProvKind, val seed: String, val seedAffinity: Double, val mood: Mood? = null)

enum class Mood { CHILL, NIGHT, ENERGY }

/** Whether a source has been seen to give a song's audio. */
enum class Playability { VERIFIED, UNVERIFIED }

/**
 * A song a source offers, before it is a recommendation. Plain data, so the whole selection runs without Android; the
 * engine turns a chosen one into the [com.ikko.aura.data.model.Track] the app plays.
 */
data class Candidate(
    /** The key the app plays it by: `ytm:<id>`, `jiosaavn:<id>`, `<source>:<id>`. */
    val id: String,
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkUrl: String?,
    /** Artists the source lists apart from the artist field (featured artists). */
    val extraCredits: List<String> = emptyList(),
    val release: Release = Release.Unknown,
    /** "instrumental" for a language tag, a category, tags: whatever the source says about the song itself. */
    val tags: List<String> = emptyList(),
    val provenance: List<Provenance> = emptyList(),
    val playability: Playability = Playability.UNVERIFIED,
    /** The song's album on its source, when the source says. */
    val albumRef: String? = null,
    /**
     * Whether the source vouches that this is a song and not a video: a search for songs, an auto-generated artist
     * channel. A "related" list mixes in clips and talks; those are false until the song's own page says otherwise.
     */
    val confirmedSong: Boolean = true,
    /** The tempo a catalogue states, when one does; null means none was given. */
    val bpm: Double? = null,
) {
    val credits: List<String> by lazy { ArtistNames.creditsOf(artist, title, extraCredits) }
    val identity: String by lazy { TrackIdentity.key(title, artist) }
}

/** The explanation shown under a recommended song: a code and the names it is about, so it can only say what was used. */
data class Reason(val code: ReasonCode, val subject: String = "") {
    fun text(): String = when (code) {
        ReasonCode.LISTENED_ARTIST -> "Parce que tu écoutes $subject"
        ReasonCode.RELATED_TO -> "Proche de $subject"
        ReasonCode.COLLAB -> "En featuring avec $subject, que tu écoutes"
        ReasonCode.NEW_FROM_ARTIST -> "Nouvelle sortie de $subject, que tu écoutes"
        ReasonCode.NEW_RELEASE -> "Nouvelle sortie proche de tes goûts"
        ReasonCode.GENRE -> "Dans un style de ta bibliothèque : $subject"
        ReasonCode.CALM -> "Adapté à une ambiance calme"
        ReasonCode.NIGHT -> "Ambiance nocturne"
        ReasonCode.ENERGETIC -> "Énergique, dans tes goûts"
        ReasonCode.LIBRARY_ARTIST -> "Un artiste de ta bibliothèque : $subject"
        ReasonCode.EXPLORATION -> "Une piste à explorer, proche de $subject"
        ReasonCode.NONE -> ""
    }.trim()

    fun encode(): String = "${code.name}|$subject"

    companion object {
        fun decode(raw: String?): Reason? {
            if (raw.isNullOrEmpty()) return null
            val code = ReasonCode.entries.firstOrNull { it.name == raw.substringBefore('|') } ?: return null
            return Reason(code, raw.substringAfter('|', ""))
        }
    }
}

enum class ReasonCode {
    LISTENED_ARTIST, RELATED_TO, COLLAB, NEW_FROM_ARTIST, NEW_RELEASE, GENRE, CALM, NIGHT, ENERGETIC, LIBRARY_ARTIST, EXPLORATION, NONE,
}

/** One song of a generated playlist, with what is needed to explain and to play it. */
data class Pick(val candidate: Candidate, val reason: Reason, val score: Double)
