package com.ikko.aura.reco

/**
 * An album at two levels: the record ([baseAlbumKey], "Album" whatever its edition) and the edition it is sold in ([editionTag]:
 * deluxe, live, remastered...). A page is the right one when both agree; a Deluxe is not the standard record, a Live is not the studio one.
 */
object AlbumIdentity {
    /** Editions that carry the same record with more on it: the next best thing when the exact edition is not listed. */
    private val EXTRA = linkedMapOf(
        "deluxe" to Regex("\\bdeluxe\\b"), "expanded" to Regex("\\bexpanded\\b"), "remaster" to Regex("\\bremaster(?:ed)?\\b"),
        "anniversary" to Regex("\\banniversary\\b"), "bonus" to Regex("\\bbonus\\b"), "collector" to Regex("\\bcollector"),
    )
    /** Editions that are another record: never taken for the one asked for. */
    private val OTHER = linkedMapOf(
        "live" to Regex("\\blive\\b"), "instrumental" to Regex("\\binstrumentals?\\b"), "acoustic" to Regex("\\bacoustic\\b"),
        "remix" to Regex("\\bremix(?:es|ed)?\\b"), "demo" to Regex("\\bdemos?\\b"), "cover" to Regex("\\bcovers?\\b"),
        "tribute" to Regex("\\btribute\\b"), "karaoke" to Regex("\\bkaraoke\\b"), "slowed" to Regex("\\bslowed\\b"),
        "nightcore" to Regex("\\bnightcore\\b"), "sped" to Regex("\\bsped\\b"),
    )

    fun baseAlbumKey(title: String): String = TrackIdentity.baseKey(title)

    private fun tagsIn(title: String, table: Map<String, Regex>): Set<String> {
        val low = title.lowercase()
        return table.filterValues { it.containsMatchIn(low) }.keys
    }

    /** Sorted, "+"-joined; empty for the standard edition. */
    fun editionTag(title: String): String = (tagsIn(title, EXTRA) + tagsIn(title, OTHER)).sorted().joinToString("+")

    /** The record and its edition as one key. */
    fun key(title: String): String = baseAlbumKey(title) + "#" + editionTag(title)

    /**
     * How well [candidate] stands for the album [wanted]: null when it is another record (another base, or a kind of edition the
     * wanted one does not carry), 2 for the same edition, 1 for the same record with other extras (a Deluxe of the standard).
     */
    fun fit(wanted: String, candidate: String): Int? {
        if (baseAlbumKey(wanted).let { it.isEmpty() || it != baseAlbumKey(candidate) }) return null
        if (tagsIn(wanted, OTHER) != tagsIn(candidate, OTHER)) return null
        return if (editionTag(wanted) == editionTag(candidate)) 2 else 1
    }
}

/**
 * How an album is told apart across sources and spellings, for what the listener refused: the page's reference when the source gives
 * one, and the album's name with its artist's, folded (case, accents), for the sources that give none.
 */
object AlbumKeys {
    private const val PREFIX = "album:"

    fun ofRef(ref: String) = PREFIX + ref.trim()

    fun ofName(album: String, artist: String): String =
        PREFIX + ArtistNames.key(album) + "\u0000" + ArtistNames.primaryKey(artist)

    /** Every key the album answers to: the one of its reference and the one of its name. */
    fun of(ref: String?, album: String, artist: String): Set<String> = buildSet {
        if (!ref.isNullOrBlank()) add(ofRef(ref))
        if (album.isNotBlank()) add(ofName(album, artist))
    }
}
