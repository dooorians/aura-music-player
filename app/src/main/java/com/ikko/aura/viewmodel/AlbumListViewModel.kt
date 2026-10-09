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
    ytmLibrary: com.ikko.aura.source.ytm.YtmLibrary,
) : ViewModel() {

    private val mode = savedStateHandle.get<String>("mode").orEmpty()

    val uiState: StateFlow<AlbumListUiState> = combine<Any?, AlbumListUiState>(
        repository.albums,
        recentlyPlayedTracker.recentAlbumIds,
        favoritesStore.favoriteAlbumIds,
        recentlyPlayedTracker.playCounts,
        ytmLibrary.observeKnown(),
        ytmLibrary.observeKept(),
    ) { values ->
        @Suppress("UNCHECKED_CAST") val albums = values[0] as List<Album>
        @Suppress("UNCHECKED_CAST") val recentIds = values[1] as List<Long>
        @Suppress("UNCHECKED_CAST") val favoriteIds = values[2] as Set<Long>
        @Suppress("UNCHECKED_CAST") val plays = values[3] as List<com.ikko.aura.data.AlbumPlayEntity>
        @Suppress("UNCHECKED_CAST") val known = values[4] as List<com.ikko.aura.data.model.Track>
        @Suppress("UNCHECKED_CAST") val kept = values[5] as List<com.ikko.aura.data.model.Track>
        val albumsById = (albums + com.ikko.aura.source.ytm.YtmLibrary.albumsOf(known)).associateBy { it.id }
        when (mode) {
            // As Home's row has it: a starred streamed album is a favourite like any other.
            "favorites" -> AlbumListUiState(
                "Albums favoris",
                (albums + com.ikko.aura.source.ytm.YtmLibrary.albumsOf((known + kept).distinctBy { it.id })).distinctBy { it.id }.filter { it.id in favoriteIds },
            )
            "top" -> {
                val countById = plays.associate { it.albumId to it.playCount }
                AlbumListUiState(
                    "Les plus écoutés",
                    albums.filter { (countById[it.id] ?: 0) > 0 }.sortedByDescending { countById[it.id] },
                )
            }
            "recent" -> AlbumListUiState("Écoutés récemment", recentIds.mapNotNull { albumsById[it] })
            // Same ordering Home's row uses, uncapped: least-played first. "Tout voir" on a row
            // about what you have forgotten should not reorder it into the plain album list.
            else -> {
                val countById = plays.associate { it.albumId to it.playCount }
                AlbumListUiState("Redécouvrir", (albums + com.ikko.aura.source.ytm.YtmLibrary.albumsOf(kept)).sortedBy { countById[it.id] ?: 0 })
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumListUiState())
}
