package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.FavoritesStore
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.model.Track
import android.content.Context
import android.media.MediaMetadataRetriever
import com.ikko.aura.ui.components.AlbumTheme
import com.ikko.aura.ui.components.buildAlbumTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** See [LibraryUiState]'s doc comment — same `List<T>` stability-inference issue applies here. */
@Immutable
data class AlbumUiState(
    val title: String = "",
    val artist: String = "",
    val tracks: List<Track> = emptyList(),
    val isFavorite: Boolean = false,
    /** The album's cover when no song carries it yet (a page that is still being fetched). */
    val coverUri: android.net.Uri? = null,
    /** The page is a placeholder: what the card it came from knows, in the places the real page will fill. */
    val loading: Boolean = false,
)

/** Everything the album page needs before it can be drawn in one go. See [AlbumViewModel.page]. */
@Immutable
data class AlbumPage(val theme: AlbumTheme, val tags: FileTags)

/** Pages already worked out, so opening an album a second time is instant. */
private object AlbumPageCache {
    private val cache = android.util.LruCache<Long, AlbumPage>(32)
    fun get(albumId: Long): AlbumPage? = cache.get(albumId)
    fun put(albumId: Long, page: AlbumPage) { cache.put(albumId, page) }
}

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: MusicRepository,
    private val favoritesStore: FavoritesStore,
    @param:ApplicationContext private val context: Context,
    private val ytmClient: com.ikko.aura.source.ytm.YtmClient,
    private val ytmLibrary: com.ikko.aura.source.ytm.YtmLibrary,
) : ViewModel() {

    private val albumId: Long = savedStateHandle.get<String>("albumId")?.toLongOrNull() ?: -1L

    init {
        // An album downloaded before the artist was read from its songs carries the name of the channel that hosted
        // them ("le rappeur Damso", "Zaho Officiel"), and its artist link opens an artist that does not exist. Read the
        // album once from the source and rewrite its rows, so both the page and its link name the real artist; a
        // failure changes nothing (the rows stay as they are).
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val remote = repository.tracks.value
                    .filter { it.albumId == albumId }
                    .firstNotNullOfOrNull { it.remoteAlbumId }
                if (remote != null && ytmClient.isEnabled()) {
                    val songs = ytmClient.album(remote).songs.map { com.ikko.aura.source.ytm.YtmLibrary.trackOf(it) }
                    if (songs.isNotEmpty()) ytmLibrary.refreshArtists(songs)
                }
            }
        }
    }

    private fun stateFor(tracks: List<Track>, favoriteIds: Set<Long>): AlbumUiState {
        val albumTracks = tracks
            .filter { it.albumId == albumId }
            .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))
        return AlbumUiState(
            title = albumTracks.firstOrNull()?.album?.let(com.ikko.aura.data.LibraryNormalizer::displayTitle) ?: "",
            artist = com.ikko.aura.data.LibraryNormalizer.albumArtistOf(albumTracks.map { it.artist }),
            tracks = albumTracks,
            isFavorite = albumId in favoriteIds,
        )
    }

    /**
     * Seeded from what the repository already holds rather than from an empty state: the album's
     * tracks are in memory, so the page's first frame can have its list, and starting empty made the
     * rows arrive a beat after the header — the page assembling itself in front of you.
     */
    val uiState: StateFlow<AlbumUiState> = combine(
        repository.tracks,
        favoritesStore.favoriteAlbumIds,
    ) { tracks, favoriteIds -> stateFor(tracks, favoriteIds) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            stateFor(repository.tracks.value, favoritesStore.favoriteAlbumIds.value),
        )

    /**
     * The album's colours and its file tags, worked out together and handed over only when both are
     * ready — null until then, and the screen draws nothing of the page until it is not. The colours
     * and the genre line were arriving separately, a fraction of a second after the page, and every
     * one of those arrivals was a visible change: the buttons changing colour, a caption appearing
     * and pushing the list down. Held as a unit, the page appears complete.
     *
     * Read off the cover once and cached; keyed on the cover rather than on the track list, which is
     * re-emitted every time the background scan finishes another file. Genre and year come from the
     * first file itself — MediaStore's own genre column is a sub-query per row and slowed the whole
     * library scan for the sake of one line on one page.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val page: StateFlow<AlbumPage?> = uiState
        .map { it.tracks.firstOrNull() }
        .distinctUntilChangedBy { it?.albumId to it?.artworkUri }
        .transformLatest { track ->
            if (track == null) {
                emit(AlbumPage(AlbumTheme.Neutral, FileTags()))
                return@transformLatest
            }
            val cached = AlbumPageCache.get(albumId)
            if (cached != null) {
                emit(cached)
                return@transformLatest
            }
            // 1. Build theme fast (~10-15ms)
            val theme = withContext(Dispatchers.Default) { buildAlbumTheme(context, track) }
            val initial = AlbumPage(theme, FileTags(null, track.year))
            AlbumPageCache.put(albumId, initial)
            emit(initial)

            // 2. Read tags in background without blocking transition
            val tags = withContext(Dispatchers.IO) { readFileTags(context, track) }
            if (tags.genre != null || (tags.year > 0 && tags.year != track.year)) {
                val updated = AlbumPage(theme, tags)
                AlbumPageCache.put(albumId, updated)
                emit(updated)
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AlbumPageCache.get(albumId) ?: uiState.value.tracks.firstOrNull()?.let {
                AlbumPage(AlbumTheme.Neutral, FileTags(null, it.year))
            } ?: AlbumPage(AlbumTheme.Neutral, FileTags()),
        )

    fun toggleFavorite() = favoritesStore.toggle(albumId)
}

@Immutable
data class FileTags(val genre: String? = null, val year: Int = 0)

private fun readFileTags(context: Context, track: Track): FileTags {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, track.contentUri)
        val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.trim()?.takeIf { it.isNotEmpty() }
        val year = track.year.takeIf { it > 0 }
            ?: (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
                ?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
                ?: 0
        FileTags(genre, year)
    } catch (_: Exception) {
        FileTags(null, track.year)
    } finally {
        retriever.release()
    }
}
