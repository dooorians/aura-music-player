package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.AlbumPlayEntity
import com.ikko.aura.data.FavoritesStore
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.RecentlyPlayedTracker
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** See [LibraryUiState]'s doc comment — same `List<T>` stability-inference issue applies here. */
@Immutable
data class HomeUiState(
    val favoriteAlbums: List<Album> = emptyList(),
    val recentAlbums: List<Album> = emptyList(),
    val recentTracks: List<Track> = emptyList(),
    val rediscoverAlbums: List<Album> = emptyList(),
    val isScanning: Boolean = true,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val recentlyPlayedTracker: RecentlyPlayedTracker,
    private val favoritesStore: FavoritesStore,
    private val ytmLibrary: com.ikko.aura.source.ytm.YtmLibrary,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        repository.tracks,
        repository.albums,
        repository.isScanning,
        recentlyPlayedTracker.recentAlbumIds,
        favoritesStore.favoriteAlbumIds,
        recentlyPlayedTracker.playCounts,
        recentlyPlayedTracker.recentTrackIds,
        ytmLibrary.observeKnown(),
        ytmLibrary.observeKept(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val tracks = values[0] as List<Track>
        @Suppress("UNCHECKED_CAST")
        val albums = values[1] as List<Album>
        val isScanning = values[2] as Boolean
        @Suppress("UNCHECKED_CAST")
        val recentIds = values[3] as List<Long>
        @Suppress("UNCHECKED_CAST")
        val favoriteIds = values[4] as Set<Long>
        @Suppress("UNCHECKED_CAST")
        val plays = values[5] as List<AlbumPlayEntity>
        @Suppress("UNCHECKED_CAST")
        val recentTrackIds = values[6] as List<String>
        @Suppress("UNCHECKED_CAST")
        val streamed = values[7] as List<Track>
        @Suppress("UNCHECKED_CAST")
        val keptStreamed = values[8] as List<Track>

        // A streamed album is on the row once you have played it, and in the rediscovery pool once you have kept it.
        val playedStreamedAlbums = com.ikko.aura.source.ytm.YtmLibrary.albumsOf(streamed)
        val keptStreamedAlbums = com.ikko.aura.source.ytm.YtmLibrary.albumsOf(keptStreamed)
        val albumsById = (albums + playedStreamedAlbums).associateBy { it.id }
        HomeUiState(
            favoriteAlbums = albums.filter { it.id in favoriteIds },
            recentAlbums = recentIds.mapNotNull { albumsById[it] },
            // What was actually played, newest first — not simply the first tracks of the library.
            // Streamed songs count, kept or not (their ids resolve against what the app has met).
            recentTracks = (tracks + streamed).associateBy { it.id }.let { byId -> recentTrackIds.mapNotNull { byId[it] } },
            rediscoverAlbums = rediscover(albums + keptStreamedAlbums, plays, recentIds.toSet()),
            isScanning = isScanning,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * Albums you are not listening to, least-played first.
     *
     * Never-played ones come first — they all count zero — and among equals the order rotates
     * once a day rather than being fixed. A "redécouvrir" row that showed the same twelve albums
     * every time you opened the app would stop being a rediscovery after the first one, and
     * reshuffling on every recomposition would make it impossible to come back to something you
     * had just spotted.
     *
     * Whatever is in the session's recently-played row is excluded outright: it is on screen
     * directly above, and it is by definition not something you have forgotten.
     */
    private fun rediscover(
        albums: List<Album>,
        plays: List<AlbumPlayEntity>,
        recentIds: Set<Long>,
    ): List<Album> {
        val countById = plays.associate { it.albumId to it.playCount }
        val day = System.currentTimeMillis() / MILLIS_PER_DAY
        return albums
            .filterNot { it.id in recentIds }
            .sortedWith(
                compareBy<Album> { countById[it.id] ?: 0 }
                    .thenBy { (it.id * 2_654_435_761L + day).hashCode() },
            )
            .take(HOME_LIST_SIZE)
    }

    init {
        viewModelScope.launch { repository.refresh() }
    }

    companion object {
        private const val HOME_LIST_SIZE = 15
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
