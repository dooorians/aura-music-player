package com.ikko.aura.source.ytm

import com.ikko.aura.reco.ArtistNames
import com.ikko.aura.reco.authority.CompilationTitles
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The page of an album on YouTube Music, found from its title and its artist: where a Home tile, or the source switch of an album, goes. */
@Singleton
class AlbumRefResolver internal constructor(
    private val ytmClient: YtmClient?,
    internal var searcher: ((String) -> List<YtmAlbumRef>)? = null,
) {
    @Inject
    constructor(ytmClient: YtmClient) : this(ytmClient, null)

    constructor(searcher: (String) -> List<YtmAlbumRef>) : this(null, searcher)

    /** The pages already found, by the album's name and artist: asked once, whatever taps or the background reading ask again. */
    private val foundRefs = ConcurrentHashMap<String, String>()
    private val normBrackets = Regex("[ᐸᐳ＜＞《》〈〉⟨⟩]")
    private val normSymbols = Regex("[^\\p{L}\\p{N}]+")
    private val unwantedWords = listOf("tribute", "karaoke", "cover", "instrumental", "parody", "lullaby")

    fun clear() {
        foundRefs.clear()
    }

    suspend fun find(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val cacheKey = title.trim().lowercase() + "\u0000" + artist.trim().lowercase()
        foundRefs[cacheKey]?.let { return@withContext it }
        // Titles are written with signs that are letters to the unicode and nothing to a search: "ᐸHyper-Egoᐳ".
        fun norm(s: String) = s.lowercase().replace(normBrackets, "").replace(normSymbols, " ").trim()
        val wantTitle = norm(title)
        val wantCredits = ArtistNames.split(artist)
        val wantArtist = norm(ArtistNames.primary(artist, beforeAmpersand = true))

        val searchFunc: (String) -> List<YtmAlbumRef> = searcher ?: { q ->
            ytmClient?.let { client -> runCatching { client.searchAlbums(q) }.getOrNull() }.orEmpty()
        }

        // The name as written, then cleaned of its signs ("<Hyper-Ego>"), then with the artist's first name only.
        val found = listOf("${norm(title)} ${norm(artist)}", "$title $artist", "$wantTitle $wantArtist").distinct()
            .firstNotNullOfOrNull { q -> searchFunc(q).takeIf { it.isNotEmpty() } }.orEmpty()

        fun isUnwanted(albumTitle: String): Boolean {
            val low = albumTitle.lowercase()
            if (CompilationTitles.matches(albumTitle)) return true
            return unwantedWords.any { low.contains(it) && !wantTitle.contains(it) }
        }

        fun artistMatches(a: YtmAlbumRef): Boolean {
            val got = norm(a.artist)
            if (got.isEmpty() || wantArtist.isEmpty()) return false
            val aCredits = ArtistNames.split(a.artist)
            return ArtistNames.anyCompatible(wantCredits, aCredits) || got == wantArtist ||
                ArtistNames.key(a.artist) == ArtistNames.key(artist)
        }

        val filtered = found.filter { a -> !isUnwanted(a.title) && artistMatches(a) }

        // The exact album only: a title that is a prefix of, or contains, the wanted one — "Greatest Hits"
        // vs "Greatest Hits (Deluxe Edition)", "TiK ToK" vs "TiK ToK - Remixes" — is a different record
        // (an edition, a remix, a reissue) and must not open in its place.
        val exact = filtered.firstOrNull { a -> norm(a.title) == wantTitle }
        exact?.id?.also { foundRefs[cacheKey] = it }
    }
}
