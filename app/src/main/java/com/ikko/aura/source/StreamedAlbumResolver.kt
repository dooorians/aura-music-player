package com.ikko.aura.source

import com.ikko.aura.data.model.Track
import com.ikko.aura.navigation.streamedAlbumRoute
import com.ikko.aura.reco.AlbumIdentity
import com.ikko.aura.reco.ArtistNames
import com.ikko.aura.reco.TrackIdentity
import com.ikko.aura.reco.authority.CompilationTitles
import com.ikko.aura.source.ytm.YtmClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Finds the album page of a streamed song.
 *
 * A song that came through its album carries the album's reference and that is the answer. One met any other way — a
 * recommendation, a "similar to" entry, a JioSaavn result — only has a name for its album (or, for a YouTube Music
 * video, a page that names it): the album is then looked up on YouTube Music by that name and its artist, and the page
 * opened is the album found there, if it is the same one. Null when there is no way to tell which album it is.
 */
@Singleton
class StreamedAlbumResolver internal constructor(
    private val ytm: YtmClient?,
    internal var isEnabledFunc: () -> Boolean = { ytm?.isEnabled() ?: false },
    internal var searchAlbumsFunc: (String) -> List<com.ikko.aura.source.ytm.YtmAlbumRef> = { ytm?.searchAlbums(it) ?: emptyList() },
    internal var detailsAlbumFunc: (String) -> String? = { ytm?.details(it)?.album },
) {
    @Inject
    constructor(ytm: YtmClient) : this(
        ytm,
        { ytm.isEnabled() },
        { ytm.searchAlbums(it) },
        { ytm.details(it).album },
    )

    /** Track id → the route found, or "" when none was (so a song with no album is not looked up at every tap). */
    private val found = ConcurrentHashMap<String, String>()
    private val unwantedWords = listOf("tribute", "karaoke", "cover", "instrumental", "parody", "lullaby")

    fun clear() {
        found.clear()
    }

    suspend fun routeOf(track: Track): String? {
        track.remoteAlbumId?.let { return streamedAlbumRoute(it) }
        found[track.id]?.let { return it.ifEmpty { null } }
        val route = lookUp(track)
        // Only a definite answer is kept: a failed lookup (no network) is tried again at the next tap.
        found[track.id] = route.orEmpty()
        return route
    }

    /** Starts the lookup for [track] now, so a tap on its title later finds the answer ready. Cheap when it is already known. */
    suspend fun warm(track: Track) {
        if (track.remoteAlbumId != null || found.containsKey(track.id)) return
        routeOf(track)
    }

    private suspend fun lookUp(track: Track): String? = coroutineScope {
        if (!isEnabledFunc()) return@coroutineScope null
        val artists = ArtistNames.creditsOf(track.artist, track.title)
        val placeholder = track.album.isBlank() || track.album.equals("YouTube Music", ignoreCase = true) ||
            track.album.equals(track.id.substringBefore(':'), ignoreCase = true) ||
            track.album.equals(track.title, ignoreCase = true)

        fun isUnwanted(albumTitle: String): Boolean {
            val low = albumTitle.lowercase()
            if (CompilationTitles.matches(albumTitle, track.title + " " + track.album)) return true
            return unwantedWords.any { low.contains(it) && !track.title.lowercase().contains(it) && !track.album.lowercase().contains(it) }
        }

        fun artistMatches(ref: com.ikko.aura.source.ytm.YtmAlbumRef): Boolean {
            if (ref.artist.isBlank()) return false
            val refCredits = ArtistNames.split(ref.artist)
            return ArtistNames.anyCompatible(artists, refCredits) ||
                ArtistNames.key(ref.artist) == ArtistNames.key(track.artist)
        }

        fun pick(refs: List<com.ikko.aura.source.ytm.YtmAlbumRef>, albumTitle: String): String? {
            // The same record under the same artist, in the edition asked for (2) or, failing that, with extras on it (1): a Live, an
            // Instrumental, a cover or a tribute is another record, and a title that merely starts like the wanted one is not it either.
            val best = refs.filter { ref -> !isUnwanted(ref.title) && artistMatches(ref) }
                .mapNotNull { ref -> AlbumIdentity.fit(albumTitle, ref.title)?.let { ref to it } }
                .maxByOrNull { it.second }?.first
            return best?.let { streamedAlbumRoute(it.id, online = true) }
        }

        // The slow part is the network, so what can be asked together is: the album search by the song's own name (it finds
        // the album of a single, and of a song whose album shares its title) at the same time as the song's page, which
        // names the album it is really on.
        val bySong = async(Dispatchers.IO) { runCatching { searchAlbumsFunc("${track.title} ${track.artist}") }.getOrNull().orEmpty() }
        val named = if (!placeholder) {
            track.album
        } else if (track.id.startsWith("ytm:")) {
            withContext(Dispatchers.IO) { runCatching { detailsAlbumFunc(track.id.substringAfter(':')) }.getOrNull() }
        } else null

        if (named.isNullOrBlank()) {
            // No name for the album: only an album that carries the song's own title can be told to be it.
            return@coroutineScope pick(bySong.await(), track.title)
        }
        // Often the same list answers (an album named like its song, or an album search that already contains it).
        pick(bySong.await(), named)?.let { return@coroutineScope it }
        val refs = withContext(Dispatchers.IO) { runCatching { searchAlbumsFunc("$named ${track.artist}") }.getOrNull().orEmpty() }
        pick(refs, named)
    }
}
