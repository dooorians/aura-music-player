package com.ikko.aura.navigation

sealed class AuraDestination(val route: String) {
    /**
     * The top-level spaces, held together in one swipeable pager rather than as separate
     * destinations. They are a single entry on the back stack, which is also what makes
     * back from an album return to the tab you were on rather than to Home.
     */
    data object Tabs : AuraDestination("tabs")

    data object Home : AuraDestination("home")
    data object Library : AuraDestination("library")
    data object Settings : AuraDestination("settings")
    data object NowPlaying : AuraDestination("now_playing")
    data object Queue : AuraDestination("queue")
    data object Lyrics : AuraDestination("lyrics")
    data object Equalizer : AuraDestination("equalizer")

    data object AlbumPattern : AuraDestination("album/{albumId}") {
        fun route(albumId: String) = "album/$albumId"
    }

    data object ArtistPattern : AuraDestination("artist/{artistId}") {
        fun route(artistId: String) = "artist/$artistId"
    }

    data object PlaylistPattern : AuraDestination("playlist/{playlistId}") {
        fun route(playlistId: String) = "playlist/$playlistId"
    }

    /** An album on YouTube Music: [albumId] is its playlist id. */
    data object YtmAlbumPattern : AuraDestination("ytm_album/{albumId}") {
        fun route(albumId: String) = "ytm_album/${android.net.Uri.encode(albumId)}"
    }

    /** An artist on YouTube Music, by name. */
    data object YtmArtistPattern : AuraDestination("ytm_artist/{name}") {
        fun route(name: String) = "ytm_artist/${android.net.Uri.encode(name)}"
    }

    /** An album on an added source: the source's id, then the album's. */
    data object RemoteAlbumPattern : AuraDestination("remote_album/{source}/{albumId}") {
        fun route(source: String, albumId: String) = "remote_album/${android.net.Uri.encode(source)}/${android.net.Uri.encode(albumId)}"
    }

    /** An artist on an added source, by name. */
    data object RemoteArtistPattern : AuraDestination("remote_artist/{source}/{name}") {
        fun route(source: String, name: String) = "remote_artist/${android.net.Uri.encode(source)}/${android.net.Uri.encode(name)}"
    }

    /** The sources in priority order, dragged about. */
    data object SourceOrder : AuraDestination("source_order")

    data object AlbumListPattern : AuraDestination("album_list/{mode}") {
        fun route(mode: String) = "album_list/$mode"
    }

    companion object {
        /** Routes with no mini-player pill at all (their own full-bleed player UI). */
        /** Reached from the player and part of it: the mini player has no business on any of them. */
        val fullScreenRoutes = setOf(NowPlaying.route, Lyrics.route, Queue.route, Equalizer.route)

        /** The pager's pages, in swipe order. Search is not one: it opens over whichever page is showing. */
        val topLevelRoutes = listOf(Home, Library, Settings)
    }
}

/** Where an album's page is: a streamed album's on its source, a local one's in the library. */
fun com.ikko.aura.data.model.Album.route(): String =
    remoteId?.let { streamedAlbumRoute(it) } ?: AuraDestination.AlbumPattern.route(id.toString())

/** The page of a streamed album: [ref] is a YouTube Music playlist id, or `<source>:<albumId>` for an added source. */
fun streamedAlbumRoute(ref: String): String {
    val source = ref.substringBefore(':', "")
    return if (ref.contains(':')) AuraDestination.RemoteAlbumPattern.route(source, ref.substringAfter(':'))
    else AuraDestination.YtmAlbumPattern.route(ref)
}

/** The album page of a streamed song, if its album is known. */
fun com.ikko.aura.data.model.Track.streamedAlbumRoute(): String? = remoteAlbumId?.let { streamedAlbumRoute(it) }

/** The artist page of a streamed song: YouTube Music's by name, or the song's own source's by name. */
fun com.ikko.aura.data.model.Track.streamedArtistRoute(): String =
    if (id.startsWith("ytm:")) AuraDestination.YtmArtistPattern.route(artist)
    else AuraDestination.RemoteArtistPattern.route(id.substringBefore(':'), artist)
