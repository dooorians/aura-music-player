package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
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
) : ViewModel() {
    private val albumId: String = savedStateHandle.get<String>("albumId").orEmpty()
    private val _state = MutableStateFlow(YtmAlbumState())
    val state: StateFlow<YtmAlbumState> = _state.asStateFlow()

    init { load() }

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
