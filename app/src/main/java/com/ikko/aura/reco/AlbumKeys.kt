package com.ikko.aura.reco

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
