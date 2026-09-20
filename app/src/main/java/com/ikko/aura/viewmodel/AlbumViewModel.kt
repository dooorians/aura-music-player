package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.FavoritesStore
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.model.Track
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
data class AlbumUiState(
    val title: String = "",
    val artist: String = "",
    val tracks: List<Track> = emptyList(),
    val isFavorite: Boolean = false,
)

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: MusicRepository,
    private val favoritesStore: FavoritesStore,
) : ViewModel() {

    private val albumId: Long = savedStateHandle.get<String>("albumId")?.toLongOrNull() ?: -1L

    val uiState: StateFlow<AlbumUiState> = combine(
        repository.tracks,
        favoritesStore.favoriteAlbumIds,
    ) { tracks, favoriteIds ->
        val albumTracks = tracks
            .filter { it.albumId == albumId }
            .sortedBy { it.trackNumber }
        AlbumUiState(
            title = albumTracks.firstOrNull()?.album ?: "",
            artist = albumTracks.firstOrNull()?.artist ?: "",
            tracks = albumTracks,
            isFavorite = albumId in favoriteIds,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumUiState())

    fun toggleFavorite() = favoritesStore.toggle(albumId)
}
