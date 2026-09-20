package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.MusicRepository
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

/** See [LibraryUiState]'s doc comment — same `List<T>` stability-inference issue applies here. */
@Immutable
data class ArtistUiState(
    val name: String = "",
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
)

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: MusicRepository,
) : ViewModel() {

    private val artistId: Long = savedStateHandle.get<String>("artistId")?.toLongOrNull() ?: -1L

    val uiState: StateFlow<ArtistUiState> = combine(
        repository.tracks,
        repository.albums,
    ) { tracks, albums ->
        val artistTracks = tracks.filter { it.artistId == artistId }
        val artistAlbums = albums.filter { it.artistId == artistId }.sortedByDescending { it.year }
        ArtistUiState(
            name = artistTracks.firstOrNull()?.artist ?: artistAlbums.firstOrNull()?.artist ?: "",
            albums = artistAlbums,
            tracks = artistTracks,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArtistUiState())
}
