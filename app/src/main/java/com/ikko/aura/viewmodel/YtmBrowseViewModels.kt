package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.ExperimentalCoroutinesApi
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ikko.aura.ui.components.buildAlbumTheme
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.FavoritesStore
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.model.Track
import com.ikko.aura.source.ytm.YtmAlbumRef
import com.ikko.aura.source.ytm.YtmClient
import com.ikko.aura.source.ytm.YtmLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class YtmAlbumState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val coverUrl: String? = null,
    val tracks: List<Track> = emptyList(),
)

@Immutable
data class YtmArtistState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val name: String = "",
    val coverUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    val albums: List<YtmAlbumRef> = emptyList(),
    /** What each album of [albums] is, by its id, when the artist's discography says: an album, a single or an EP. */
    val types: Map<String, com.ikko.aura.reco.authority.ReleaseType> = emptyMap(),
    /** The year each album of [albums] came out, by its id, when the discography says. */
    val years: Map<String, Int> = emptyMap(),
    /** The album each song of [tracks] is on, by the song's id, as the page finds them on the source. */
    val albumOf: Map<String, String> = emptyMap(),
    /** The whole line under each of the first songs ("Album - Single · 2026"), by the song's id, when the catalogue gave it with the page. */
    val subtitleOf: Map<String, String> = emptyMap(),
    /** Artists close to this one, the closest first. */
    val similar: List<SimilarArtist> = emptyList(),
)

@HiltViewModel
class YtmAlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val client: YtmClient,
    private val library: YtmLibrary,
    private val favoritesStore: FavoritesStore,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val albumId: String = savedStateHandle.get<String>("albumId").orEmpty()
    /** Opened from the search, which is explicitly online: the album as the source has it. Everywhere else, what is on the device wins. */
    private val _online = MutableStateFlow(savedStateHandle.get<Boolean>("online") ?: false)
    /** Which side of the source switch is on: the whole album on the source (true), or what is on the device. The page stays; its songs change. */
    val onlineFlow: StateFlow<Boolean> = _online.asStateFlow()
    val online: Boolean get() = _online.value
    private val _hasLocal = MutableStateFlow(false)
    /** Whether any of the album is on the device: what the "Local" side of the switch has to show. */
    val hasLocal: StateFlow<Boolean> = _hasLocal.asStateFlow()

    fun setOnline(on: Boolean) {
        if (on == _online.value) return
        _online.value = on
        load(keepPage = true)
    }
    /** The album's reference on its source, for the page that lists all of it. */
    val ref: String get() = albumId
    private val _state = MutableStateFlow(YtmAlbumState())
    val state: StateFlow<YtmAlbumState> = _state.asStateFlow()

    /** The same state a local album page is drawn from, so the page can be the same. */
    val uiState: StateFlow<AlbumUiState> = combine(_state, favoritesStore.favoriteAlbumIds) { album, favorites ->
        AlbumUiState(
            title = album.title,
            artist = album.artist,
            tracks = album.tracks,
            isFavorite = album.tracks.firstOrNull()?.albumId?.let { it in favorites } == true,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AlbumUiState())

    /** Its colours, read off the cover as a local album's are; null until the album and its cover are in. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val page: StateFlow<AlbumPage?> = _state
        .map { it.tracks.firstOrNull() }
        .distinctUntilChangedBy { it?.albumId to it?.artworkUri }
        .mapLatest { track -> track?.let { AlbumPage(buildAlbumTheme(context, it), FileTags()) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        load()
        // The "Local" side opens as soon as a song of this album is on the device (downloaded), and closes when the last one goes:
        // it is not worked out once, when the page opens.
        viewModelScope.launch {
            library.observeDoneKeys().collect { _hasLocal.value = library.downloadedOfAlbum(albumId).isNotEmpty() }
        }
    }

    fun toggleFavorite() {
        _state.value.tracks.firstOrNull()?.let { favoritesStore.toggle(it.albumId) }
    }

    /** [keepPage]: the source switch changes the songs of the page that is there, it does not empty it first. */
    fun load(keepPage: Boolean = false) {
        if (!keepPage) _state.value = YtmAlbumState()
        viewModelScope.launch {
            val onDevice = library.downloadedOfAlbum(albumId)
            _hasLocal.value = onDevice.isNotEmpty()
            // Once any of it is downloaded, the album is the one on the device: its downloaded songs, no network, from
            // wherever it is opened but the search.
            if (!_online.value) {
                if (onDevice.isNotEmpty()) {
                    _state.value = YtmAlbumState(
                        loading = false,
                        title = onDevice.first().album,
                        artist = onDevice.first().artist,
                        coverUrl = onDevice.first().artworkUri?.toString(),
                        tracks = onDevice,
                    )
                    return@launch
                }
            }
            val album = runCatching { withContext(Dispatchers.IO) { client.album(albumId) } }.getOrNull()
            _state.value = if (album == null || album.songs.isEmpty()) {
                // No answer (no network, the source off): the downloaded or known songs of this album are used so the page
                // can still be drawn from them.
                val saved = library.downloadedOfAlbum(albumId).ifEmpty {
                    library.known().filter { it.remoteAlbumId == albumId }
                }
                if (saved.isEmpty()) YtmAlbumState(loading = false, failed = true)
                else YtmAlbumState(
                    loading = false,
                    title = saved.first().album,
                    artist = saved.first().artist,
                    coverUrl = saved.first().artworkUri?.toString(),
                    tracks = saved,
                )
            } else {
                YtmAlbumState(
                    loading = false,
                    title = album.ref.title,
                    artist = album.ref.artist,
                    coverUrl = album.ref.thumbnailUrl,
                    tracks = album.songs.map(YtmLibrary::trackOf),
                )
            }
        }
    }
}

@HiltViewModel
class YtmArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    client: YtmClient,
    discographies: com.ikko.aura.reco.authority.ArtistDiscographies,
    deezer: com.ikko.aura.data.deezer.DeezerClient,
) : ViewModel() {
    private val name: String = savedStateHandle.get<String>("name").orEmpty()
    private val page = OnlineArtistPage(name, client, discographies, viewModelScope, deezer)
    val state: StateFlow<YtmArtistState> = page.state

    /** The same state a local artist page is drawn from. */
    val uiState: StateFlow<ArtistUiState> = state.map { s -> artistUiStateOf(s) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, artistUiStateOf(page.state.value))

    init { page.start() }

    fun load() = page.load()
}

/** The artist page's state from what an online artist's page found: albums apart from singles and EPs, as the discography types them. */
internal fun artistUiStateOf(s: YtmArtistState): ArtistUiState {
    fun albumOf(ref: YtmAlbumRef) = Album(
        id = YtmLibrary.pseudoAlbumId(ref.id),
        title = ref.title,
        artist = ref.artist,
        artistId = 0L,
        artworkUri = ref.thumbnailUrl?.let(android.net.Uri::parse),
        trackCount = 0,
        year = s.years[ref.id] ?: 0,
        remoteId = ref.id,
    )
    val (small, full) = s.albums.partition { s.types[it.id].let { t -> t == com.ikko.aura.reco.authority.ReleaseType.SINGLE || t == com.ikko.aura.reco.authority.ReleaseType.EP } }
    // The most recent first; the ones with no year known after those that have one.
    fun newestFirst(list: List<YtmAlbumRef>) = list.map(::albumOf).sortedByDescending { it.year }
    return ArtistUiState(name = s.name, albums = newestFirst(full), singles = newestFirst(small), tracks = s.tracks, albumOf = s.albumOf, subtitleOf = s.subtitleOf, similar = s.similar)
}
