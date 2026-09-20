package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.FavoritesStore
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.RecentlyPlayedTracker
import com.ikko.aura.data.model.Album
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** See [LibraryUiState]'s doc comment — same `List<T>` stability-inference issue applies here. */
@Immutable
data class AlbumListUiState(
    val title: String = "",
    val albums: List<Album> = emptyList(),
)

@HiltViewModel
class AlbumListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: MusicRepository,
    recentlyPlayedTracker: RecentlyPlayedTracker,
    favoritesStore: FavoritesStore,
) : ViewModel() {

    private val mode = savedStateHandle.get<String>("mode").orEmpty()

    val uiState: StateFlow<AlbumListUiState> = combine(
        repository.albums,
        recentlyPlayedTracker.recentAlbumIds,
        favoritesStore.favoriteAlbumIds,
        recentlyPlayedTracker.playCounts,
    ) { albums, recentIds, favoriteIds, plays ->
        val albumsById = albums.associateBy { it.id }
        when (mode) {
            "favorites" -> AlbumListUiState("Favoris", albums.filter { it.id in favoriteIds })
            "recent" -> AlbumListUiState("Écoutés récemment", recentIds.mapNotNull { albumsById[it] })
            // Same ordering Home's row uses, uncapped: least-played first. "Tout voir" on a row
            // about what you have forgotten should not reorder it into the plain album list.
            else -> {
                val countById = plays.associate { it.albumId to it.playCount }
                AlbumListUiState("Redécouvrir", albums.sortedBy { countById[it.id] ?: 0 })
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumListUiState())
}
