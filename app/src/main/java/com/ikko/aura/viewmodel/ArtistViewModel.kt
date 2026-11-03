package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Track
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

/** See [LibraryUiState]'s doc comment — same `List<T>` stability-inference issue applies here. */
@Immutable
data class ArtistUiState(
    val name: String = "",
    val albums: List<Album> = emptyList(),
    /** The artist's singles and EPs, apart from their albums. */
    val singles: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
)

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: MusicRepository,
    private val discographies: com.ikko.aura.reco.authority.ArtistDiscographies,
) : ViewModel() {

    private val artistId: Long = savedStateHandle.get<String>("artistId")?.toLongOrNull() ?: -1L

    /** The artist's real discography, once the catalogues have answered: what tells a single or an EP from an album. */
    private val discography = MutableStateFlow<com.ikko.aura.reco.authority.Discography?>(null)

    /** The albums of the artist's discography the library lacks, found on the source: the page is the artist's, not only what is on the device. */
    private val missing = MutableStateFlow<List<Album>>(emptyList())

    private fun stateFor(tracks: List<Track>, albums: List<Album>, d: com.ikko.aura.reco.authority.Discography?, extra: List<Album>): ArtistUiState =
        localArtistUiState(artistId, tracks, albums, d, extra)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val name = repository.tracks.value.firstOrNull { it.artistId == artistId }?.artist
                ?: repository.albums.value.firstOrNull { it.artistId == artistId }?.artist
            if (name.isNullOrBlank()) return@launch
            val d = runCatching { discographies.of(name) }.getOrNull()
            discography.value = d
            if (d != null) {
                val have = repository.albums.value.filter { it.artistId == artistId }.map { it.title }
                missing.value = runCatching { discographies.missingOnSource(d, have) }.getOrDefault(emptyList()).map { ref ->
                    Album(
                        id = com.ikko.aura.source.ytm.YtmLibrary.pseudoAlbumId(ref.id), title = ref.title, artist = ref.artist, artistId = 0L,
                        artworkUri = ref.thumbnailUrl?.let(android.net.Uri::parse), trackCount = 0, year = d.yearOf(ref.title), remoteId = ref.id,
                    )
                }
            }
        }
    }

    // Seeded from what the repository already holds, so the page's first frame has its content
    // instead of filling in a beat later.
    val uiState: StateFlow<ArtistUiState> = combine(repository.tracks, repository.albums, discography, missing, ::stateFor)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), stateFor(repository.tracks.value, repository.albums.value, null, emptyList()))
}

/** "… - Single", "(EP)": a title that says what it is. */
private val SMALL_TITLE = Regex("(?i)[\\s(\\[-]\\s*(single|ep)\\s*[)\\]]?$")

private fun isSmall(a: Album, d: com.ikko.aura.reco.authority.Discography?): Boolean = when (d?.typeOf(a.title)) {
    com.ikko.aura.reco.authority.ReleaseType.SINGLE, com.ikko.aura.reco.authority.ReleaseType.EP -> true
    com.ikko.aura.reco.authority.ReleaseType.ALBUM -> false
    // Not in the catalogues (or no catalogue): what the files say.
    null -> SMALL_TITLE.containsMatchIn(a.title) || a.trackCount in 1..3
}

/** The page of an artist of the library, with their albums apart from their singles and EPs, the most recent first. */
internal fun localArtistUiState(
    artistId: Long,
    tracks: List<Track>,
    albums: List<Album>,
    d: com.ikko.aura.reco.authority.Discography?,
    extra: List<Album>,
): ArtistUiState {
    val artistTracks = tracks.filter { it.artistId == artistId }
    // A year the files do not give is the discography's.
    val own = albums.filter { it.artistId == artistId }.map { a -> if (a.year > 0 || d == null) a else a.copy(year = d.yearOf(a.title)) }
    val artistAlbums = (own + extra).sortedByDescending { it.year }
    val (small, full) = artistAlbums.partition { isSmall(it, d) }
    return ArtistUiState(
        name = artistTracks.firstOrNull()?.artist ?: artistAlbums.firstOrNull()?.artist ?: "",
        albums = full,
        singles = small,
        tracks = artistTracks,
    )
}
