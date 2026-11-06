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

    /** The grid of artist bubbles that calibrates the recommendations. */
    data object TastePicker : AuraDestination("taste_picker")

    /** What the algorithm learned and why: the transparency screen. */
    data object TasteProfile : AuraDestination("taste_profile")

    /** The artists the listener likes, and the ones they blocked: a page each, with their photos and a way to remove them. */
    data object LikedArtists : AuraDestination("liked_artists")
    data object BlockedArtists : AuraDestination("blocked_artists")

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
    data object YtmAlbumPattern : AuraDestination("ytm_album/{albumId}?online={online}") {
        /** [online] is the search's way in: the album as the source has it, whatever has been downloaded of it. */
        fun route(albumId: String, online: Boolean = false) =
            "ytm_album/${android.net.Uri.encode(albumId)}" + if (online) "?online=true" else ""
    }

    /** An artist on YouTube Music, by name. */
    data object YtmArtistPattern : AuraDestination("ytm_artist/{name}") {
        fun route(name: String) = "ytm_artist/${android.net.Uri.encode(name)}"
    }

    /** An album on an added source: the source's id, then the album's. */
    data object RemoteAlbumPattern : AuraDestination("remote_album/{source}/{albumId}?online={online}") {
        fun route(source: String, albumId: String, online: Boolean = false) =
            "remote_album/${android.net.Uri.encode(source)}/${android.net.Uri.encode(albumId)}" + if (online) "?online=true" else ""
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

    data object Playlists : AuraDestination("playlists")

    /** One of the settings' own pages (Apparence, Lecture…): [page] is the name of the page in the settings screen. */
    data object SettingsPagePattern : AuraDestination("settings/{page}") {
        fun route(page: String) = "settings/$page"
    }

    /** An artist's songs in their ranking (the most played, or the source's own order): [page] is the artist's name. */
    data object RankingPattern : AuraDestination("ranking/{page}") {
        fun route(name: String) = "ranking/${android.net.Uri.encode(name)}"
    }

    /** Everything one row of Home holds: [page] is the row's id. */
    data object HomeSectionPattern : AuraDestination("homesection/{page}") {
        fun route(sectionId: String) = "homesection/${android.net.Uri.encode(sectionId)}"
    }

    /** All the albums (or all the singles) of an artist: [page] is `albums:` or `singles:` and the artist's name. */
    data object ReleasesPattern : AuraDestination("releases/{page}") {
        fun route(kind: String, name: String) = "releases/${android.net.Uri.encode("$kind:$name")}"
    }

    /** The artists close to an artist: [page] is that artist's name. */
    data object SimilarPattern : AuraDestination("similar/{page}") {
        fun route(name: String) = "similar/${android.net.Uri.encode(name)}"
    }

    companion object {
        /** Routes with no mini-player pill at all (their own full-bleed player UI). */
        /** Reached from the player and part of it: the mini player has no business on any of them. */
        val fullScreenRoutes = setOf(NowPlaying.route, Lyrics.route, Queue.route, Equalizer.route, TastePicker.route)

        /** The pager's pages, in swipe order. Search is not one: it opens over whichever page is showing. */
        val topLevelRoutes = listOf(Home, Library)
    }
}

/** Where an album's page is: a streamed album's on its source, a local one's in the library. */
fun com.ikko.aura.data.model.Album.route(): String =
    remoteId?.let { streamedAlbumRoute(it) } ?: AuraDestination.AlbumPattern.route(id.toString())

/** The page of a streamed album: [ref] is a YouTube Music playlist id, or `<source>:<albumId>` for an added source. */
fun streamedAlbumRoute(ref: String, online: Boolean = false): String {
    val source = ref.substringBefore(':', "")
    return if (ref.contains(':')) AuraDestination.RemoteAlbumPattern.route(source, ref.substringAfter(':'), online)
    else AuraDestination.YtmAlbumPattern.route(ref, online)
}

/** The album page of a streamed song, if its album is known. */
fun com.ikko.aura.data.model.Track.streamedAlbumRoute(): String? = remoteAlbumId?.let { streamedAlbumRoute(it) }

/** The artist page of a streamed song: YouTube Music's by name, or the song's own source's by name. */
fun com.ikko.aura.data.model.Track.streamedArtistRoute(): String =
    if (id.startsWith("ytm:")) AuraDestination.YtmArtistPattern.route(artist)
    else AuraDestination.RemoteArtistPattern.route(id.substringBefore(':'), artist)

/**
 * The pages that belong to the settings: the hub, the pages it opens, and the pages those open. They move the way an album
 * page does (a trip from the touched row, the page rising round it, the way back the trip's own), in both directions.
 *
 * A new page of the settings is one of these as soon as its route starts with `settings/`, or is added to [SETTINGS_PAGE_ROUTES].
 * Then: wrap its body in `settingsPageBody()`, mark its title with `settingsPageTitle(...)`, and open it from a `SettingsRow(trip = ...)`
 * (see ui/screens/settings/SettingsMotion.kt).
 */
private val SETTINGS_PAGE_ROUTES = setOf(
    AuraDestination.Settings.route,
    AuraDestination.Equalizer.route,
    AuraDestination.SourceOrder.route,
    AuraDestination.TastePicker.route,
    AuraDestination.TasteProfile.route,
    AuraDestination.LikedArtists.route,
    AuraDestination.BlockedArtists.route,
)

fun isSettingsPageRoute(route: String?): Boolean =
    route != null && (route in SETTINGS_PAGE_ROUTES || route.startsWith("settings/") || route.startsWith("ranking/") || route.startsWith("similar/") || route.startsWith("releases/") || route.startsWith("homesection/"))

/**
 * The key of a trip to a settings page, from its route as it is navigated to (`settings/APPEARANCE`, `equalizer`…): what a row
 * that opens the page and the page's own title agree on.
 */
fun settingsTripKey(route: String): String = "settings:$route"

/**
 * The route of a settings-like page as a trip names it: the pattern's argument filled in (`settings/APPEARANCE`, `ranking/<artist>`),
 * the route itself for the pages that have none. [arg] reads a navigation argument by name.
 */
fun settingsTripPath(route: String, arg: (String) -> String?): String = when {
    route.startsWith("settings/") -> "settings/" + (arg("page") ?: "")
    route.startsWith("ranking/") -> "ranking/" + (arg("page") ?: "")
    route.startsWith("similar/") -> "similar/" + (arg("page") ?: "")
    route.startsWith("releases/") -> "releases/" + (arg("page") ?: "")
    route.startsWith("homesection/") -> "homesection/" + (arg("page") ?: "")
    else -> route
}
