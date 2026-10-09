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
import com.ikko.aura.data.home.WidgetContent
import com.ikko.aura.data.home.WidgetKind
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.PlaylistRepository
import com.ikko.aura.data.playlist.PlaylistSummary
import com.ikko.aura.source.ytm.YtmLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

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
)

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
    playlistRepository: PlaylistRepository,
    settingsStore: SettingsStore,
) : ViewModel() {

    val profileImageUri: StateFlow<String?> = settingsStore.profileImageUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<ForYouUiState> = combine(
        repository.tracks,
        repository.albums,
        recentlyPlayedTracker.recentAlbumIds,
        favoritesStore.favoriteAlbumIds,
        recentlyPlayedTracker.playCounts,
        recentlyPlayedTracker.recentTrackIds,
        ytmLibrary.observeKnown(),
        ytmLibrary.observeKept(),
        playlistRepository.playlists,
    ) { values ->
        @Suppress("UNCHECKED_CAST") val tracks = values[0] as List<Track>
        @Suppress("UNCHECKED_CAST") val albums = values[1] as List<Album>
        @Suppress("UNCHECKED_CAST") val recentAlbumIds = values[2] as List<Long>
        @Suppress("UNCHECKED_CAST") val favoriteIds = values[3] as Set<Long>
        @Suppress("UNCHECKED_CAST") val plays = values[4] as List<AlbumPlayEntity>
        @Suppress("UNCHECKED_CAST") val recentTrackIds = values[5] as List<String>
        @Suppress("UNCHECKED_CAST") val streamed = values[6] as List<Track>
        @Suppress("UNCHECKED_CAST") val kept = values[7] as List<Track>
        @Suppress("UNCHECKED_CAST") val playlists = values[8] as List<PlaylistSummary>

        // Streamed songs count as the library's own once played or kept, as on the old Home.
        val allTracks = (tracks + streamed + kept).distinctBy { it.id }
        val allAlbums = (albums + YtmLibrary.albumsOf((streamed + kept).distinctBy { it.id })).distinctBy { it.id }
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
        ForYouUiState(
            loaded = true,
            cards = CARDS.mapNotNull { spec ->
                val content = HomeWidgetResolver.resolve(HomeWidget(spec.id, spec.kind), data)
                if (content.isEmpty) null else ForYouCard(spec.id, spec.ambience, spec.heading, spec.kicker, artistsOf(content.tracks), content)
            },
            recent = recentAlbumIds.mapNotNull { albumsById[it] }.take(MAX_RECENT),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForYouUiState())

    private class Spec(val id: String, val kind: WidgetKind, val ambience: CardAmbience, val heading: String, val kicker: String)

    private fun artistsOf(tracks: List<Track>): String {
        val names = tracks.map { it.artist }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return ""
        return names.take(5).joinToString(", ") + if (names.size > 5) " et plus encore" else ""
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val MAX_RECENT = 12
        val CARDS = listOf(
            Spec("fav", WidgetKind.FAVORITE_ALBUMS, CardAmbience.FAVORIS, "Favoris", "Fait pour toi"),
            Spec("disc", WidgetKind.REDISCOVER, CardAmbience.DECOUVERTE, "Découverte", "À redécouvrir"),
            Spec("top", WidgetKind.TOP_PLAYED, CardAmbience.ENERGIE, "Les plus écoutés", "Ce que tu écoutes le plus"),
            Spec("daily", WidgetKind.DAILY_MIX, CardAmbience.NUIT, "Mix du jour", "Ambiance du moment"),
            Spec("style", WidgetKind.FOR_YOU, CardAmbience.NOUVEAUTES, "Dans ton style", "D'après tes écoutes"),
        )
    }
}
