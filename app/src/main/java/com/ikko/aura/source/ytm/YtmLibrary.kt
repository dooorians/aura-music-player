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
) {
    private fun entity(track: Track, inLibrary: Boolean) = CatalogTrackEntity(
        key = track.id,
        title = track.title,
        artist = track.artist,
        album = "",
        durationMs = track.durationMs,
        artworkUrl = track.artworkUri?.toString().orEmpty(),
        inLibrary = inLibrary,
    )

    private fun binding(track: Track) = TrackBindingEntity(
        YTM.value, track.id.substringAfter(':'), track.id, "", Availability.AVAILABLE.name, System.currentTimeMillis(),
    )

    /** Makes sure these streamed songs are known; already-kept ones stay kept. Anything that is not streamed is ignored. */
    suspend fun remember(tracks: List<Track>) {
        val streamed = tracks.filter(::isStreamed)
        if (streamed.isEmpty()) return
        dao.rememberItems(streamed.map { entity(it, inLibrary = false) }, streamed.map(::binding))
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
        combine(dao.observeSaved(), settings.ytmEnabled) { rows, on -> if (on) rows.map(::trackOf) else emptyList() }

    /** Every song the app knows, kept or not, while the source is on. */
    fun observeKnown(): Flow<List<Track>> =
        combine(dao.observeStreamed(), settings.ytmEnabled) { rows, on -> if (on) rows.map(::trackOf) else emptyList() }

    suspend fun known(): List<Track> = observeKnown().first()

    fun observeKeptIds(): Flow<Set<String>> = observeKept().map { list -> list.mapTo(HashSet()) { it.id } }

    suspend fun prune(now: Long = System.currentTimeMillis()) {
        dao.pruneStreamed(since = now - PRUNE_AFTER_MS)
        dao.pruneOrphanBindings()
    }

    private fun trackOf(row: CatalogTrackEntity): Track =
        trackOf(row.key.substringAfter(':'), row.title, row.artist, row.durationMs, row.artworkUrl)

    companion object {
        private const val PRUNE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

        fun isStreamed(track: Track) = isStreamedId(track.id)

        fun isStreamedId(id: String) = id.startsWith("${YTM.value}:")

        /**
         * Negative, so it can never be a real MediaStore album id, and one per song, because the screens key a cover on
         * the album id and two songs must not share a picture.
         */
        fun pseudoAlbumId(videoId: String): Long = -(videoId.hashCode().toLong() and 0x7fffffffL) - 1

        fun keyOf(videoId: String) = "${YTM.value}:$videoId"

        /** A search result as the player and the lists see it. Not in the library until [keep]t. */
        fun trackOf(song: YtmSong) = trackOf(song.videoId, song.title, song.artist, song.durationMs, song.thumbnailUrl)

        fun trackOf(videoId: String, title: String, artist: String, durationMs: Long, thumbnailUrl: String?) = Track(
            id = keyOf(videoId),
            title = title,
            artist = artist,
            album = "YouTube Music",
            artistId = 0L,
            albumId = pseudoAlbumId(videoId),
            artworkUri = thumbnailUrl?.takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse),
            contentUri = android.net.Uri.parse("$YTM_SCHEME://$videoId"),
            durationMs = durationMs,
        )
    }
}
