package com.ikko.aura.source

import com.ikko.aura.data.model.Track
import com.ikko.aura.navigation.streamedAlbumRoute
import com.ikko.aura.reco.ArtistNames
import com.ikko.aura.reco.TrackIdentity
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
class StreamedAlbumResolver @Inject constructor(private val ytm: YtmClient) {

    /** Track id → the route found, or "" when none was (so a song with no album is not looked up at every tap). */
    private val found = ConcurrentHashMap<String, String>()

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
        if (!ytm.isEnabled()) return@coroutineScope null
        val artists = ArtistNames.creditsOf(track.artist, track.title)
        val placeholder = track.album.isBlank() || track.album.equals("YouTube Music", ignoreCase = true) ||
            track.album.equals(track.id.substringBefore(':'), ignoreCase = true)

        fun pick(refs: List<com.ikko.aura.source.ytm.YtmAlbumRef>, albumTitle: String): String? {
            val wanted = TrackIdentity.baseKey(albumTitle)
            val best = refs.firstOrNull { ref ->
                TrackIdentity.baseKey(ref.title) == wanted && ArtistNames.anyCompatible(artists, ArtistNames.split(ref.artist))
            } ?: refs.firstOrNull { ref -> TrackIdentity.baseKey(ref.title) == wanted }
            return best?.let { streamedAlbumRoute(it.id, online = true) }
        }

        // The slow part is the network, so what can be asked together is: the album search by the song's own name (it finds
        // the album of a single, and of a song whose album shares its title) at the same time as the song's page, which
        // names the album it is really on.
        val bySong = async(Dispatchers.IO) { runCatching { ytm.searchAlbums("${track.title} ${track.artist}") }.getOrNull().orEmpty() }
        val named = if (!placeholder) {
            track.album
        } else if (track.id.startsWith("ytm:")) {
            withContext(Dispatchers.IO) { runCatching { ytm.details(track.id.substringAfter(':')).album }.getOrNull() }
        } else null
        if (named.isNullOrBlank()) {
            // No name for the album: only an album that carries the song's own title can be told to be it.
            return@coroutineScope pick(bySong.await(), track.title)
        }
        // Often the same list answers (an album named like its song, or an album search that already contains it).
        pick(bySong.await(), named)?.let { return@coroutineScope it }
        val refs = withContext(Dispatchers.IO) { runCatching { ytm.searchAlbums("$named ${track.artist}") }.getOrNull().orEmpty() }
        pick(refs, named)
    }
}
