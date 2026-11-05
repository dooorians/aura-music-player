package com.ikko.aura.source.jiosaavn

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RawArtistMapItem(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class RawArtistMap(
    @SerialName("primary_artists") val primaryArtists: List<RawArtistMapItem> = emptyList(),
    @SerialName("featured_artists") val featuredArtists: List<RawArtistMapItem> = emptyList(),
)

@Serializable
data class RawMoreInfo(
    val album_id: String = "",
    val album: String = "",
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    val duration: String = "",
    @SerialName("320kbps") val has320: String = "",
    val artistMap: RawArtistMap = RawArtistMap(),
    /** "YYYY-MM-DD" when the catalogue knows it; most songs have only [RawSongItem.year]. */
    @SerialName("release_date") val releaseDate: String? = null,
) {
    val supports320: Boolean get() = has320.equals("true", ignoreCase = true)
}

@Serializable
data class RawSongItem(
    val id: String = "",
    val title: String = "",
    val image: String = "",
    val year: String = "",
    /** "english", "hindi", "instrumental"…: what the catalogue says the song is sung in. */
    val language: String = "",
    @SerialName("explicit_content") val explicitContent: String = "",
    @SerialName("more_info") val moreInfo: RawMoreInfo = RawMoreInfo()
) {
    val isExplicit: Boolean
        get() = explicitContent == "1" || explicitContent.equals("true", ignoreCase = true)
}

@Serializable
data class RawSearchResponse(
    val results: List<RawSongItem> = emptyList()
)

data class SaavnStream(
    val url: String,
    val kbps: Int?,
    val fallbackUrl: String? = null,
)
