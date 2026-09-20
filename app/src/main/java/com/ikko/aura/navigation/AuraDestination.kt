package com.ikko.aura.navigation

sealed class AuraDestination(val route: String) {
    /**
     * The five top-level spaces, held together in one swipeable pager rather than as five
     * separate destinations. They are a single entry on the back stack, which is also what makes
     * back from an album return to the tab you were on rather than to Home.
     */
    data object Tabs : AuraDestination("tabs")

    data object Home : AuraDestination("home")
    data object Library : AuraDestination("library")
    data object Stats : AuraDestination("stats")
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

    data object AlbumListPattern : AuraDestination("album_list/{mode}") {
        fun route(mode: String) = "album_list/$mode"
    }

    companion object {
        /** Routes with no mini-player pill at all (their own full-bleed player UI). */
        val fullScreenRoutes = setOf(NowPlaying.route, Lyrics.route)

        /** The pager's pages, in swipe order. Search is not one: it is pulled down from any page. */
        val topLevelRoutes = listOf(Home, Library, Stats, Settings)
    }
}
