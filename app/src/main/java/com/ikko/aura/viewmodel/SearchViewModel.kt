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
import com.ikko.aura.data.SettingsStore
import com.ikko.aura.source.ytm.YtmClient
import com.ikko.aura.source.ytm.YtmLibrary
import com.ikko.aura.source.ytm.YtmSong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import org.schabi.newpipe.extractor.Page
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

/** What the YouTube Music section of the search shows. Nothing at all unless the source is on. */
@Immutable
data class YtmSearchState(
    val enabled: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: Boolean = false,
    /** Searched, and nothing came back. */
    val empty: Boolean = false,
    val hasMore: Boolean = false,
    val results: List<Track> = emptyList(),
    /** Ids of results already kept in the library. */
    val saved: Set<String> = emptySet(),
) {
    /** Whether the section has anything to say: a search in flight, results, a failure, or an honest "nothing". */
    val active: Boolean get() = enabled && (loading || error || empty || results.isNotEmpty())
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MusicRepository,
    settings: SettingsStore,
    private val ytmClient: YtmClient,
    private val ytmLibrary: YtmLibrary,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(SearchFilter.ALL)

    private val ytmSearch = MutableStateFlow(YtmSearchState())
    private var nextPage: Page? = null
    private var searchedQuery = ""
    private var ytmJob: Job? = null

    init {
        viewModelScope.launch {
            combine(settings.ytmEnabled, query.debounce { if (it.isBlank()) 0L else YTM_DEBOUNCE_MS }) { on, q -> on to q.trim() }
                .distinctUntilChanged()
                .collect { (on, q) ->
                    ytmJob?.cancel()
                    nextPage = null
                    if (!on || q.length < 2) {
                        ytmSearch.value = YtmSearchState(enabled = on)
                        return@collect
                    }
                    searchedQuery = q
                    ytmSearch.value = YtmSearchState(enabled = true, loading = true)
                    ytmJob = launch {
                        val page = runCatching { withContext(Dispatchers.IO) { ytmClient.search(q) } }
                        nextPage = page.getOrNull()?.next
                        val songs = page.getOrNull()?.songs.orEmpty()
                        ytmSearch.value = YtmSearchState(
                            enabled = true,
                            error = page.isFailure,
                            empty = page.isSuccess && songs.isEmpty(),
                            hasMore = nextPage != null,
                            results = songs.map(YtmLibrary::trackOf),
                        )
                    }
                }
        }
    }

    val ytmState: StateFlow<YtmSearchState> = combine(ytmSearch, ytmLibrary.observeKeptIds()) { state, kept ->
        state.copy(saved = kept)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YtmSearchState())

    /** The next page of the same search, added under the results already there. */
    fun loadMoreYtm() {
        val page = nextPage ?: return
        if (ytmSearch.value.loadingMore || ytmSearch.value.loading) return
        val q = searchedQuery
        ytmSearch.update { it.copy(loadingMore = true, error = false) }
        ytmJob = viewModelScope.launch {
            val more = runCatching { withContext(Dispatchers.IO) { ytmClient.searchMore(q, page) } }
            if (q != searchedQuery) return@launch // The query moved on while this was on its way.
            nextPage = more.getOrNull()?.next ?: if (more.isFailure) page else null
            ytmSearch.update { state ->
                val known = state.results.mapTo(HashSet()) { it.id }
                val added = more.getOrNull()?.songs.orEmpty().map(YtmLibrary::trackOf).filter { it.id !in known }
                state.copy(
                    loadingMore = false,
                    error = more.isFailure,
                    hasMore = nextPage != null,
                    results = state.results + added,
                )
            }
        }
    }

    fun saveYtm(track: Track) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { ytmLibrary.keep(listOf(track)) } }
    }

    fun removeYtm(track: Track) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { ytmLibrary.unkeep(listOf(track.id)) } }
    }

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
        const val YTM_DEBOUNCE_MS = 700L
    }
}
