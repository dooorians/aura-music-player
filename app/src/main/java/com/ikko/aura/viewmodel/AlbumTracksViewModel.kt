package com.ikko.aura.viewmodel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The library's songs grouped by album: what an album's long-press menu needs to know about the album's songs. */
@HiltViewModel
class AlbumTracksViewModel @Inject constructor(
    repository: MusicRepository,
    private val favoritesStore: com.ikko.aura.data.FavoritesStore,
) : ViewModel() {
    val favoriteAlbumIds: StateFlow<Set<Long>> = favoritesStore.favoriteAlbumIds

    fun toggleFavorite(albumId: Long) = favoritesStore.toggle(albumId)

    val byAlbum: StateFlow<Map<Long, List<Track>>> = repository.tracks
        .map { it.groupBy { track -> track.albumId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}

/** The songs of an album, as the library knows them now. */
@Composable
fun rememberAlbumTracks(viewModel: AlbumTracksViewModel = hiltViewModel()): (Long) -> List<Track> {
    val map = viewModel.byAlbum.collectAsState().value
    return { id -> map[id].orEmpty() }
}
