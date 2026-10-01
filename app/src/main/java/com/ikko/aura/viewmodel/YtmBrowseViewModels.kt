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
)

@HiltViewModel
class YtmAlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val client: YtmClient,
    private val favoritesStore: FavoritesStore,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val albumId: String = savedStateHandle.get<String>("albumId").orEmpty()
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

    init { load() }

    fun toggleFavorite() {
        _state.value.tracks.firstOrNull()?.let { favoritesStore.toggle(it.albumId) }
    }

    fun load() {
        _state.value = YtmAlbumState()
        viewModelScope.launch {
            val album = runCatching { withContext(Dispatchers.IO) { client.album(albumId) } }.getOrNull()
            _state.value = if (album == null || album.songs.isEmpty()) {
                YtmAlbumState(loading = false, failed = true)
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
    private val client: YtmClient,
) : ViewModel() {
    private val name: String = savedStateHandle.get<String>("name").orEmpty()
    private val _state = MutableStateFlow(YtmArtistState(name = name))
    val state: StateFlow<YtmArtistState> = _state.asStateFlow()

    /** The same state a local artist page is drawn from. */
    val uiState: StateFlow<ArtistUiState> = _state.map { s ->
        ArtistUiState(
            name = s.name,
            albums = s.albums.map { ref ->
                Album(
                    id = YtmLibrary.pseudoAlbumId(ref.id),
                    title = ref.title,
                    artist = ref.artist,
                    artistId = 0L,
                    artworkUri = ref.thumbnailUrl?.let(android.net.Uri::parse),
                    trackCount = 0,
                    year = 0,
                    remoteId = ref.id,
                )
            },
            tracks = s.tracks,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ArtistUiState(name = name))

    init { load() }

    fun load() {
        _state.value = YtmArtistState(name = name)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val songs = async { client.artistSongs(name) }
                        val albums = async { runCatching { client.artistAlbums(name) }.getOrDefault(emptyList()) }
                        songs.await() to albums.await()
                    }
                }
            }.getOrNull()
            _state.value = if (result == null || (result.first.isEmpty() && result.second.isEmpty())) {
                YtmArtistState(loading = false, failed = result == null, name = name)
            } else {
                YtmArtistState(
                    loading = false,
                    name = name,
                    coverUrl = result.first.firstOrNull()?.thumbnailUrl ?: result.second.firstOrNull()?.thumbnailUrl,
                    tracks = result.first.map(YtmLibrary::trackOf),
                    albums = result.second,
                )
            }
        }
    }
}
