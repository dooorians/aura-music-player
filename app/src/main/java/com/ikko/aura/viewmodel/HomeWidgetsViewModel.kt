package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.AlbumPlayEntity
import com.ikko.aura.data.FavoritesStore
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.SettingsStore
import com.ikko.aura.data.RecentlyPlayedTracker
import com.ikko.aura.data.home.HomeCategory
import com.ikko.aura.data.home.HomeLayoutOps
import com.ikko.aura.data.home.HomeLayoutStore
import com.ikko.aura.data.home.HomeSourceData
import com.ikko.aura.data.home.HomeWidget
import com.ikko.aura.data.home.HomeWidgetResolver
import com.ikko.aura.data.home.WidgetContent
import com.ikko.aura.data.home.WidgetKind
import com.ikko.aura.data.home.WidgetSize
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.PlaylistRepository
import com.ikko.aura.data.playlist.PlaylistSummary
import com.ikko.aura.source.ytm.YtmLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A widget and what it shows right now. */
@Immutable
data class WidgetItem(val widget: HomeWidget, val content: WidgetContent)

/** One thing the "add / change content" sheet offers: a kind, and the argument that points it at something. */
@Immutable
data class PickerOption(
    val section: String,
    val title: String,
    val subtitle: String?,
    val kind: WidgetKind,
    val arg: String?,
)

/** See [LibraryUiState]'s doc comment — same `List<T>` stability-inference issue applies here. */
@Immutable
data class HomeWidgetsUiState(
    /** False until the saved layout has been read: nothing is drawn before that, rather than the wrong layout. */
    val loaded: Boolean = false,
    /** Every widget, hidden ones included, in page order. */
    val items: List<WidgetItem> = emptyList(),
    val picker: List<PickerOption> = emptyList(),
)

/**
 * The state of the widget-based Home: the saved layout, resolved against the library into what each card shows, and
 * the edits that change the layout. Kept apart from [HomeViewModel], which the other builds' Home still uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeWidgetsViewModel @Inject constructor(
    repository: MusicRepository,
    recentlyPlayedTracker: RecentlyPlayedTracker,
    favoritesStore: FavoritesStore,
    ytmLibrary: YtmLibrary,
    playlistRepository: PlaylistRepository,
    private val layoutStore: HomeLayoutStore,
    settingsStore: SettingsStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    val profileImageUri: StateFlow<String?> = settingsStore.profileImageUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profileFirstName: StateFlow<String> = settingsStore.profileFirstName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** The songs of the playlists the layout points at, followed live so a widget stays current as its playlist does. */
    private val playlistTracks = layoutStore.widgets
        .map { widgets ->
            widgets.orEmpty().filter { it.kind == WidgetKind.PLAYLIST }.mapNotNull { it.arg?.toLongOrNull() }.distinct()
        }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf<Map<Long, List<Track>>>(emptyMap())
            } else {
                combine(
                    ids.map { id -> playlistRepository.playlistDetail(id).map { id to it?.tracks.orEmpty() } },
                ) { it.toMap() }
            }
        }

    val uiState: StateFlow<HomeWidgetsUiState> = combine(
        layoutStore.widgets,
        repository.tracks,
        repository.albums,
        recentlyPlayedTracker.recentAlbumIds,
        favoritesStore.favoriteAlbumIds,
        recentlyPlayedTracker.playCounts,
        recentlyPlayedTracker.recentTrackIds,
        ytmLibrary.observeKnown(),
        ytmLibrary.observeKept(),
        playlistRepository.playlists,
        playlistTracks,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val layout = values[0] as List<HomeWidget>?
        @Suppress("UNCHECKED_CAST")
        val tracks = values[1] as List<Track>
        @Suppress("UNCHECKED_CAST")
        val albums = values[2] as List<Album>
        @Suppress("UNCHECKED_CAST")
        val recentAlbumIds = values[3] as List<Long>
        @Suppress("UNCHECKED_CAST")
        val favoriteIds = values[4] as Set<Long>
        @Suppress("UNCHECKED_CAST")
        val plays = values[5] as List<AlbumPlayEntity>
        @Suppress("UNCHECKED_CAST")
        val recentTrackIds = values[6] as List<String>
        @Suppress("UNCHECKED_CAST")
        val streamed = values[7] as List<Track>
        @Suppress("UNCHECKED_CAST")
        val kept = values[8] as List<Track>
        @Suppress("UNCHECKED_CAST")
        val playlists = values[9] as List<PlaylistSummary>
        @Suppress("UNCHECKED_CAST")
        val playlistTracks = values[10] as Map<Long, List<Track>>

        if (layout == null) return@combine HomeWidgetsUiState()

        // Streamed songs count as the library's own once played or kept, as on the old Home.
        val allTracks = (tracks + streamed + kept).distinctBy { it.id }
        val allAlbums = (albums + YtmLibrary.albumsOf((streamed + kept).distinctBy { it.id })).distinctBy { it.id }
        val byId = allTracks.associateBy { it.id }
        val data = HomeSourceData(
            allTracks = allTracks,
            tracksByAlbum = allTracks.groupBy { it.albumId },
            albums = allAlbums,
            favoriteAlbums = allAlbums.filter { it.id in favoriteIds },
            recentTracks = recentTrackIds.mapNotNull { byId[it] },
            recentAlbumIds = recentAlbumIds.toSet(),
            albumPlays = plays.associate { it.albumId to it.playCount },
            playlists = playlists,
            playlistTracks = playlistTracks,
            day = System.currentTimeMillis() / MILLIS_PER_DAY,
        )
        HomeWidgetsUiState(
            loaded = true,
            items = layout.map { WidgetItem(it, HomeWidgetResolver.resolve(it, data)) },
            picker = pickerOptions(data),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeWidgetsUiState())

    /** Every choice the add / change-content sheet offers, drawn from what the library really holds. */
    private fun pickerOptions(data: HomeSourceData): List<PickerOption> = buildList {
        WidgetKind.entries.filterNot { it.takesArgument }.forEach { kind ->
            add(PickerOption(SECTION_SELECTIONS, kind.title, null, kind, null))
        }
        if (data.allTracks.any { it.isHiRes }) {
            add(PickerOption(SECTION_CATEGORIES, "Hi-Res", "Fichiers au-delà de la qualité CD", WidgetKind.CATEGORY, HomeCategory.HI_RES))
        }
        if (data.allTracks.any { it.isLossless }) {
            add(PickerOption(SECTION_CATEGORIES, "Lossless", "FLAC, ALAC, WAV…", WidgetKind.CATEGORY, HomeCategory.LOSSLESS))
        }
        data.allTracks.map { it.year }.filter { it > 0 }.map { it / 10 * 10 }.distinct().sortedDescending().forEach { decade ->
            add(PickerOption(SECTION_CATEGORIES, "Années $decade", null, WidgetKind.CATEGORY, HomeCategory.decade(decade)))
        }
        data.playlists.forEach { playlist ->
            add(
                PickerOption(
                    SECTION_PLAYLISTS, playlist.name, "${playlist.trackCount} titres", WidgetKind.PLAYLIST,
                    playlist.id.toString(),
                ),
            )
        }
        data.albums.sortedBy { it.title.lowercase() }.forEach { album ->
            add(PickerOption(SECTION_ALBUMS, album.title.ifBlank { "Album sans titre" }, album.artist, WidgetKind.ALBUM, album.id.toString()))
        }
    }

    fun resize(id: String, size: WidgetSize) = layoutStore.update { HomeLayoutOps.resize(it, id, size) }
    fun hide(id: String) = layoutStore.update { HomeLayoutOps.setHidden(it, id, true) }
    fun show(id: String) = layoutStore.update { HomeLayoutOps.setHidden(it, id, false) }
    fun remove(id: String) {
        // A GIF widget owns the copy of its file: it goes with it.
        uiState.value.items.firstOrNull { it.widget.id == id }?.widget
            ?.takeIf { it.kind == WidgetKind.GIF }?.arg?.let { path -> viewModelScope.launch(Dispatchers.IO) { java.io.File(path).delete() } }
        layoutStore.update { HomeLayoutOps.remove(it, id) }
    }

    /** Copies the chosen GIF into the app's own storage (the picker's link would not outlive it) and adds it as a widget. */
    fun addGif(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = java.io.File(context.filesDir, "home_gifs").apply { mkdirs() }
            val file = java.io.File(dir, "${java.util.UUID.randomUUID()}.gif")
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } != null
            }.getOrDefault(false)
            if (copied) layoutStore.update { HomeLayoutOps.add(it, WidgetKind.GIF, file.absolutePath) } else file.delete()
        }
    }
    fun add(kind: WidgetKind, arg: String?) = layoutStore.update { HomeLayoutOps.add(it, kind, arg) }
    fun replaceContent(id: String, kind: WidgetKind, arg: String?) =
        layoutStore.update { HomeLayoutOps.replaceContent(it, id, kind, arg) }
    fun moveTo(id: String, targetId: String) = layoutStore.update { HomeLayoutOps.moveTo(it, id, targetId) }
    fun shift(id: String, delta: Int) = layoutStore.update { HomeLayoutOps.shift(it, id, delta) }
    fun reset() = layoutStore.reset()

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val SECTION_SELECTIONS = "Sélections"
        const val SECTION_CATEGORIES = "Catégories"
        const val SECTION_PLAYLISTS = "Playlists"
        const val SECTION_ALBUMS = "Albums"
    }
}
