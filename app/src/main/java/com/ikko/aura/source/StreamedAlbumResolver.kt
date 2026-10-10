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
        val route = withContext(Dispatchers.IO) { lookUp(track) }
        found[track.id] = route.orEmpty()
        return route
    }

    private fun lookUp(track: Track): String? {
        if (!ytm.isEnabled()) return null
        // What names the album: the song's page for a YouTube Music video, else the title the song carries.
        val fromPage = if (track.id.startsWith("ytm:")) {
            runCatching { ytm.details(track.id.substringAfter(':')).album }.getOrNull()
        } else null
        val title = fromPage?.takeIf { it.isNotBlank() }
            ?: track.album.takeIf { it.isNotBlank() && !it.equals("YouTube Music", ignoreCase = true) && !it.equals(track.id.substringBefore(':'), ignoreCase = true) }
            ?: return null
        val artists = ArtistNames.creditsOf(track.artist, track.title)
        val wanted = TrackIdentity.baseKey(title)
        val refs = runCatching { ytm.searchAlbums("$title ${track.artist}") }.getOrNull().orEmpty()
        val best = refs.firstOrNull { ref ->
            TrackIdentity.baseKey(ref.title) == wanted &&
                ArtistNames.anyCompatible(artists, ArtistNames.split(ref.artist))
        } ?: refs.firstOrNull { ref -> TrackIdentity.baseKey(ref.title) == wanted }
        return best?.let { streamedAlbumRoute(it.id, online = true) }
    }
}
