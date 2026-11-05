package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.util.runCatchingCancellable
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Artist
import com.ikko.aura.data.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.ikko.aura.data.SettingsStore
import com.ikko.aura.source.ytm.YtmAlbumRef
import com.ikko.aura.source.ytm.YtmArtistRef
import com.ikko.aura.source.ytm.YtmClient
import com.ikko.aura.source.ytm.YtmLibrary
import com.ikko.aura.source.ytm.YtmSong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import org.schabi.newpipe.extractor.Page
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

enum class SearchFilter { ALL, TRACKS, ALBUMS, ARTISTS }

/** Where the search looks: the library, the sources, or both. */
enum class SearchScope { LOCAL, ONLINE, ALL }

/** One online source the search can use, and whether it is on. */
@Immutable
data class SearchSource(val id: String, val name: String, val enabled: Boolean)

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
    val albums: List<YtmAlbumRef> = emptyList(),
    val artists: List<YtmArtistRef> = emptyList(),
    /** Ids of results already kept in the library. */
    val saved: Set<String> = emptySet(),
) {
    /** Whether the section has anything to say: a search in flight, results, a failure, or an honest "nothing". */
    val active: Boolean get() = enabled && (loading || error || empty || results.isNotEmpty() || albums.isNotEmpty() || artists.isNotEmpty())
}

/** What one added source answered to the search. */
@Immutable
data class RemoteSection(
    val sourceId: String,
    val name: String,
    val loading: Boolean = true,
    val error: Boolean = false,
    val songs: List<Track> = emptyList(),
    val albums: List<YtmAlbumRef> = emptyList(),
    val artists: List<String> = emptyList(),
) {
    val hasContent: Boolean get() = loading || error || songs.isNotEmpty() || albums.isNotEmpty() || artists.isNotEmpty()
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val settings: SettingsStore,
    private val ytmClient: YtmClient,
    private val ytmLibrary: YtmLibrary,
    private val remoteSources: com.ikko.aura.source.remote.RemoteSources,
    private val jioSaavnClient: com.ikko.aura.source.jiosaavn.JioSaavnClient,
    order: com.ikko.aura.source.SourceOrder,
) : ViewModel() {

    /** The sources in the priority the user gave them. */
    val sourceOrder: StateFlow<List<String>> = order.order
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf("local", "ytm"))

    private val remoteSearch = MutableStateFlow<List<RemoteSection>>(emptyList())
    private val jioSaavnSearch = MutableStateFlow<RemoteSection?>(null)

    /** The sources the user added, each with what it found: first on the page, as they are the ones chosen. */

    val remoteState: StateFlow<List<RemoteSection>> = combine(remoteSearch, jioSaavnSearch) { remote, jiosaavn ->
        if (jiosaavn != null) listOf(jiosaavn) + remote else remote
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())




    private var remoteJob: Job? = null
    private var jioSaavnJob: Job? = null

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(SearchFilter.ALL)

    private val ytmSearch = MutableStateFlow(YtmSearchState())
    private var nextPage: Page? = null
    private var searchedQuery = ""
    private var ytmJob: Job? = null
    private val ytmSideJobs = mutableListOf<Job>()

    init {
        viewModelScope.launch {
            query.debounce { if (it.isBlank()) 0L else YTM_DEBOUNCE_MS }
                .distinctUntilChanged()
                .collect { rawQuery ->
                    jioSaavnJob?.cancel()
                    val q = rawQuery.trim()
                    if (q.length < 2) {
                        jioSaavnSearch.value = null
                        return@collect
                    }
                    jioSaavnSearch.value = RemoteSection("jiosaavn", "JioSaavn", loading = true)
                    jioSaavnJob = launch {
                        try {
                            val tracks = withContext(Dispatchers.IO) {
                                jioSaavnClient.searchSongsOrThrow(q).map { res ->
                                    val artistName = res.moreInfo.artistMap.primaryArtists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" }
                                    val durationMillis = res.moreInfo.duration.toLongOrNull()?.times(1000) ?: 0L
                                    val thumbnail = res.image.replace(Regex("150x150|50x50"), "500x500").replace(Regex("^http://"), "https://")
                                    Track(
                                        id = "jiosaavn:${res.id}",
                                        title = res.title,
                                        artist = artistName,
                                        album = res.moreInfo.album.ifBlank { "" },
                                        artistId = 0L,
                                        albumId = com.ikko.aura.source.jiosaavn.jioSaavnAlbumId(res.moreInfo.album_id, res.id),
                                        artworkUri = android.net.Uri.parse(thumbnail),
                                        contentUri = android.net.Uri.Builder().scheme("jiosaavn").authority(res.id).build(),
                                        durationMs = durationMillis,
                                        trackNumber = 0,
                                        year = 0,
                                        format = com.ikko.aura.data.model.AudioFormat.UNKNOWN,
                                        bitrateKbps = 320,
                                        sampleRateHz = 44100,
                                        bitDepth = 16
                                    )
                                }
                            }
                            jioSaavnSearch.value = RemoteSection("jiosaavn", "JioSaavn", loading = false, songs = tracks)
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A request that failed after this search was replaced throws its own error, not a
                            // cancellation: without this, the old query's error landed over the new query's songs.
                            ensureActive()
                            jioSaavnSearch.value = RemoteSection("jiosaavn", "JioSaavn", loading = false, error = true)
                        }
                    }
                }
        }

        viewModelScope.launch {
            combine(remoteSources.configs, query.debounce { if (it.isBlank()) 0L else YTM_DEBOUNCE_MS }) { configs, q -> configs.filter { it.enabled }.map { it.id } to q.trim() }
                .distinctUntilChanged()
                .collect { (ids, q) ->
                    remoteJob?.cancel()
                    if (q.length < 2 || ids.isEmpty()) {
                        remoteSearch.value = emptyList()
                        return@collect
                    }
                    remoteSearch.value = ids.map { RemoteSection(it, remoteSources.nameOf(it)) }
                    remoteJob = launch {
                        ids.forEach { id ->
                            launch {
                                val client = remoteSources.client(id)
                                val found = client?.let { runCatchingCancellable { withContext(Dispatchers.IO) { it.search(q) } } }
                                val section = if (client == null || found == null || found.isFailure) {
                                    RemoteSection(id, remoteSources.nameOf(id), loading = false, error = true)
                                } else {
                                    withContext(Dispatchers.Default) {
                                        val r = found.getOrThrow()
                                        RemoteSection(
                                            sourceId = id,
                                            name = remoteSources.nameOf(id),
                                            loading = false,
                                            songs = r.songs.map { com.ikko.aura.source.remote.RemoteTracks.trackOf(id, it, client) }.distinctBy { it.id },
                                            albums = r.albums.distinctBy { it.id }.map { a ->
                                                YtmAlbumRef("$id:${a.id}", a.title, a.artist, client.coverUrl(a.coverId, com.ikko.aura.source.remote.RemoteTracks.COVER_PX))
                                            },
                                            artists = r.artists.map { it.name }.distinct(),
                                        )
                                    }
                                }
                                remoteSearch.update { list -> list.map { if (it.sourceId == id) section else it } }
                            }
                        }
                    }
                }
        }
        viewModelScope.launch {
            combine(settings.ytmEnabled, query.debounce { if (it.isBlank()) 0L else YTM_DEBOUNCE_MS }) { on, q -> on to q.trim() }
                .distinctUntilChanged()
                .collect { (on, q) ->
                    ytmJob?.cancel()
                    ytmSideJobs.forEach { it.cancel() }
                    ytmSideJobs.clear()
                    nextPage = null
                    if (!on || q.length < 2) {
                        ytmSearch.value = YtmSearchState(enabled = on)
                        return@collect
                    }
                    searchedQuery = q
                    ytmSearch.value = YtmSearchState(enabled = true, loading = true)
                    // Albums and artists come on their own, so the songs are never held up by them.
                    ytmSideJobs += launch {
                        val albums = runCatchingCancellable { withContext(Dispatchers.IO) { ytmClient.searchAlbums(q).distinctBy { it.id } } }.getOrDefault(emptyList())
                        if (q == searchedQuery) ytmSearch.update { it.copy(albums = albums) }
                    }
                    ytmSideJobs += launch {
                        val artists = runCatchingCancellable { withContext(Dispatchers.IO) { ytmClient.searchArtists(q).distinctBy { it.name } } }.getOrDefault(emptyList())
                        if (q == searchedQuery) ytmSearch.update { it.copy(artists = artists) }
                    }
                    ytmJob = launch {
                        val page = runCatchingCancellable { withContext(Dispatchers.IO) { ytmClient.search(q) } }
                        if (q != searchedQuery) return@launch // The query moved on while this was on its way.
                        nextPage = page.getOrNull()?.next
                        val songs = page.getOrNull()?.songs.orEmpty()
                        val mappedResults = withContext(Dispatchers.Default) { songs.map(YtmLibrary::trackOf).distinctBy { it.id } }
                        ytmSearch.update {
                            it.copy(
                                loading = false,
                                error = page.isFailure,
                                empty = page.isSuccess && songs.isEmpty() && it.albums.isEmpty() && it.artists.isEmpty(),
                                hasMore = nextPage != null,
                                results = mappedResults,
                            )
                        }
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
            val more = runCatchingCancellable { withContext(Dispatchers.IO) { ytmClient.searchMore(q, page) } }
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

    val unifiedRemoteState: StateFlow<RemoteSection> = combine(ytmSearch, jioSaavnSearch, remoteSearch) { ytm, jiosaavn, remote ->
        val allSongs = ytm.results + (jiosaavn?.songs ?: emptyList()) + remote.flatMap { it.songs }
        val bestSongs = allSongs
            .groupBy { it.title.lowercase().trim() + "|" + it.artist.lowercase().trim() }
            .values.map { group -> group.maxByOrNull { it.bitrateKbps }!! }
            .sortedByDescending { it.bitrateKbps }

        val allAlbums = ytm.albums + (jiosaavn?.albums ?: emptyList()) + remote.flatMap { it.albums }
        val bestAlbums = allAlbums.distinctBy { it.title.lowercase().trim() + "|" + it.artist.lowercase().trim() }

        val allArtists = ytm.artists.map { it.name } + (jiosaavn?.artists ?: emptyList()) + remote.flatMap { it.artists }
        val bestArtists = allArtists.distinctBy { it.lowercase().trim() }

        RemoteSection(
            sourceId = "unified",
            name = "Meilleurs Résultats",
            loading = ytm.loading || (jiosaavn?.loading == true) || remote.any { it.loading },
            // Offline, or every source refusing: said, instead of an empty list. A source that is off does not count.
            error = run {
                val states = buildList {
                    if (ytm.enabled) add(ytm.error)
                    jiosaavn?.let { add(it.error) }
                    remote.forEach { add(it.error) }
                }
                states.isNotEmpty() && states.all { it }
            },
            songs = bestSongs,
            albums = bestAlbums,
            artists = bestArtists
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteSection("unified", "Meilleurs Résultats"))

    val unifiedHasMore = ytmSearch.map { it.hasMore }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val unifiedLoadingMore = ytmSearch.map { it.loadingMore }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setQuery(value: String) {
        query.value = value
    }

    /** The online sources, YouTube Music first, then the ones the user added. */
    val sources: StateFlow<List<SearchSource>> = combine(settings.ytmEnabled, remoteSources.configs) { ytmOn, configs ->
        listOf(SearchSource(YTM_SOURCE_ID, "YouTube Music", ytmOn)) +
            configs.sortedBy { it.createdAt }.map { SearchSource(it.id, it.name, it.enabled) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleSource(id: String, enabled: Boolean) {
        viewModelScope.launch {
            if (id == YTM_SOURCE_ID) settings.setYtmEnabled(enabled) else remoteSources.setEnabled(id, enabled)
        }
    }

    val recents: StateFlow<List<String>> = settings.searchRecents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Remembers a search the listener acted on (opened a result, or let it run). */
    fun rememberQuery(value: String) {
        viewModelScope.launch { settings.addSearchRecent(value) }
    }

    fun clearRecents() {
        viewModelScope.launch { settings.clearSearchRecents() }
    }
    
    fun setFilter(newFilter: SearchFilter) {
        filter.value = newFilter
    }
    
    fun clearSearch() {
        query.value = ""
    }

    private companion object {
        const val FILTER_DEBOUNCE_MS = 120L
        const val YTM_DEBOUNCE_MS = 300L
        const val YTM_SOURCE_ID = "ytm"
    }
}
