package com.ikko.aura.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.LyricsRepository
import com.ikko.aura.data.lyrics.LyricLine
import com.ikko.aura.data.model.Track
import com.ikko.aura.util.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
sealed interface LyricsUiState {
    data object Loading : LyricsUiState
    data object Unavailable : LyricsUiState
    data class Available(val lines: List<LyricLine>, val sourceLabel: String) : LyricsUiState
}

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val lyricsRepository: LyricsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LyricsUiState>(LyricsUiState.Unavailable)
    val uiState: StateFlow<LyricsUiState> = _uiState.asStateFlow()

    private var loadedTrackId: String? = null
    private var loadJob: Job? = null

    fun loadFor(track: Track?) {
        if (track == null) {
            loadJob?.cancel()
            loadedTrackId = null
            _uiState.value = LyricsUiState.Unavailable
            return
        }
        if (track.id == loadedTrackId) return
        loadedTrackId = track.id
        // Cancelled rather than left to finish: skipping through a queue would otherwise
        // leave three network lookups per track running, and the answer to all but the
        // last of them is already out of date by the time it lands.
        loadJob?.cancel()
        _uiState.value = LyricsUiState.Loading
        loadJob = viewModelScope.launch {
            // A breath first: a run of skips looks up only the last track's words, and does not keep the CPU busy
            // under the player's own change.
            kotlinx.coroutines.delay(LOOKUP_SETTLE_MS)
            // An error (the settings or the cache unreadable) is "no lyrics", not a crash, nor a page left on Loading.
            val result = runCatchingCancellable { lyricsRepository.loadLyrics(track) }.getOrNull()
            _uiState.value = if (result == null || result.lines.isEmpty()) {
                LyricsUiState.Unavailable
            } else {
                LyricsUiState.Available(result.lines, result.sourceLabel)
            }
        }
    }

    /** Drops what is stored for the track and looks it up again from scratch. */
    fun refetch(track: Track?) {
        val current = track ?: return
        loadJob?.cancel()
        // Set now, not once the coroutine runs: a loadFor() of the same track arriving meanwhile took it for another
        // one, cancelled this refetch before the stored lyrics were dropped, and showed them again from the cache.
        loadedTrackId = current.id
        _uiState.value = LyricsUiState.Loading
        loadJob = viewModelScope.launch {
            lyricsRepository.forget(current.id)
            val result = runCatchingCancellable { lyricsRepository.loadLyrics(current) }.getOrNull()
            _uiState.value = if (result == null || result.lines.isEmpty()) {
                LyricsUiState.Unavailable
            } else {
                LyricsUiState.Available(result.lines, result.sourceLabel)
            }
        }
    }

    private companion object {
        const val LOOKUP_SETTLE_MS = 250L
    }
}
