package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Artist
import com.ikko.aura.data.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

enum class SearchFilter { ALL, TRACKS, ALBUMS, ARTISTS }

@Immutable
data class SearchUiState(
    val query: String = "",
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val filter: SearchFilter = SearchFilter.ALL,
) {
    val isEmpty: Boolean get() = query.isBlank()
    val hasResults: Boolean get() = tracks.isNotEmpty() || albums.isNotEmpty() || artists.isNotEmpty()
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    repository: MusicRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(SearchFilter.ALL)

    val uiState: StateFlow<SearchUiState> = combine(
        query.debounce { if (it.isBlank()) 0L else FILTER_DEBOUNCE_MS },
        filter,
        repository.tracks,
        repository.albums,
        repository.artists
    ) { q, f, tracks, albums, artists ->
        if (q.isBlank()) {
            SearchUiState(query = q, filter = f)
        } else {
            val needle = q.trim()
            val filteredTracks = if (f == SearchFilter.ALL || f == SearchFilter.TRACKS) {
                tracks.asSequence().filter { it.title.contains(needle, ignoreCase = true) || it.artist.contains(needle, ignoreCase = true) }.take(30).toList()
            } else emptyList()
            
            val filteredAlbums = if (f == SearchFilter.ALL || f == SearchFilter.ALBUMS) {
                albums.asSequence().filter { it.title.contains(needle, ignoreCase = true) || it.artist.contains(needle, ignoreCase = true) }.take(15).toList()
            } else emptyList()
            
            val filteredArtists = if (f == SearchFilter.ALL || f == SearchFilter.ARTISTS) {
                artists.asSequence().filter { it.name.contains(needle, ignoreCase = true) }.take(15).toList()
            } else emptyList()

            SearchUiState(
                query = q,
                tracks = filteredTracks,
                albums = filteredAlbums,
                artists = filteredArtists,
                filter = f
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(value: String) {
        query.value = value
    }
    
    fun setFilter(newFilter: SearchFilter) {
        filter.value = newFilter
    }
    
    fun clearSearch() {
        query.value = ""
    }

    private companion object {
        const val FILTER_DEBOUNCE_MS = 120L
    }
}
