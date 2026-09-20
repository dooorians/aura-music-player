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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** See [LibraryUiState]'s doc comment — same `List<T>` stability-inference issue applies here. */
@Immutable
data class SearchUiState(
    val query: String = "",
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
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

    val uiState: StateFlow<SearchUiState> = combine(
        // Settled, not per-keystroke. Filtering the whole library on every character is work the
        // typist never sees the result of, and it is what made the round trip behind the old
        // field binding long enough to scramble the text. The field itself is no longer waiting
        // on this, so the only thing the wait costs now is how soon the results appear.
        query.debounce { if (it.isBlank()) 0L else FILTER_DEBOUNCE_MS },
        repository.tracks,
        repository.albums,
        repository.artists,
    ) { q, tracks, albums, artists ->
        if (q.isBlank()) {
            SearchUiState(query = q)
        } else {
            val needle = q.trim()
            SearchUiState(
                query = q,
                tracks = tracks.filter { it.title.contains(needle, ignoreCase = true) || it.artist.contains(needle, ignoreCase = true) }.take(30),
                albums = albums.filter { it.title.contains(needle, ignoreCase = true) || it.artist.contains(needle, ignoreCase = true) }.take(15),
                artists = artists.filter { it.name.contains(needle, ignoreCase = true) }.take(15),
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    private companion object {
        const val FILTER_DEBOUNCE_MS = 120L
    }
}
