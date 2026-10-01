package com.ikko.aura.source.remote

/** A song as a remote source lists it. */
data class RemoteSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String?,
    val durationMs: Long,
    val coverId: String?,
    val suffix: String?,
    val contentType: String?,
    val trackNumber: Int,
    val year: Int,
)

data class RemoteAlbum(val id: String, val title: String, val artist: String, val coverId: String?, val year: Int, val songCount: Int)

data class RemoteArtist(val id: String, val name: String, val coverId: String?)

data class RemoteResults(val songs: List<RemoteSong>, val albums: List<RemoteAlbum>, val artists: List<RemoteArtist>)

data class RemoteAlbumPage(val album: RemoteAlbum, val songs: List<RemoteSong>)

/** One reachable source. All calls block: they run on an IO dispatcher. */
interface RemoteClient {
    /** Throws when the source cannot be reached or the credentials are refused. */
    fun ping()

    fun search(query: String): RemoteResults

    fun album(id: String): RemoteAlbumPage

    /** An artist's albums, found by [name] on this source. */
    fun artistAlbums(name: String): List<RemoteAlbum>

    /** The original file's address: no transcoding, so a FLAC is a FLAC. */
    fun streamUrl(songId: String): String

    /** A cover at [px], or null when the source has none. */
    fun coverUrl(coverId: String?, px: Int): String?
}
