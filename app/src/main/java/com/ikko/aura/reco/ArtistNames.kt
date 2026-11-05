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
    private val PUNCT = Regex("[\"'`’.·•]+")
    private val VEVO_END = Regex("(?i)\\s*vevo$")

    private val KEY_CACHE = java.util.concurrent.ConcurrentHashMap<String, String>()

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
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        KEY_CACHE[trimmed]?.let { return it }
        var s = Normalizer.normalize(trimmed, Normalizer.Form.NFKD).replace(DIACRITICS, "")
        s = s.replace('$', 's')
        s = s.lowercase().replace(TOPIC, "")
        s = s.replace(PUNCT, "").replace(SPACES, " ").trim()
        if (s.length > 4 && VEVO.containsMatchIn(s)) s = s.replace(VEVO, "").trim()
        KEY_CACHE[trimmed] = s
        return s
    }

    /** What follows the first artist of a credit line: " feat. B", " ft B", " Featuring B" (not "Featherstone"). */
    private val FEATURING = Regex("\\s+(?:feat|ft|featuring)\\b.*$", RegexOption.IGNORE_CASE)

    /**
     * The first artist of a credit line, as written: "A, B" and "A feat. B" are A. [beforeAmpersand] also cuts at " &", for
     * names that come from a source's credit line ("A & B"); a library's own artist field keeps it ("Simon & Garfunkel").
     * The one place this is decided, so every table that is keyed by artist agrees on who the artist is.
     */
    fun primary(raw: String, beforeAmpersand: Boolean = false): String {
        var s = raw.substringBefore(',').replace(FEATURING, "")
        if (beforeAmpersand) s = s.substringBefore(" &")
        return s.trim()
    }

    fun primaryKey(raw: String, beforeAmpersand: Boolean = false): String = key(primary(raw, beforeAmpersand))

    private val LATIN_ALIAS = Regex("^(.+?)\\s*[(（]([^()（）]+)[)）]$")

    /**
     * The name a catalogue (Last.fm, Deezer) knows an artist by: one tagged in its own script with a Latin alias, "れをる (Reol)", is
     * asked about as "Reol". Any other name is returned as it is.
     */
    fun askName(raw: String): String {
        val m = LATIN_ALIAS.find(raw.trim()) ?: return raw
        val main = m.groupValues[1]
        val alias = m.groupValues[2].trim()
        val mainOwnScript = main.any { it.isLetter() && it.code >= 0x250 }
        val aliasLatin = alias.any { it.isLetter() } && alias.all { it.code < 0x250 }
        return if (mainOwnScript && aliasLatin) alias else raw
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
            if (b.contains(' ') || b.contains(',') || b.contains('&')) {
                val idx = line.indexOf(b)
                if (idx >= 0) {
                    val before = line.getOrNull(idx - 1)
                    val after = line.getOrNull(idx + b.length)
                    val beforeOk = before == null || !before.isLetterOrDigit()
                    val afterOk = after == null || !after.isLetterOrDigit()
                    beforeOk && afterOk
                } else false
            } else false
        }
    }

    /**
     * Whether two artist keys can be the same artist written differently: equal, or one is the other's first words
     * ("rebecca" on a channel branded with a first name, "rebecca black" in the tags of the files). The shorter name
     * must be long enough ([MIN_ALIAS]) that "lisa" is not taken for "lisa ono".
     */
    fun compatible(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= MIN_ALIAS && long.startsWith("$short ")
    }

    fun anyCompatible(a: Collection<String>, b: Collection<String>): Boolean = a.any { x -> b.any { y -> compatible(x, y) } }

    private const val MIN_ALIAS = 5

    /** A name for the artist field of a source whose uploader is a channel ("Rina Sawayama - Topic", "RinaSawayamaVEVO"). */
    fun cleanChannel(name: String): String =
        name.trim().replace(TOPIC, "").replace(VEVO_END, "").replace('$', 's').trim()
}
