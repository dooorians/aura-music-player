package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.AlbumPlayEntity
import com.ikko.aura.data.FavoritesStore
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.RecentlyPlayedTracker
import com.ikko.aura.data.SettingsStore
import com.ikko.aura.data.home.CardAmbience
import com.ikko.aura.data.home.HomeSourceData
import com.ikko.aura.data.home.HomeWidget
import com.ikko.aura.data.home.HomeWidgetResolver
import com.ikko.aura.data.home.HomeWidgetRules
import com.ikko.aura.data.home.WidgetContent
import com.ikko.aura.data.home.WidgetKind
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.PlaylistRepository
import com.ikko.aura.data.playlist.PlaylistSummary
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.source.ytm.YtmLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One "Pour toi" card: what it says, the mood it is dressed in, and what it plays or opens. */
@Immutable
data class ForYouCard(
    val id: String,
    val ambience: CardAmbience,
    val heading: String,
    val kicker: String,
    val artists: String,
    val content: WidgetContent,
)

@Immutable
data class ForYouUiState(
    /** False until the library has been read: nothing is drawn before that, rather than an empty Home. */
    val loaded: Boolean = false,
    val cards: List<ForYouCard> = emptyList(),
    /** The albums played lately, most recent first. */
    val recent: List<Album> = emptyList(),
    /** Every card there is, in the listener's order, hidden ones included: what the organiser shows. */
    val layout: List<CardSlot> = emptyList(),
)

@Immutable
data class CardSlot(val id: String, val heading: String, val hidden: Boolean)

/**
 * The state of the Home: the "Pour toi" cards, each one a selection of the library the resolver already knows how to
 * make (favourites, what has not been played lately, the most played, the day's mix, albums by the artists you play most),
 * and the albums played lately.
 */
@HiltViewModel
class HomeWidgetsViewModel @Inject constructor(
    repository: MusicRepository,
    recentlyPlayedTracker: RecentlyPlayedTracker,
    favoritesStore: FavoritesStore,
    ytmLibrary: YtmLibrary,
    private val playlistRepository: PlaylistRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val profileImageUri: StateFlow<String?> = settingsStore.profileImageUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Moves a card one place up (-1) or down (+1) in the organiser. */
    fun moveCard(id: String, delta: Int) {
        val ids = uiState.value.layout.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from < 0 || from == to) return
        ids.add(to, ids.removeAt(from))
        viewModelScope.launch { settingsStore.setHomeCardOrder(ids) }
    }

    fun setCardHidden(id: String, hidden: Boolean) {
        val now = uiState.value.layout.filter { it.hidden }.map { it.id }.toMutableSet()
        if (hidden) now += id else now -= id
        viewModelScope.launch { settingsStore.setHomeCardHidden(now) }
    }

    /** Saves the order the listener dropped the cards in. */
    fun setCardOrder(ids: List<String>) {
        viewModelScope.launch { settingsStore.setHomeCardOrder(ids) }
    }

    val uiState: StateFlow<ForYouUiState> = combine(
        repository.tracks,
        repository.albums,
        recentlyPlayedTracker.recentAlbumIds,
        favoritesStore.favoriteAlbumIds,
        recentlyPlayedTracker.playCounts,
        recentlyPlayedTracker.recentTrackIds,
        ytmLibrary.observeDoneKeys(),
        ytmLibrary.observeKept(),
        playlistRepository.playlists,
        settingsStore.homeCardOrder,
        settingsStore.homeCardHidden,
    ) { values ->
        @Suppress("UNCHECKED_CAST") val tracks = values[0] as List<Track>
        @Suppress("UNCHECKED_CAST") val albums = values[1] as List<Album>
        @Suppress("UNCHECKED_CAST") val recentAlbumIds = values[2] as List<Long>
        @Suppress("UNCHECKED_CAST") val favoriteIds = values[3] as Set<Long>
        @Suppress("UNCHECKED_CAST") val plays = values[4] as List<AlbumPlayEntity>
        @Suppress("UNCHECKED_CAST") val recentTrackIds = values[5] as List<String>
        @Suppress("UNCHECKED_CAST") val ytmDownloads = values[6] as List<String>
        @Suppress("UNCHECKED_CAST") val kept = values[7] as List<Track>
        @Suppress("UNCHECKED_CAST") val playlists = values[8] as List<PlaylistSummary>
        @Suppress("UNCHECKED_CAST") val order = values[9] as List<String>
        @Suppress("UNCHECKED_CAST") val hidden = values[10] as Set<String>

        // Streamed songs count as the library's own once played or kept, as on the old Home.
        // Streamed songs count only as kept ones (not everything ever played from a source), and only the downloaded among
        // them: one whose download was deleted is not a file any more, and its album used to linger in the recents.
        val downloadedKeys = ytmDownloads.toHashSet()
        val allTracks = (tracks + kept.filter { it.id in downloadedKeys }).distinctBy { it.id }
        val allAlbums = (albums + YtmLibrary.albumsOf(kept.filter { it.id in downloadedKeys })).distinctBy { it.id }
        val byId = allTracks.associateBy { it.id }
        val data = HomeSourceData(
            allTracks = allTracks,
            tracksByAlbum = allTracks.groupBy { it.albumId },
            albums = allAlbums,
            favoriteAlbums = allAlbums.filter { it.id in favoriteIds },
            recentTracks = recentTrackIds.mapNotNull { byId[it] },
            recentAlbumIds = recentAlbumIds.toSet(),
            albumPlays = plays.associate { it.albumId to it.playCount },
            playlists = playlists,
            day = System.currentTimeMillis() / MILLIS_PER_DAY,
        )
        val albumsById = allAlbums.associateBy { it.id }
        val ranked = CARDS.sortedBy { spec -> order.indexOf(spec.id).let { if (it < 0) Int.MAX_VALUE else it } }
        val built = ranked.mapNotNull { spec ->
                var content = if (spec.pick != null) {
                    val tracks = spec.pick.invoke(data).take(MIX_SIZE)
                    WidgetContent(title = spec.heading, subtitle = null, artwork = emptyList(), tracks = tracks, hue = 0f)
                } else {
                    HomeWidgetResolver.resolve(HomeWidget(spec.id, spec.kind!!), data)
                }
                if (spec.weekly && !content.isEmpty) {
                    // A real playlist, refilled once a week: the card opens it.
                    val id = playlistRepository.autoPlaylist(spec.id, spec.heading, System.currentTimeMillis()) { content.tracks }
                    content = content.copy(openRoute = AuraDestination.PlaylistPattern.route(id.toString()))
                }
                if (content.isEmpty) null else ForYouCard(spec.id, spec.ambience, spec.heading, spec.kicker, artistsOf(content.tracks), content)
        }
        ForYouUiState(
            loaded = true,
            cards = built.filter { it.id !in hidden },
            recent = recentAlbumIds.mapNotNull { albumsById[it] }.take(MAX_RECENT),
            layout = built.map { CardSlot(it.id, it.heading, it.id in hidden) },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForYouUiState())

    /** One card. [kind] is a selection the resolver knows; [pick] a selection made here, from the library's songs. */
    private class Spec(
        val id: String,
        val kind: WidgetKind?,
        val ambience: CardAmbience,
        val heading: String,
        val kicker: String,
        val weekly: Boolean = false,
        val pick: ((HomeSourceData) -> List<Track>)? = null,
    )

    private fun artistsOf(tracks: List<Track>): String {
        val names = tracks.map { it.artist.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return ""
        return names.take(3).joinToString(", ") + if (names.size > 3) " et plus" else ""
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val MAX_RECENT = 12
        const val MIX_SIZE = 30
        const val FOUR_MIN_MS = 4L * 60_000L
        const val THREE_MIN_MS = 3L * 60_000L
        val CARDS = listOf(
            Spec("fav", WidgetKind.FAVORITE_ALBUMS, CardAmbience.FAVORIS, "Favoris", "Fait pour toi"),
            Spec("disc", WidgetKind.REDISCOVER, CardAmbience.DECOUVERTE, "Découverte", "À redécouvrir", weekly = true),
            Spec("top", WidgetKind.TOP_PLAYED, CardAmbience.ENERGIE, "Les plus écoutés", "Ce que tu écoutes le plus", weekly = true),
            Spec("daily", WidgetKind.DAILY_MIX, CardAmbience.NUIT, "Mix du jour", "Ambiance du moment"),
            Spec("style", WidgetKind.FOR_YOU, CardAmbience.NOUVEAUTES, "Dans ton style", "D'après tes écoutes", weekly = true),
            // Chosen from what the files say (their length, their year), not from guesses about how they sound.
            Spec("night", null, CardAmbience.NUIT, "Nuit", "Des titres longs, pour la soirée", weekly = true) { d ->
                d.allTracks.filter { it.durationMs >= FOUR_MIN_MS }.sortedBy { HomeWidgetRules.dailyRank(it.id, d.day) }
            },
            Spec("energy", null, CardAmbience.ENERGIE, "Énergique", "Courts et directs", weekly = true) { d ->
                d.allTracks.filter { it.durationMs in 1L until THREE_MIN_MS }.sortedBy { HomeWidgetRules.dailyRank(it.id, d.day) }
            },
            Spec("new", null, CardAmbience.NOUVEAUTES, "Nouveautés", "Les plus récents de ta bibliothèque", weekly = true) { d ->
                d.allTracks.filter { it.year > 0 }.sortedWith(compareByDescending<Track> { it.year }.thenBy { HomeWidgetRules.dailyRank(it.id, d.day) })
            },
        )
    }
}
