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

/** What a recommended playlist's page adds to a playlist's: why each song is there, and when the list was made. */
@androidx.compose.runtime.Immutable
data class RecoPage(
    val kind: com.ikko.aura.reco.RecoKind,
    /** Track id → the explanation, written from the signal that put it there. */
    val reasons: Map<String, String>,
    val updatedAtMs: Long,
    val status: String,
)

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val recommendations: com.ikko.aura.reco.RecommendationEngine,
) : ViewModel() {

    private val playlistId: Long = savedStateHandle.get<String>("playlistId")?.toLongOrNull() ?: -1L

    val detail: StateFlow<PlaylistDetail?> = playlistRepository.playlistDetail(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The Home card this playlist came from, when it is one of the weekly ones: its colours are the page's. */
    val autoKey: StateFlow<String?> = kotlinx.coroutines.flow.flow { emit(playlistRepository.autoKeyOf(playlistId)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Set when this playlist is one of the seven that are recommended, once it has been generated. */
    val reco: StateFlow<RecoPage?> = kotlinx.coroutines.flow.combine(autoKey, recommendations.items, recommendations.status) { key, items, status ->
        val kind = com.ikko.aura.reco.RecoKind.ofKey(key) ?: return@combine null
        val mine = items[kind.key].orEmpty()
        if (mine.isEmpty()) return@combine null
        RecoPage(
            kind = kind,
            reasons = mine.mapNotNull { i ->
                val r = i.reason?.text()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                i.trackId to (if (i.verified) r else "$r · lecture non vérifiée")
            }.toMap(),
            updatedAtMs = status[kind.key]?.generatedAtMs ?: 0L,
            status = status[kind.key]?.status ?: "",
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val refreshing: StateFlow<Boolean> = recommendations.refreshing

    fun refresh() {
        val kind = reco.value?.kind ?: return
        viewModelScope.launch { recommendations.refreshNow(kind) }
    }

    fun dislike(track: com.ikko.aura.data.model.Track) {
        val context = reco.value?.kind?.key
        viewModelScope.launch { recommendations.dislike(track, context) }
    }

    fun markKnown(track: com.ikko.aura.data.model.Track) { viewModelScope.launch { recommendations.markKnown(track) } }
    fun hide(track: com.ikko.aura.data.model.Track) { viewModelScope.launch { recommendations.hide(track) } }
    fun blockArtist(name: String) { viewModelScope.launch { recommendations.blockArtist(name) } }

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
