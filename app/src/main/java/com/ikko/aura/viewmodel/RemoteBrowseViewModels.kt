package com.ikko.aura.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.FavoritesStore
import com.ikko.aura.data.model.Album
import com.ikko.aura.source.jiosaavn.JioSaavnClient
import com.ikko.aura.source.jiosaavn.JioSaavnTracks
import com.ikko.aura.source.remote.RemoteAlbumPage
import com.ikko.aura.source.remote.RemoteSources
import com.ikko.aura.source.remote.RemoteTracks
import com.ikko.aura.source.ytm.YtmAlbumRef
import com.ikko.aura.source.ytm.YtmClient
import com.ikko.aura.source.ytm.YtmLibrary
import com.ikko.aura.ui.components.buildAlbumTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val _online = MutableStateFlow(savedStateHandle.get<Boolean>("online") ?: false)
    /** Which side of the source switch is on: the whole album on the source (true), or what is on the device. The page stays; its songs change. */
    val onlineFlow: StateFlow<Boolean> = _online.asStateFlow()
    val online: Boolean get() = _online.value
    private val _hasLocal = MutableStateFlow(false)
    /** Whether any of the album is on the device: what the "Local" side of the switch has to show. */
    val hasLocal: StateFlow<Boolean> = _hasLocal.asStateFlow()
    /** The album's reference on its source, for the page that lists all of it. */
    val ref: String get() = "$sourceId:$albumId"

    fun setOnline(on: Boolean) {
        if (on == _online.value) return
        _online.value = on
        load(keepPage = true)
    }
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

    /** [keepPage]: the source switch changes the songs of the page that is there, it does not empty it first. */
    fun load(keepPage: Boolean = false) {
        if (!keepPage) _state.value = YtmAlbumState()
        viewModelScope.launch {
            val onDevice = library.downloadedOfAlbum("$sourceId:$albumId")
            _hasLocal.value = onDevice.isNotEmpty()
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

/** An artist of an added source, or across all active online sources (`unified`), by name. */
@HiltViewModel
class RemoteArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sources: RemoteSources,
    private val ytm: YtmClient,
    private val jioSaavn: JioSaavnClient,
    private val discographies: com.ikko.aura.reco.authority.ArtistDiscographies,
) : ViewModel() {
    private val sourceId: String = savedStateHandle.get<String>("source").orEmpty()
    private val name: String = savedStateHandle.get<String>("name").orEmpty()
    private val _state = MutableStateFlow(YtmArtistState(name = name))
    val state: StateFlow<YtmArtistState> = _state.asStateFlow()

    val uiState: StateFlow<ArtistUiState> = _state.map { s -> artistUiStateOf(s) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ArtistUiState(name = name))

    init { load() }

    /** The albums the page lacked of the artist's real discography, found afterwards: the page is there at once, they join it as they come. */
    private fun completeLater(shown: List<YtmAlbumRef>) {
        viewModelScope.launch(Dispatchers.IO) {
            val more = runCatching { discographies.completion(name, shown) }.getOrNull() ?: return@launch
            if (more.albums.isEmpty()) return@launch
            _state.value = _state.value.let { s ->
                s.copy(albums = (s.albums + more.albums).distinctBy { it.id }, types = s.types + more.types, years = s.years + more.years)
            }
        }
    }

    /**
     * The page, at once, with what the name alone allows; then checked against the artist's real discography, which takes its time:
     * what is not theirs goes, and the type and the year of each release come in.
     */
    private fun present(tracks: List<Track>, albums: List<YtmAlbumRef>) {
        val (mine, theirAlbums) = discographies.byName(name, tracks, albums)
        _state.value = YtmArtistState(
            loading = false,
            name = name,
            coverUrl = mine.firstOrNull { it.artworkUri != null }?.artworkUri?.toString() ?: theirAlbums.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
            tracks = mine,
            albums = theirAlbums,
        )
        viewModelScope.launch(Dispatchers.IO) {
            val page = runCatching { discographies.verify(name, mine, theirAlbums) }.getOrNull() ?: return@launch
            _state.value = _state.value.copy(tracks = page.tracks, albums = page.albums, types = page.types, years = page.years)
            completeLater(page.albums)
        }
    }

    fun load() {
        _state.value = YtmArtistState(name = name)
        // Looked up while the sources answer, not after them.
        discographies.prefetch(viewModelScope, name)
        viewModelScope.launch {
            if (sources.isRemoteId(sourceId)) {
                val client = sources.client(sourceId)
                val result = client?.let {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val key = name.lowercase()
                            val songs = it.search(name).songs.filter { s ->
                                val a = s.artist.lowercase()
                                a.contains(key) || key.contains(a)
                            }
                            songs to it.artistAlbums(name)
                        }
                    }.getOrNull()
                }
                _state.value = if (client == null || result == null || (result.first.isEmpty() && result.second.isEmpty())) {
                    YtmArtistState(loading = false, failed = client == null || result == null, name = name)
                } else {
                    present(
                        result.first.map { RemoteTracks.trackOf(sourceId, it, client) },
                        result.second.map { a -> YtmAlbumRef("$sourceId:${a.id}", a.title, a.artist, client.coverUrl(a.coverId, RemoteTracks.COVER_PX)) },
                    )
                    _state.value
                }
            } else {
                val key = name.lowercase()
                val (answered, attempted) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val activeRemotes = sources.active()
                        val remoteJobs = activeRemotes.map { handle ->
                            async {
                                runCatching {
                                    val songs = handle.client.search(name).songs
                                        .filter { s ->
                                            val a = s.artist.lowercase()
                                            a.contains(key) || key.contains(a)
                                        }
                                        .map { RemoteTracks.trackOf(handle.config.id, it, handle.client) }
                                    val albums = handle.client.artistAlbums(name).map { a ->
                                        YtmAlbumRef(
                                            "${handle.config.id}:${a.id}",
                                            a.title,
                                            a.artist,
                                            handle.client.coverUrl(a.coverId, RemoteTracks.COVER_PX),
                                        )
                                    }
                                    songs to albums
                                }.getOrNull()
                            }
                        }
                        val ytmJob = if (ytm.isEnabled()) {
                            async {
                                runCatching {
                                    val songs = async { ytm.artistSongs(name).map(YtmLibrary::trackOf) }
                                    val albums = async { runCatching { ytm.artistAlbums(name) }.getOrDefault(emptyList()) }
                                    songs.await() to albums.await()
                                }.getOrNull()
                            }
                        } else null
                        val saavnJob = async {
                            runCatching {
                                val songs = jioSaavn.searchSongsOrThrow(name)
                                    .map(JioSaavnTracks::trackOf)
                                    .filter { s ->
                                        val a = s.artist.lowercase()
                                        a.contains(key) || key.contains(a)
                                    }
                                songs to emptyList<YtmAlbumRef>()
                            }.getOrNull()
                        }
                        val totalAttempted = remoteJobs.size + (if (ytmJob != null) 1 else 0) + 1
                        val results = buildList {
                            remoteJobs.forEach { it.await()?.let(::add) }
                            ytmJob?.await()?.let(::add)
                            saavnJob.await()?.let(::add)
                        }
                        results to totalAttempted
                    }
                }
                if (answered.isEmpty()) {
                    _state.value = YtmArtistState(loading = false, failed = attempted > 0, name = name)
                } else {
                    val tracks = answered
                        .flatMap { it.first }
                        .groupBy { it.title.lowercase().trim() }
                        .values
                        .map { group ->
                            group.firstOrNull { !it.id.startsWith("ytm:") && !it.id.startsWith("jiosaavn:") }
                                ?: group.maxByOrNull { it.bitrateKbps }!!
                        }
                    val albums = answered
                        .flatMap { it.second }
                        .distinctBy { it.title.lowercase().trim() }
                    present(tracks, albums)
                }
            }
        }
    }
}
