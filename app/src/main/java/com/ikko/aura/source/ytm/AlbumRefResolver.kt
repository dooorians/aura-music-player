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

    suspend fun find(rawTitle: String, rawArtist: String): String? = withContext(Dispatchers.IO) {
        // A source's text may carry HTML entities, sometimes escaped twice ("Kaskade &amp;amp; Tiësto"): a search for that finds nothing.
        val title = unescape(rawTitle)
        val artist = unescape(rawArtist)
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

        // The name as written, then cleaned of its signs ("<Hyper-Ego>"), then with the artist's first name only, then the title
        // alone: a search that names the artist can be answered by karaoke and tribute albums only, and the real one is
        // further down, or in the next search.
        val queries = listOf("${norm(title)} ${norm(artist)}", "$title $artist", "$wantTitle $wantArtist", wantTitle).distinct()

        fun isUnwanted(albumTitle: String): Boolean {
            val low = albumTitle.lowercase()
            if (CompilationTitles.matches(albumTitle, title)) return true
            return unwantedWords.any { low.contains(it) && !wantTitle.contains(it) }
        }

        fun artistMatches(a: YtmAlbumRef): Boolean {
            val got = norm(a.artist)
            if (got.isEmpty() || wantArtist.isEmpty()) return false
            val aCredits = ArtistNames.split(a.artist)
            return ArtistNames.anyCompatible(wantCredits, aCredits) || got == wantArtist ||
                ArtistNames.key(a.artist) == ArtistNames.key(artist)
        }

        // The exact album only: a title that is a prefix of, or contains, the wanted one — "Greatest Hits"
        // vs "Greatest Hits (Deluxe Edition)", "TiK ToK" vs "TiK ToK - Remixes" — is a different record
        // (an edition, a remix, a reissue) and must not open in its place.
        fun exactIn(found: List<YtmAlbumRef>): YtmAlbumRef? =
            found.filter { a -> !isUnwanted(a.title) && artistMatches(a) }.firstOrNull { a -> norm(a.title) == wantTitle }
                // The same title credited otherwise: "Lady Gaga" for what the source lists as "Tony Bennett & Lady Gaga", or the
                // reverse. Only when the credits share a whole name word, so "Greatest Hits" by someone else stays out.
                ?: found.firstOrNull { a -> !isUnwanted(a.title) && norm(a.title) == wantTitle && sharesNameWord(a.artist, artist, ::norm) }

        val exact = queries.firstNotNullOfOrNull { q -> exactIn(searchFunc(q)) }
        exact?.id?.also { foundRefs[cacheKey] = it }
    }

    private fun unescape(text: String): String {
        fun once(t: String) = t.replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        return once(once(text))
    }

    /** Whether two credits have a name word in common (three letters or more, and not a credit word like "feat"). */
    private fun sharesNameWord(a: String, b: String, norm: (String) -> String): Boolean {
        fun words(x: String) = norm(x).split(' ').filter { it.length >= 3 && it !in CREDIT_WORDS }.toSet()
        return words(a).intersect(words(b)).isNotEmpty()
    }

    private companion object {
        val CREDIT_WORDS = setOf("feat", "ft", "featuring", "and", "with", "the", "various", "artists", "topic", "vevo")
    }
}
