package com.ikko.aura.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.reco.BlockedArtist
import com.ikko.aura.reco.RecommendationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The recommendations' section of Réglages: the consent, the blocked artists, the typed-in ones, a reset. */
@HiltViewModel
class RecoSettingsViewModel @Inject constructor(
    private val engine: RecommendationEngine,
) : ViewModel() {

    val consent: StateFlow<Boolean> = engine.consent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val blocked: StateFlow<List<BlockedArtist>> = engine.blockedArtists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val seeds: StateFlow<List<String>> = engine.seedArtists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val refreshing: StateFlow<Boolean> = engine.refreshing

    fun setConsent(on: Boolean) { viewModelScope.launch { engine.setConsent(on) } }
    fun unblock(key: String) { viewModelScope.launch { engine.unblockArtist(key) } }
    fun block(name: String) { viewModelScope.launch { engine.blockArtist(name) } }
    fun setSeeds(names: List<String>) { viewModelScope.launch { engine.setSeedArtists(names) } }
    fun refresh() { viewModelScope.launch { engine.refreshNow() } }
    fun reset() { viewModelScope.launch { engine.resetProfile() } }
}
