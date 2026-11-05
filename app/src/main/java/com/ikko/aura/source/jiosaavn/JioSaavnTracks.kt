package com.ikko.aura.source.jiosaavn

import com.ikko.aura.data.model.AudioFormat
import com.ikko.aura.data.model.Track

/** A JioSaavn song as the lists and the player see it. */
object JioSaavnTracks {

    /** JioSaavn's text carries HTML entities ("&amp;", "&quot;"). */
    fun unescape(text: String): String = text
        .replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

    fun artistOf(res: RawSongItem): String =
        res.moreInfo.artistMap.primaryArtists.joinToString(", ") { unescape(it.name) }.ifBlank { "Unknown Artist" }

    fun trackOf(res: RawSongItem): Track {
        val thumbnail = res.image.replace(Regex("150x150|50x50"), "500x500").replace(Regex("^http://"), "https://")
        return Track(
            id = "jiosaavn:${res.id}",
            title = unescape(res.title),
            artist = artistOf(res),
            album = unescape(res.moreInfo.album).ifBlank { "" },
            artistId = 0L,
            albumId = jioSaavnAlbumId(res.moreInfo.album_id, res.id),
            artworkUri = android.net.Uri.parse(thumbnail),
            contentUri = android.net.Uri.Builder().scheme("jiosaavn").authority(res.id).build(),
            durationMs = res.moreInfo.duration.toLongOrNull()?.times(1000) ?: 0L,
            trackNumber = 0,
            year = res.year.toIntOrNull() ?: 0,
            format = AudioFormat.UNKNOWN,
            bitrateKbps = 320,
            sampleRateHz = 44100,
            bitDepth = 16,
        )
    }
}
