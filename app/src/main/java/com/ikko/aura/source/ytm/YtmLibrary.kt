package com.ikko.aura.source.ytm

import com.ikko.aura.catalog.Availability
import com.ikko.aura.catalog.CatalogDao
import com.ikko.aura.catalog.CatalogTrackEntity
import com.ikko.aura.catalog.TrackBindingEntity
import com.ikko.aura.data.SettingsStore
import com.ikko.aura.data.model.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The streamed songs the app knows. Two tiers, one table: the ones the user *kept* (in the library, listed under
 * Titres), and the ones merely *met* — played, queued, put in a playlist or given a heart without being kept — which
 * are remembered so that a playlist, Favoris or a queue restored after a kill can still show them. A song met and
 * never used again is dropped after a while ([prune]).
 *
 * Everything here reads as empty while the source is switched off: the songs are not gone, they are not offered.
 */
@Singleton
class YtmLibrary @Inject constructor(
    private val dao: CatalogDao,
    private val settings: SettingsStore,
    private val downloads: com.ikko.aura.catalog.DownloadDao,
    private val remote: com.ikko.aura.source.remote.RemoteSources,
) {
    private fun entity(track: Track, inLibrary: Boolean) = CatalogTrackEntity(
        key = track.id,
        title = track.title,
        artist = track.artist,
        album = track.album.takeIf { track.remoteAlbumId != null }.orEmpty(),
        durationMs = track.durationMs,
        artworkUrl = track.artworkUri?.toString().orEmpty(),
        inLibrary = inLibrary,
        albumRef = track.remoteAlbumId.orEmpty(),
    )

    private fun binding(track: Track) = TrackBindingEntity(
        track.id.substringBefore(':'), track.id.substringAfter(':'), track.id, "", Availability.AVAILABLE.name, System.currentTimeMillis(),
    )

    /** Makes sure these streamed songs are known; already-kept ones stay kept. Anything that is not streamed is ignored. */
    suspend fun remember(tracks: List<Track>) {
        val streamed = tracks.filter(::isStreamed)
        if (streamed.isEmpty()) return
        dao.rememberItems(streamed.map { entity(it, inLibrary = false) }, streamed.map(::binding))
        // A song first met in a search and now played from its album learns which album it is on.
        streamed.filter { it.remoteAlbumId != null }.forEach { dao.attachAlbum(it.id, it.album, it.remoteAlbumId.orEmpty()) }
    }

    /** Puts these songs in the library. */
    suspend fun keep(tracks: List<Track>) {
        tracks.filter(::isStreamed).forEach { dao.saveItem(entity(it, inLibrary = true), binding(it)) }
    }

    /** Takes a song out of the library. Still known, so a playlist that holds it keeps it. */
    suspend fun unkeep(keys: Collection<String>) {
        keys.forEach { dao.setInLibrary(it, false) }
    }

    /** What the library lists: the kept songs, while the source is on. */
    fun observeKept(): Flow<List<Track>> =
        combine(dao.observeSaved(), settings.ytmEnabled, downloads.observeDoneKeys(), remote.enabledIds) { rows, on, done, others ->
            // Downloaded songs are files: they stay whatever the switch says.
            rows.filter { offered(it.key, on, others) || it.key in done }.map(::trackOf)
        }

    /** Every song the app knows, kept or not, while the source is on (and the downloaded ones always). */
    fun observeKnown(): Flow<List<Track>> =
        combine(dao.observeStreamed(), settings.ytmEnabled, downloads.observeDoneKeys(), remote.enabledIds) { rows, on, done, others ->
            rows.filter { offered(it.key, on, others) || it.key in done }.map(::trackOf)
        }

    suspend fun known(): List<Track> = observeKnown().first()

    /** The keys of the songs that are files of the device (downloaded). */
    fun observeDoneKeys(): Flow<List<String>> = downloads.observeDoneKeys()

    /**
     * What can be played without a network, from these songs: the device's own files and the downloaded ones. A song of
     * another source that was kept but whose file is gone is a stream, and is left out.
     */
    suspend fun withFiles(tracks: List<Track>): List<Track> {
        val done = downloads.observeDoneKeys().first().toHashSet()
        return tracks.filter { !isStreamed(it) || it.id in done }
    }

    /**
     * The albums of the songs that are downloaded, each with only its downloaded songs: what the library's Albums tab
     * lists beside the device's own. A song that is only streamed does not make an album there.
     */
    fun observeDownloadedAlbums(): Flow<List<com.ikko.aura.data.model.Album>> =
        combine(observeKnown(), downloads.observeDoneKeys()) { known, done ->
            val doneKeys = done.toHashSet()
            albumsOf(known.filter { it.id in doneKeys })
        }

    /**
     * What an album page can show without a network: the downloaded songs of the album [albumRef] (a playlist id, or
     * `<source>:<albumId>`), in album order. Files, so they are there whatever the source or the connection says.
     */
    suspend fun downloadedOfAlbum(albumRef: String): List<Track> {
        val done = downloads.observeDoneKeys().first().toHashSet()
        return observeKnown().first()
            .filter { it.id in done && it.remoteAlbumId == albumRef }
            .sortedBy { it.trackNumber }
    }

    fun observeKeptIds(): Flow<Set<String>> = observeKept().map { list -> list.mapTo(HashSet()) { it.id } }

    suspend fun prune(now: Long = System.currentTimeMillis()) {
        dao.pruneStreamed(since = now - PRUNE_AFTER_MS)
        dao.pruneOrphanBindings()
    }

    /** Whether a song's source is on: YouTube Music by its own switch, an added source by its own. */
    private fun offered(key: String, ytmOn: Boolean, others: Set<String>): Boolean =
        if (key.startsWith("${YTM.value}:")) ytmOn else key.substringBefore(':') in others

    private fun trackOf(row: CatalogTrackEntity): Track =
        if (!row.key.startsWith("${YTM.value}:")) {
            com.ikko.aura.source.remote.RemoteTracks.trackOf(
                row.key, row.title, row.artist, row.durationMs, row.artworkUrl,
                row.albumRef.ifEmpty { null }, row.album.ifEmpty { null },
            )
        } else trackOf(row.key.substringAfter(':'), row.title, row.artist, row.durationMs, row.artworkUrl, row.albumRef.ifEmpty { null }, row.album.ifEmpty { null })

    companion object {
        private const val PRUNE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

        /** The albums streamed songs belong to, as the lists draw them: one per album, from the songs known through it. */
        fun albumsOf(tracks: List<Track>): List<com.ikko.aura.data.model.Album> =
            tracks.filter { it.remoteAlbumId != null }
                .groupBy { it.remoteAlbumId }
                .map { (remote, songs) ->
                    val first = songs.first()
                    com.ikko.aura.data.model.Album(
                        id = pseudoAlbumId(remote.orEmpty()),
                        title = first.album,
                        artist = first.artist,
                        artistId = 0L,
                        artworkUri = first.artworkUri,
                        trackCount = songs.size,
                        year = 0,
                        remoteId = remote,
                    )
                }

        fun isStreamed(track: Track) = isStreamedId(track.id)

        /** A song of any source but the device: its id names the source (`ytm:…`, `sub1a2b3c:…`); a local one is bare. */
        fun isStreamedId(id: String) = id.contains(':')

        /**
         * Negative, so it can never be a real MediaStore album id, and one per song, because the screens key a cover on
         * the album id and two songs must not share a picture.
         */
        fun pseudoAlbumId(videoId: String): Long = -(videoId.hashCode().toLong() and 0x7fffffffL) - 1

        fun keyOf(videoId: String) = "${YTM.value}:$videoId"

        /** A search result as the player and the lists see it. Not in the library until [keep]t. */
        fun trackOf(song: YtmSong) =
            trackOf(song.videoId, song.title, song.artist, song.durationMs, song.thumbnailUrl, song.albumId, song.albumTitle)

        fun trackOf(
            videoId: String,
            title: String,
            artist: String,
            durationMs: Long,
            thumbnailUrl: String?,
            albumId: String? = null,
            albumTitle: String? = null,
        ) = Track(
            id = keyOf(videoId),
            title = title,
            artist = artist,
            album = albumTitle ?: "YouTube Music",
            artistId = 0L,
            // One id for the whole album when the song is known through it, so its songs share a cover and a page.
            albumId = pseudoAlbumId(albumId ?: videoId),
            // Songs kept before covers were requested at full size are upgraded here, as they are read.
            artworkUri = YtmImages.sized(thumbnailUrl)?.takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse),
            contentUri = android.net.Uri.parse("$YTM_SCHEME://$videoId"),
            durationMs = durationMs,
            remoteAlbumId = albumId,
        )
    }
}
