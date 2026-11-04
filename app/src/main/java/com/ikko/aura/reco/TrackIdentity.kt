package com.ikko.aura.reco

import java.text.Normalizer
import kotlin.math.abs

/**
 * What makes two entries the same recording, whatever their id or the way a source writes them: the title with
 * decorations that do not change the recording set aside, the credited artists, and — when both say — the length.
 */
object TrackIdentity {

    private val DIACRITICS = Regex("\\p{M}+")
    private val BRACKETED = Regex("[(\\[][^)\\]]*[)\\]]")
    private val SUFFIX_DASH = Regex("\\s-\\s(.*)$")

    /** Words that decorate a title without making it another recording; "remix", "live", "acoustic" are not here. */
    private val NOISE = Regex(
        "\\b(?:remaster(?:ed)?(?:\\s+\\d{4})?|\\d{4}\\s+remaster(?:ed)?|album version|single version|radio edit|explicit|clean|" +
            "deluxe(?: edition)?|bonus track|mono|stereo|feat\\.?|ft\\.?|featuring|with|official audio|audio|lyrics?)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** The title as compared: no accents, case, noise words, "(feat. …)" or "- Remastered 2011", single spaces. */
    fun titleKey(title: String): String {
        var s = Normalizer.normalize(title, Normalizer.Form.NFKD).replace(DIACRITICS, "").lowercase()
        // A bracketed part that is only noise ("(feat. B)", "(Remastered)") goes; one that says something ("(Live)") stays.
        s = BRACKETED.replace(s) { m -> if (NOISE.containsMatchIn(m.value) && !MEANINGFUL.containsMatchIn(m.value)) "" else m.value }
        s = SUFFIX_DASH.replace(s) { m -> if (NOISE.containsMatchIn(m.groupValues[1]) && !MEANINGFUL.containsMatchIn(m.groupValues[1])) "" else m.value }
        s = NOISE.replace(s, " ")
        s = s.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return s
    }

    private val MEANINGFUL = Regex("\\b(?:remix|live|acoustic|instrumental|demo|cover|sped|slowed|reverb|karaoke)\\b", RegexOption.IGNORE_CASE)

    /**
     * The title with every bracketed part and "- suffix" removed: "Alterlife (Seiho Remix)" and "Alterlife" share a base.
     * Used to tell a remix, a live cut or an acoustic take of a song the listener has from a song they do not.
     */
    fun baseKey(title: String): String {
        var s = Normalizer.normalize(title, Normalizer.Form.NFKD).replace(DIACRITICS, "").lowercase()
        s = BRACKETED.replace(s, " ")
        s = SUFFIX_DASH.replace(s, "")
        s = NOISE.replace(s, " ")
        return s.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    /** The base key of a song for matching against others' bases: base title and primary artist. */
    fun baseIdentity(title: String, artist: String): String = baseKey(title) + "\u0001" + ArtistNames.split(artist).firstOrNull().orEmpty()

    /**
     * The one key a song answers to in every source, local or streamed: its primary artist and its title without "(feat. ...)" and the
     * other decorations that do not change the recording ("- Remastered 2011"). What says it is another recording ("(Live)", "(Remix)")
     * stays, so the live take and the studio one keep their own affinity.
     */
    fun canonicalKey(artist: String, title: String): String = ArtistNames.primaryKey(artist) + "::" + titleKey(title)

    /** A song's key for exact matching: title and primary artist. */
    fun key(title: String, artist: String): String = titleKey(title) + "\u0001" + ArtistNames.split(artist).firstOrNull().orEmpty()

    /** Whether lengths agree: unknown on either side agrees, otherwise within [toleranceMs]. */
    fun sameLength(a: Long, b: Long, toleranceMs: Long = 6_000L): Boolean = a <= 0 || b <= 0 || abs(a - b) <= toleranceMs

    /** One entry of a library, reduced to what matching needs. */
    class Entry(val title: String, val artist: String, val durationMs: Long, extraCredits: List<String> = emptyList(), val album: String = "") {
        val titleKey: String = titleKey(title)
        val credits: Set<String> = ArtistNames.creditsOf(artist, title, extraCredits).toSet()
        /** The album as compared; blank for no album, or the placeholder a source puts when it does not know. */
        val albumKey: String = if (album.isBlank() || album.equals("YouTube Music", ignoreCase = true)) "" else baseKey(album)
    }

    /** The songs the listener already has: a candidate that matches any of them is not a discovery. */
    class Index(entries: Collection<Entry>) {
        private val byTitle: Map<String, List<Entry>> = entries.filter { it.titleKey.isNotEmpty() }.groupBy { it.titleKey }

        /** Album key → the artists credited on that album in the library. */
        private val byAlbum: Map<String, Set<String>> = entries.filter { it.albumKey.isNotEmpty() }
            .groupBy { it.albumKey }.mapValues { (_, list) -> list.flatMapTo(HashSet()) { it.credits } }

        val size: Int = entries.size

        /** Whether [candidate] is the same recording as something in the index, under another id or spelling. */
        fun contains(candidate: Entry): Boolean {
            val same = byTitle[candidate.titleKey] ?: return false
            return same.any { e ->
                sameLength(e.durationMs, candidate.durationMs) && (
                    ArtistNames.anyCompatible(e.credits, candidate.credits) ||
                        // "Artist - Title" kept in the title field of a file with no artist tag.
                        e.credits.isEmpty() || candidate.credits.isEmpty()
                    )
            }
        }

        /**
         * Whether the listener has the album [album] by one of [credits]: the same album under the same artist (or an
         * alias of it), whichever of its songs they have. A song of an album they own is not a discovery.
         */
        fun hasAlbum(album: String, credits: Collection<String>): Boolean {
            if (album.isBlank() || album.equals("YouTube Music", ignoreCase = true)) return false
            val artistsOnAlbum = byAlbum[baseKey(album)] ?: return false
            return artistsOnAlbum.isEmpty() || ArtistNames.anyCompatible(artistsOnAlbum, credits)
        }

        /** Whether the listener has songs by [artistKey] (or by an artist that is the same under another spelling). */
        fun hasArtist(artistKey: String): Boolean = artistKey in artists || artists.any { ArtistNames.compatible(it, artistKey) }

        private val artists: Set<String> = entries.flatMapTo(HashSet()) { it.credits }
    }
}
