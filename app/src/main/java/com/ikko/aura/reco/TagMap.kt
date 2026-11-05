package com.ikko.aura.reco

/** What a file's tags give the recommendations. */
data class ParsedTags(
    val mbid: String = "",
    val isrc: String = "",
    val replayGainDb: Double? = null,
    val bpm: Double = 0.0,
    val genres: List<String> = emptyList(),
    val originalYear: Int = 0,
)

/**
 * Reads the (name, value) pairs of a file's tags, whatever the container wrote them in: ID3 frames ("TSRC", "TBPM", "TCON", the "TXXX"
 * descriptions), Vorbis comments ("ISRC", "REPLAYGAIN_TRACK_GAIN", "MUSICBRAINZ_TRACKID") and the iTunes freeform atoms. Names are compared
 * folded (case, spaces, underscores). Pure, so it is checked without a file.
 */
object TagMap {
    private fun fold(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    private val MBID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val ISRC = Regex("^[A-Z]{2}[A-Z0-9]{3}\\d{7}$")
    private val GAIN = Regex("^\\s*([+-]?\\d+(?:[.,]\\d+)?)\\s*(?:dB)?\\s*$", RegexOption.IGNORE_CASE)
    private val CONTROL = Regex("[\\p{Cc}&&[^\\u0000]]")
    private val YEAR = Regex("(?:^|\\D)((?:19|20)\\d{2})(?:\\D|$)")

    /**
     * A value as a tagger may have left it: a byte-order mark kept from a UTF-16 frame, the NUL that ends an ID3 string
     * (or separates its values), control characters. Removed before anything is compared.
     */
    internal fun clean(value: String): String = value
        .replace("\uFEFF", "")
        // Kept: NUL, which separates the values of one frame. A tab or a line break separates words.
        .replace(CONTROL, " ")
        .trim { it.isWhitespace() || it == '\u0000' }

    fun parse(pairs: List<Pair<String, String>>): ParsedTags {
        fun values(vararg names: String): List<String> {
            val wanted = names.map(::fold).toSet()
            return pairs.filter { fold(it.first) in wanted }.map { clean(it.second) }.filter { it.isNotEmpty() }
        }
        // The recording's id: Vorbis "MUSICBRAINZ_TRACKID", or the ID3 UFID frame the adapter hands over as "UFID:<owner>". Picard
        // writes the owner "http://musicbrainz.org", which folds to another name than the bare host: both are read.
        val mbid = values("musicbrainz_trackid", "ufid:musicbrainz.org", "ufid:http://musicbrainz.org", "musicbrainz track id", "musicbrainz recording id")
            .firstNotNullOfOrNull { v -> MBID.find(v)?.value?.lowercase() }.orEmpty()
        val isrc = values("isrc", "tsrc").map { it.uppercase().replace("-", "").replace(" ", "") }.firstOrNull { ISRC.matches(it) }.orEmpty()
        fun gain(vararg names: String) = values(*names).firstNotNullOfOrNull { v -> GAIN.find(v)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }
        val replay = gain("replaygain_track_gain") ?: gain("replaygain_album_gain")
        val bpm = values("bpm", "tbpm", "beatsperminute").firstNotNullOfOrNull { it.replace(',', '.').toDoubleOrNull() }?.takeIf { it in 30.0..300.0 } ?: 0.0
        val genres = values("genre", "tcon", "contenttype").flatMap { it.split(';', '/', '\u0000') }
            .mapNotNull { GenreNormalizer.normalize(it) }.distinct()
        val year = values("originalyear", "originaldate", "tdor", "tory").firstNotNullOfOrNull { v -> YEAR.find(v)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        return ParsedTags(mbid, isrc, replay, bpm, genres, year)
    }
}
