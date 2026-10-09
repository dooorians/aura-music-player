package com.ikko.aura.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.playlist.PlaylistDetail
import com.ikko.aura.data.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    private val playlistId: Long = savedStateHandle.get<String>("playlistId")?.toLongOrNull() ?: -1L

    val detail: StateFlow<PlaylistDetail?> = playlistRepository.playlistDetail(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The Home card this playlist came from, when it is one of the weekly ones: its colours are the page's. */
    val autoKey: StateFlow<String?> = kotlinx.coroutines.flow.flow { emit(playlistRepository.autoKeyOf(playlistId)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun removeTrack(trackId: String) {
        viewModelScope.launch { playlistRepository.removeTrack(playlistId, trackId) }
    }

    fun rename(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { playlistRepository.renamePlaylist(playlistId, trimmed) }
    }

    fun delete() {
        viewModelScope.launch { playlistRepository.deletePlaylist(playlistId) }
    }
}
