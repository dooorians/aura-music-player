package com.ikko.aura.source.ytm

import com.ikko.aura.catalog.Availability
import com.ikko.aura.catalog.CatalogDao
import com.ikko.aura.catalog.CatalogTrackEntity
import com.ikko.aura.catalog.TrackBindingEntity
import com.ikko.aura.data.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/** The songs the user kept from YouTube Music: a catalogue row and a binding, keyed `ytm:<videoId>`. */
@Singleton
class YtmLibrary @Inject constructor(private val dao: CatalogDao) {

    suspend fun save(song: YtmSong) {
        val key = keyOf(song.videoId)
        dao.saveItem(
            CatalogTrackEntity(key, song.title, song.artist, "", song.durationMs, song.thumbnailUrl.orEmpty()),
            TrackBindingEntity(YTM.value, song.videoId, key, "", Availability.AVAILABLE.name, System.currentTimeMillis()),
        )
    }

    suspend fun remove(key: String) = dao.forgetKeys(listOf(key))

    companion object {
        fun keyOf(videoId: String) = "${YTM.value}:$videoId"

        /** A search result as the player and the lists see it. Not in the library until [save]d. */
        fun trackOf(song: YtmSong) = trackOf(song.videoId, song.title, song.artist, song.durationMs, song.thumbnailUrl)

        fun trackOf(videoId: String, title: String, artist: String, durationMs: Long, thumbnailUrl: String?) = Track(
            id = keyOf(videoId),
            title = title,
            artist = artist,
            album = "YouTube Music",
            // Negative, so it can never be a real MediaStore album id, and one per song, because the screens key a
            // cover on the album id and two songs must not share a picture.
            artistId = 0L,
            albumId = -(videoId.hashCode().toLong() and 0x7fffffffL) - 1,
            artworkUri = thumbnailUrl?.takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse),
            contentUri = android.net.Uri.parse("$YTM_SCHEME://$videoId"),
            durationMs = durationMs,
        )
    }
}
