package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Immutable
data class ArtistPlayCount(val name: String, val trackCount: Int)

/** See [LibraryUiState]'s doc comment — same `List<T>` stability-inference issue applies here. */
@Immutable
data class StatsUiState(
    val totalTracks: Int = 0,
    val totalAlbums: Int = 0,
    val totalArtists: Int = 0,
    val totalDurationMs: Long = 0L,
    val topArtists: List<ArtistPlayCount> = emptyList(),
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    repository: MusicRepository,
) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = repository.tracks
        .map(::aggregate)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private fun aggregate(tracks: List<Track>): StatsUiState {
        if (tracks.isEmpty()) return StatsUiState()
        val topArtists = tracks.groupBy { it.artist }
            .map { (artist, list) -> ArtistPlayCount(artist, list.size) }
            .sortedByDescending { it.trackCount }
            .take(5)
        return StatsUiState(
            totalTracks = tracks.size,
            totalAlbums = tracks.map { it.albumId }.distinct().size,
            totalArtists = tracks.map { it.artistId }.distinct().size,
            totalDurationMs = tracks.sumOf { it.durationMs },
            topArtists = topArtists,
        )
    }
}
