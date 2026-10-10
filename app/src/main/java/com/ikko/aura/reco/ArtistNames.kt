package com.ikko.aura.reco

import java.text.Normalizer

/**
 * Artist names as the recommendations compare them: one normal form for the same artist written two ways, and the list
 * of artists a credit line names (an artist field, a "feat." in a title, the featured artists a source lists).
 *
 * Pure, so what blocking an artist covers can be tested without a device.
 */
object ArtistNames {

    private val DIACRITICS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")
    private val TOPIC = Regex("\\s*-\\s*topic$", RegexOption.IGNORE_CASE)
    private val VEVO = Regex("vevo$", RegexOption.IGNORE_CASE)

    /** What separates two artists in a credit line; "/" only when spaced, so "AC/DC" stays one artist. */
    private val SEPARATORS = Regex(
        "\\s*(?:,|;|&|\\+|×|\\s/\\s|\\bx\\b|\\bfeat\\.?|\\bfeaturing\\b|\\bft\\.?|\\bwith\\b|\\bvs\\.?|\\band\\b|\\bet\\b)\\s*",
        RegexOption.IGNORE_CASE,
    )

    /** "(feat. B)", "[ft. B & C]", "feat. B" at the end of a title: where a song's own credits hide. */
    private val TITLE_CREDIT = Regex(
        "[(\\[]\\s*(?:feat\\.?|featuring|ft\\.?|with|avec)\\s+([^)\\]]+)[)\\]]|\\s-\\s(?:feat\\.?|ft\\.?)\\s+(.+)$|\\b(?:feat\\.?|ft\\.?)\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )

    /** A name with case, accents, " - Topic", a "VEVO" suffix and punctuation set aside: "Beyoncé - Topic" is "beyonce". */
    fun key(name: String): String {
        var s = Normalizer.normalize(name.trim(), Normalizer.Form.NFKD).replace(DIACRITICS, "")
        s = s.lowercase().replace(TOPIC, "")
        s = s.replace(Regex("[\"'`’.·•]+"), "").replace(SPACES, " ").trim()
        if (s.length > 4 && VEVO.containsMatchIn(s)) s = s.replace(VEVO, "").trim()
        return s
    }

    /** The artists a raw artist field names, as keys, without duplicates and without blanks. */
    fun split(raw: String): List<String> =
        raw.split(SEPARATORS).map(::key).filter { it.isNotEmpty() }.distinct()

    /** The artists a title credits ("Song (feat. B & C)"), as keys. */
    fun featuredIn(title: String): List<String> =
        TITLE_CREDIT.findAll(title).flatMap { m -> m.groupValues.drop(1).filter { it.isNotBlank() }.asSequence() }
            .flatMap { split(it).asSequence() }.distinct().toList()

    /**
     * Every artist a song is credited to: its artist field, the title's "feat.", and [extra] (what a source lists
     * separately — JioSaavn's featured artists). The first is the primary artist.
     */
    fun creditsOf(artist: String, title: String, extra: List<String> = emptyList()): List<String> =
        (split(artist) + extra.flatMap(::split) + featuredIn(title)).distinct()

    /**
     * Whether a song credited to [credits] (keys) — whose artist field was [rawArtist] — involves one of [blocked].
     *
     * A blocked name matches a credited artist exactly; a blocked name that is itself a group of names ("Tyler, The
     * Creator", "Simon & Garfunkel") also matches when the whole credit line contains it, bounded by non-letters.
     */
    fun involves(credits: Collection<String>, rawArtist: String, blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return false
        if (credits.any { it in blocked }) return true
        val line = " ${key(rawArtist)} "
        return blocked.any { b ->
            (b.contains(' ') || b.contains(',') || b.contains('&')) && Regex("(?<![\\p{L}\\p{N}])${Regex.escape(b)}(?![\\p{L}\\p{N}])").containsMatchIn(line)
        }
    }

    /** A name for the artist field of a source whose uploader is a channel ("Rina Sawayama - Topic", "RinaSawayamaVEVO"). */
    fun cleanChannel(name: String): String =
        name.trim().replace(TOPIC, "").replace(Regex("(?i)\\s*vevo$"), "").trim()
}
