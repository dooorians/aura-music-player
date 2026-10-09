package com.ikko.aura.data.home

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.PlaylistSummary
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.navigation.route

/** A cover a widget can draw: the picture and the key it is cached under (one per album, not per song). */
@Immutable
data class WidgetArt(val uri: Uri?, val cacheKey: String)

/**
 * What one widget has to show, whatever it is a widget of: the card draws this and nothing else, so a new kind of widget
 * is a new branch in [HomeWidgetResolver] and no change to the UI.
 *
 * [openRoute] is the page the card opens when tapped; a widget with none (a mix, a category) plays instead.
 * [startIndex] is where playback starts in [tracks]. [hint] is what the card says when it has nothing to play.
 */
@Immutable
data class WidgetContent(
    val title: String,
    val subtitle: String?,
    val artwork: List<WidgetArt>,
    val tracks: List<Track>,
    val startIndex: Int = 0,
    val openRoute: String? = null,
    val hint: String? = null,
    val hue: Float,
    val isCarousel: Boolean = false,
    /** Path of the GIF a [WidgetKind.GIF] widget plays; the card then draws nothing else. */
    val gif: String? = null,
) {
    val isEmpty: Boolean get() = tracks.isEmpty()
}

/** Everything the resolver may draw on, gathered once per change of the library so no widget re-derives it. */
@Immutable
data class HomeSourceData(
    val allTracks: List<Track> = emptyList(),
    val tracksByAlbum: Map<Long, List<Track>> = emptyMap(),
    val albums: List<Album> = emptyList(),
    val favoriteAlbums: List<Album> = emptyList(),
    val recentTracks: List<Track> = emptyList(),
    val recentAlbumIds: Set<Long> = emptySet(),
    val albumPlays: Map<Long, Int> = emptyMap(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val playlistTracks: Map<Long, List<Track>> = emptyMap(),
    /** Whole days since the epoch: what the daily selections rotate on. */
    val day: Long = 0L,
)

/** The rules that need no Android types, apart from the resolver so they can be checked on their own. */
object HomeWidgetRules {
    /** Songs in a queue a widget hands to the player: enough to listen to, small enough to start at once. */
    const val MAX_QUEUE = 100
    const val MAX_ART = 4
    private val HUES = floatArrayOf(252f, 200f, 338f, 152f, 28f, 292f)

    /** The same order all day for the same ids, a new one tomorrow: a selection that changes but never flickers. */
    fun dailyRank(id: String, day: Long): Int = (id.hashCode().toLong() * 2_654_435_761L + day * 40_503L).hashCode()

    /** A hue picked from a short list of tints, by what the widget shows: stable between launches, never random. */
    fun hueFor(kindKey: String, arg: String?): Float =
        HUES[((kindKey + arg.orEmpty()).hashCode() and Int.MAX_VALUE) % HUES.size]

    fun countLabel(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
}

/**
 * Turns a configured [HomeWidget] into the [WidgetContent] it shows, from the current library. Pure: the same widget and
 * data always give the same content, so it can be re-run on every change and compared.
 */
object HomeWidgetResolver {

    fun resolve(widget: HomeWidget, data: HomeSourceData): WidgetContent {
        val hue = HomeWidgetRules.hueFor(widget.kind.key, widget.arg)
        return when (widget.kind) {
            WidgetKind.FAVORITE_ALBUMS -> fromAlbums(
                title = widget.kind.title,
                albums = data.favoriteAlbums,
                data = data,
                countNoun = "album" to "albums",
                openRoute = AuraDestination.AlbumListPattern.route("favorites"),
                emptyHint = "Marque un album favori (★) pour le retrouver ici",
                hue = hue,
                carousel = true,
            )

            WidgetKind.RECENT_TRACKS -> fromTracks(
                title = widget.kind.title,
                tracks = data.recentTracks,
                openRoute = AuraDestination.AlbumListPattern.route("recent"),
                emptyHint = "Les derniers titres écoutés apparaîtront ici",
                hue = hue,
            )

            WidgetKind.RESUME -> resume(widget, data, hue)

            WidgetKind.REDISCOVER -> fromAlbums(
                title = widget.kind.title,
                albums = rediscover(data).take(8),
                data = data,
                countNoun = "album" to "albums",
                openRoute = AuraDestination.AlbumListPattern.route("discover"),
                emptyHint = "Rien à redécouvrir pour l'instant",
                hue = hue,
            )

            WidgetKind.TOP_PLAYED -> fromAlbums(
                title = widget.kind.title,
                albums = data.albums
                    .filter { (data.albumPlays[it.id] ?: 0) > 0 }
                    .sortedByDescending { data.albumPlays[it.id] ?: 0 }
                    .take(8),
                data = data,
                countNoun = "album" to "albums",
                openRoute = AuraDestination.AlbumListPattern.route("top"),
                emptyHint = "Écoute quelques albums pour voir tes préférés",
                hue = hue,
                subtitleOverride = "",
            )

            WidgetKind.DAILY_MIX -> {
                val mix = data.allTracks.sortedBy { HomeWidgetRules.dailyRank(it.id, data.day) }.take(30)
                fromTracks(
                    title = widget.kind.title,
                    tracks = mix,
                    openRoute = null,
                    emptyHint = "Ajoute de la musique pour avoir une sélection",
                    hue = hue,
                    subtitleOverride = if (mix.isEmpty()) null else "Mix du jour · ${mix.size} titres",
                )
            }

            WidgetKind.FOR_YOU -> fromAlbums(
                title = widget.kind.title,
                albums = forYou(data),
                data = data,
                countNoun = "album" to "albums",
                openRoute = null,
                emptyHint = "Écoute un peu de musique pour des suggestions",
                hue = hue,
                subtitleOverride = "D'après tes écoutes",
            )

            WidgetKind.CATEGORY -> {
                val tracks = data.allTracks
                    .filter { HomeCategory.matches(widget.arg, it.isHiRes, it.isLossless, it.year) }
                    .sortedBy { HomeWidgetRules.dailyRank(it.id, data.day) }
                fromTracks(
                    title = HomeCategory.title(widget.arg),
                    tracks = tracks,
                    openRoute = null,
                    emptyHint = "Aucun titre dans cette catégorie",
                    hue = hue,
                )
            }

            WidgetKind.ALBUM -> {
                val album = data.albums.firstOrNull { it.id.toString() == widget.arg }
                if (album == null) {
                    missing("Album introuvable", "Il n'est plus dans la bibliothèque", hue)
                } else {
                    WidgetContent(
                        title = album.title.ifBlank { "Album sans titre" },
                        subtitle = album.artist.ifBlank { null },
                        artwork = listOf(WidgetArt(album.artworkUri, album.id.toString())),
                        tracks = data.tracksByAlbum[album.id].orEmpty().sortedBy { it.trackNumber }
                            .take(HomeWidgetRules.MAX_QUEUE),
                        openRoute = album.route(),
                        hint = "Cet album n'a aucun titre",
                        hue = hue,
                    )
                }
            }

            WidgetKind.GIF -> WidgetContent(
                title = "GIF", subtitle = null, artwork = emptyList(), tracks = emptyList(), hue = hue,
                hint = if (widget.arg?.let { java.io.File(it).exists() } == true) null else "GIF introuvable",
                gif = widget.arg?.takeIf { java.io.File(it).exists() },
            )

            WidgetKind.PLAYLIST -> {
                val id = widget.arg?.toLongOrNull()
                val playlist = data.playlists.firstOrNull { it.id == id }
                if (playlist == null) {
                    missing("Playlist introuvable", "Elle a été supprimée", hue)
                } else {
                    val tracks = data.playlistTracks[playlist.id].orEmpty().take(HomeWidgetRules.MAX_QUEUE)
                    WidgetContent(
                        title = playlist.name.ifBlank { "Playlist" },
                        subtitle = HomeWidgetRules.countLabel(playlist.trackCount, "titre", "titres"),
                        artwork = artOf(tracks),
                        tracks = tracks,
                        openRoute = AuraDestination.PlaylistPattern.route(playlist.id.toString()),
                        hint = "Cette playlist est vide",
                        hue = hue,
                    )
                }
            }
        }
    }

    private fun missing(title: String, hint: String, hue: Float) = WidgetContent(
        title = title, subtitle = null, artwork = emptyList(), tracks = emptyList(), hint = hint, hue = hue,
    )

    private fun artOf(tracks: List<Track>): List<WidgetArt> = tracks
        .distinctBy { it.albumId }
        .take(HomeWidgetRules.MAX_ART)
        .map { WidgetArt(it.artworkUri, it.albumId.toString()) }

    private fun fromTracks(
        title: String,
        tracks: List<Track>,
        openRoute: String?,
        emptyHint: String,
        hue: Float,
        subtitleOverride: String? = null,
    ): WidgetContent {
        val queue = tracks.take(HomeWidgetRules.MAX_QUEUE)
        return WidgetContent(
            title = title,
            subtitle = subtitleOverride ?: queue.takeIf { it.isNotEmpty() }
                ?.let { HomeWidgetRules.countLabel(it.size, "titre", "titres") },
            artwork = artOf(queue),
            tracks = queue,
            openRoute = openRoute.takeIf { queue.isNotEmpty() },
            hint = emptyHint,
            hue = hue,
        )
    }

    private fun fromAlbums(
        title: String,
        albums: List<Album>,
        data: HomeSourceData,
        countNoun: Pair<String, String>,
        openRoute: String?,
        emptyHint: String,
        hue: Float,
        subtitleOverride: String? = null,
        carousel: Boolean = false,
    ): WidgetContent {
        val queue = albums
            .flatMap { data.tracksByAlbum[it.id].orEmpty().sortedBy { t -> t.trackNumber } }
            .take(HomeWidgetRules.MAX_QUEUE)
        return WidgetContent(
            title = title,
            subtitle = subtitleOverride ?: albums.takeIf { it.isNotEmpty() }
                ?.let { HomeWidgetRules.countLabel(it.size, countNoun.first, countNoun.second) },
            artwork = albums.take(HomeWidgetRules.MAX_ART).map { WidgetArt(it.artworkUri, it.id.toString()) },
            tracks = queue,
            openRoute = openRoute.takeIf { albums.isNotEmpty() },
            hint = emptyHint,
            hue = hue,
            isCarousel = carousel,
        )
    }

    /** Back into the last song heard, with the rest of its album after it. */
    private fun resume(widget: HomeWidget, data: HomeSourceData, hue: Float): WidgetContent {
        val last = data.recentTracks.firstOrNull()
            ?: return WidgetContent(
                title = widget.kind.title, subtitle = null, artwork = emptyList(), tracks = emptyList(),
                hint = "Rien à reprendre pour l'instant", hue = hue,
            )
        val album = data.tracksByAlbum[last.albumId].orEmpty().sortedBy { it.trackNumber }
        val queue = if (album.any { it.id == last.id }) album else listOf(last)
        return WidgetContent(
            title = widget.kind.title,
            subtitle = listOf(last.title, last.artist).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
            artwork = listOf(WidgetArt(last.artworkUri, last.albumId.toString())),
            tracks = queue.take(HomeWidgetRules.MAX_QUEUE),
            startIndex = queue.indexOfFirst { it.id == last.id }.coerceAtLeast(0),
            hint = null,
            hue = hue,
        )
    }

    /** As Home has always rediscovered: albums not played lately, the least played first, rotating once a day. */
    private fun rediscover(data: HomeSourceData): List<Album> = data.albums
        .filterNot { it.id in data.recentAlbumIds }
        .sortedWith(
            compareBy<Album> { data.albumPlays[it.id] ?: 0 }
                .thenBy { (it.id * 2_654_435_761L + data.day).hashCode() },
        )

    /**
     * Albums by the artists the most played albums belong to, that were not heard lately; with no listening to go on, the
     * rediscovery. A suggestion drawn only from what the library and the listening history already say.
     */
    private fun forYou(data: HomeSourceData): List<Album> {
        val favouriteArtists = data.albums
            .filter { (data.albumPlays[it.id] ?: 0) > 0 }
            .groupBy { it.artistId }
            .mapValues { (_, albums) -> albums.sumOf { data.albumPlays[it.id] ?: 0 } }
            .entries.sortedByDescending { it.value }
            .take(3)
            .map { it.key }
        val fresh = data.albums.filterNot { it.id in data.recentAlbumIds }
        val byArtist = fresh.filter { it.artistId in favouriteArtists }
            .sortedBy { data.albumPlays[it.id] ?: 0 }
        return (byArtist.ifEmpty { rediscover(data) }).take(6)
    }
}
