package com.ikko.aura.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.playlist.PlaylistRepository
import com.ikko.aura.data.playlist.PlaylistSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val settingsStore: com.ikko.aura.data.SettingsStore,
) : ViewModel() {

    val recentIds: StateFlow<List<Long>> = settingsStore.recentPlaylistIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())


    val playlists: StateFlow<List<PlaylistSummary>> = playlistRepository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { playlistRepository.createPlaylist(trimmed) }
    }

    fun addTrack(playlistId: Long, trackId: String) {
        viewModelScope.launch { playlistRepository.addTrack(playlistId, trackId); settingsStore.markPlaylistUsed(playlistId) }
    }

    fun createPlaylistAndAddTracks(name: String, trackIds: List<String>) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = playlistRepository.createPlaylist(trimmed)
            trackIds.forEach { playlistRepository.addTrack(id, it) }
            settingsStore.markPlaylistUsed(id)
        }
    }
}
