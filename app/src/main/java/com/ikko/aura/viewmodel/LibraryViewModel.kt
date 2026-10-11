package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.AlbumLayoutMode
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.SettingsStore
import com.ikko.aura.data.TrackLayoutMode
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Artist
import com.ikko.aura.data.model.Track
import com.ikko.aura.player.smart.AnalysisReader
import com.ikko.aura.player.smart.SmartShuffle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibraryFilter { PLAYLISTS, ALBUMS, ARTISTS, TRACKS }
enum class LibrarySort { NAME, DATE_ADDED, ARTIST }

/**
 * `List<T>` fields make the Compose compiler infer a class as unstable by default — the
 * interface's own mutability isn't guaranteed, even when `T` is stable — which would otherwise
 * force every screen reading this state to recompose on *any* update rather than skip when
 * unchanged. This is always rebuilt via `.copy()`/fresh lists (never mutated in place), so
 * asserting immutability here is correct.
 */
@Immutable
data class LibraryUiState(
    val filter: LibraryFilter = LibraryFilter.TRACKS,
    val sort: LibrarySort = LibrarySort.NAME,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val isScanning: Boolean = true,
    val albumLayoutMode: AlbumLayoutMode = AlbumLayoutMode.GRID_3,
    val trackLayoutMode: TrackLayoutMode = TrackLayoutMode.GRID_2,
)

private data class RepositorySnapshot(
    val tracks: List<Track>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val isScanning: Boolean,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val analysisReader: AnalysisReader,
    private val selection: LibrarySelection,
    private val ytmLibrary: com.ikko.aura.source.ytm.YtmLibrary,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    /**
     * Builds a shuffled queue sequenced so consecutive tracks are ones Automix can mix — see
     * [SmartShuffle]. Suspending rather than a state field: it is a one-shot answer to a button
     * press, it reads the analysis files off the main thread, and the caller wants the queue in
     * hand to start playing it.
     */
    /** Re-reads MediaStore after a deletion the system has just confirmed. */
    fun rescan() {
        viewModelScope.launch { repository.refresh(forceRescan = true) }
    }

    suspend fun smartShuffle(): List<Track> {
        // Only what has a file: a song of another source whose download was deleted is not shuffled in.
        val tracks = ytmLibrary.withFiles(uiState.value.tracks)
        if (tracks.isEmpty()) return emptyList()
        val analyses = analysisReader.analysesFor(tracks.map { it.id })
        return withContext(Dispatchers.Default) {
            SmartShuffle.order(tracks, analysisOf = { analyses[it.id] })
        }
    }

    private val filter = selection.filter
    private val sort = MutableStateFlow(LibrarySort.NAME)

    private val repositorySnapshot = combine(
        repository.tracks,
        // The device's albums, then the ones downloaded from the other sources (YouTube Music, a Subsonic server…).
        combine(repository.albums, ytmLibrary.observeDownloadedAlbums()) { local, downloaded ->
            if (downloaded.isEmpty()) {
                local
            } else {
                val localIds = local.mapTo(HashSet(local.size)) { it.id }
                local + downloaded.filter { d -> d.id !in localIds }
            }
        },
        repository.artists,
        repository.isScanning,
    ) { tracks, albums, artists, isScanning ->
        RepositorySnapshot(tracks, albums, artists, isScanning)
    }

    private val sortedSnapshot = combine(repositorySnapshot, sort) { snapshot, s ->
        Triple(sortTracks(snapshot.tracks, s), sortAlbums(snapshot.albums, s), snapshot)
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<LibraryUiState> = combine(
        filter,
        sort,
        sortedSnapshot,
        settingsStore.albumLayoutMode,
        settingsStore.trackLayoutMode,
    ) { f, s, (sortedTracks, sortedAlbums, snapshot), albumLayout, trackLayout ->
        LibraryUiState(
            filter = f,
            sort = s,
            tracks = sortedTracks,
            albums = sortedAlbums,
            artists = snapshot.artists,
            isScanning = snapshot.isScanning,
            albumLayoutMode = albumLayout,
            trackLayoutMode = trackLayout,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        viewModelScope.launch { repository.refresh() }
    }

    fun setFilter(newFilter: LibraryFilter) {
        selection.select(newFilter)
    }

    fun setSort(newSort: LibrarySort) {
        sort.value = newSort
    }

    /**
     * The sort menu applied to albums too, not only to tracks.
     *
     * It used to leave them in the repository's own title order whatever was chosen, which made
     * the menu a no-op on the Albums tab — and, once the alphabet strip arrived, made it lie:
     * the strip indexed by artist under "Artiste" while the grid was still ordered by title, so
     * every letter jumped somewhere arbitrary.
     *
     * "Date d'ajout" keeps the title order: an album has no added-on date of its own, only its
     * tracks do, and inventing one out of the first track's would order the grid by something
     * the listener cannot see.
     */
    private fun sortAlbums(albums: List<Album>, sort: LibrarySort): List<Album> = when (sort) {
        LibrarySort.ARTIST -> albums.sortedWith(
            compareBy({ it.artist.lowercase() }, { it.title.lowercase() }),
        )
        LibrarySort.NAME, LibrarySort.DATE_ADDED -> albums
    }

    private fun sortTracks(tracks: List<Track>, sort: LibrarySort): List<Track> = when (sort) {
        LibrarySort.NAME -> tracks.sortedBy { it.title.lowercase() }
        LibrarySort.ARTIST -> tracks.sortedBy { it.artist.lowercase() }
        LibrarySort.DATE_ADDED -> tracks
    }
}
