package com.ikko.aura.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.FavoritesStore
import com.ikko.aura.data.model.Album
import com.ikko.aura.source.remote.RemoteAlbumPage
import com.ikko.aura.source.remote.RemoteSources
import com.ikko.aura.source.remote.RemoteTracks
import com.ikko.aura.source.ytm.YtmAlbumRef
import com.ikko.aura.source.ytm.YtmLibrary
import com.ikko.aura.ui.components.buildAlbumTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An album of an added source, as the shared album page is drawn from it. */
@HiltViewModel
class RemoteAlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sources: RemoteSources,
    private val library: com.ikko.aura.source.ytm.YtmLibrary,
    private val favoritesStore: FavoritesStore,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val sourceId: String = savedStateHandle.get<String>("source").orEmpty()
    private val albumId: String = savedStateHandle.get<String>("albumId").orEmpty()
    private val _state = MutableStateFlow(YtmAlbumState())
    val state: StateFlow<YtmAlbumState> = _state.asStateFlow()

    val uiState: StateFlow<AlbumUiState> = combine(_state, favoritesStore.favoriteAlbumIds) { album, favorites ->
        AlbumUiState(
            title = album.title,
            artist = album.artist,
            tracks = album.tracks,
            isFavorite = album.tracks.firstOrNull()?.albumId?.let { it in favorites } == true,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AlbumUiState())

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
            val client = sources.client(sourceId)
            val page: RemoteAlbumPage? = client?.let { runCatching { withContext(Dispatchers.IO) { it.album(albumId) } }.getOrNull() }
            _state.value = if (client == null || page == null || page.songs.isEmpty()) {
                // No answer: the downloaded songs of this album are files, and the page can be drawn from them.
                val saved = library.downloadedOfAlbum("$sourceId:$albumId")
                if (saved.isEmpty()) YtmAlbumState(loading = false, failed = true)
                else YtmAlbumState(
                    loading = false,
                    title = saved.first().album,
                    artist = saved.first().artist,
                    coverUrl = saved.first().artworkUri?.toString(),
                    tracks = saved,
                )
            } else {
                val tracks = page.songs.map { song ->
                    // Every song carries its album, whatever the listing said.
                    RemoteTracks.trackOf(sourceId, song.copy(albumId = albumId, album = page.album.title.ifEmpty { song.album }), client)
                }
                YtmAlbumState(
                    loading = false,
                    title = page.album.title,
                    artist = page.album.artist.ifEmpty { tracks.firstOrNull()?.artist.orEmpty() },
                    coverUrl = client.coverUrl(page.album.coverId, RemoteTracks.COVER_PX),
                    tracks = tracks,
                )
            }
        }
    }
}

/** An artist of an added source, by name: its albums there, and the songs its search turns up. */
@HiltViewModel
class RemoteArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sources: RemoteSources,
) : ViewModel() {
    private val sourceId: String = savedStateHandle.get<String>("source").orEmpty()
    private val name: String = savedStateHandle.get<String>("name").orEmpty()
    private val _state = MutableStateFlow(YtmArtistState(name = name))
    val state: StateFlow<YtmArtistState> = _state.asStateFlow()

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
            val client = sources.client(sourceId)
            val result = client?.let {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val key = name.lowercase()
                        val songs = it.search(name).songs.filter { s -> s.artist.lowercase().contains(key) }
                        songs to it.artistAlbums(name)
                    }
                }.getOrNull()
            }
            _state.value = if (client == null || result == null || (result.first.isEmpty() && result.second.isEmpty())) {
                YtmArtistState(loading = false, failed = client == null || result == null, name = name)
            } else {
                YtmArtistState(
                    loading = false,
                    name = name,
                    tracks = result.first.map { RemoteTracks.trackOf(sourceId, it, client) },
                    albums = result.second.map { a ->
                        YtmAlbumRef("$sourceId:${a.id}", a.title, a.artist, client.coverUrl(a.coverId, RemoteTracks.COVER_PX))
                    },
                )
            }
        }
    }
}
