package com.ikko.aura.viewmodel

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.EqualizerSettings
import com.ikko.aura.data.SettingsStore
import com.ikko.aura.player.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class EqBand(
    val index: Int,
    val centerFreqHz: Int,
    val levelMillibel: Int,
    val minLevelMillibel: Int,
    val maxLevelMillibel: Int,
)

/** See [LibraryUiState]'s doc comment — same `List<T>` stability-inference issue applies here. */
@Immutable
data class EqualizerUiState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val bands: List<EqBand> = emptyList(),
    val presets: List<String> = emptyList(),
    val selectedPresetIndex: Int = -1,
    val bassBoostSupported: Boolean = false,
    val bassBoostEnabled: Boolean = false,
    val bassBoostStrength: Int = 0,
)

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    playerController: PlayerController,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    private val _uiState = MutableStateFlow(EqualizerUiState())
    val uiState: StateFlow<EqualizerUiState> = _uiState.asStateFlow()

    init {
        val sessionId = playerController.audioSessionId
        val eq = runCatching { Equalizer(0, sessionId) }.getOrNull()
        equalizer = eq

        if (eq == null) {
            _uiState.update { it.copy(available = false) }
        } else {
            val bb = runCatching { BassBoost(0, sessionId) }.getOrNull()
            bassBoost = bb
            val bassSupported = bb?.let { runCatching { it.strengthSupported }.getOrDefault(false) } ?: false
            val presets = runCatching {
                (0 until eq.numberOfPresets).map { frenchPresetName(eq.getPresetName(it.toShort())) }
            }.getOrDefault(emptyList())

            viewModelScope.launch {
                val saved = runCatching { settingsStore.equalizerSettings.first() }.getOrDefault(EqualizerSettings())
                applySavedSettings(eq, bb, saved)
                _uiState.update {
                    it.copy(
                        available = true,
                        enabled = saved.enabled,
                        bands = readBands(eq),
                        presets = presets,
                        selectedPresetIndex = saved.presetIndex,
                        bassBoostSupported = bassSupported,
                        bassBoostEnabled = saved.bassBoostEnabled,
                        bassBoostStrength = saved.bassBoostStrength,
                    )
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        val eq = equalizer ?: return
        runCatching { eq.enabled = enabled }
        bassBoost?.let { bb -> runCatching { bb.enabled = enabled && _uiState.value.bassBoostEnabled } }
        _uiState.update { it.copy(enabled = enabled) }
        persist()
    }

    fun setBandLevel(bandIndex: Int, level: Int) {
        val eq = equalizer ?: return
        runCatching { eq.setBandLevel(bandIndex.toShort(), level.toShort()) }
        val actualLevel = runCatching { eq.getBandLevel(bandIndex.toShort()).toInt() }.getOrDefault(level)
        _uiState.update { state ->
            state.copy(
                bands = state.bands.map { if (it.index == bandIndex) it.copy(levelMillibel = actualLevel) else it },
                selectedPresetIndex = -1,
            )
        }
        persist()
    }

    fun applyPreset(presetIndex: Int) {
        val eq = equalizer ?: return
        runCatching { eq.usePreset(presetIndex.toShort()) }
        _uiState.update { it.copy(bands = readBands(eq), selectedPresetIndex = presetIndex) }
        persist()
    }

    fun setBassBoostEnabled(enabled: Boolean) {
        val bb = bassBoost ?: return
        runCatching { bb.enabled = enabled && _uiState.value.enabled }
        _uiState.update { it.copy(bassBoostEnabled = enabled) }
        persist()
    }

    fun setBassBoostStrength(strength: Int) {
        val bb = bassBoost ?: return
        if (bb.strengthSupported) runCatching { bb.setStrength(strength.toShort()) }
        _uiState.update { it.copy(bassBoostStrength = strength) }
        persist()
    }

    private fun applySavedSettings(eq: Equalizer, bb: BassBoost?, saved: EqualizerSettings) {
        runCatching { eq.enabled = saved.enabled }
        val bandCount = eq.numberOfBands.toInt()
        if (saved.bandLevels.size == bandCount && saved.bandLevels.isNotEmpty()) {
            saved.bandLevels.forEachIndexed { i, level -> runCatching { eq.setBandLevel(i.toShort(), level) } }
        } else if (saved.presetIndex >= 0) {
            runCatching { eq.usePreset(saved.presetIndex.toShort()) }
        }
        if (bb != null) {
            runCatching { bb.enabled = saved.bassBoostEnabled && saved.enabled }
            if (runCatching { bb.strengthSupported }.getOrDefault(false)) {
                runCatching { bb.setStrength(saved.bassBoostStrength.toShort()) }
            }
        }
    }

    private fun readBands(eq: Equalizer): List<EqBand> {
        val range = eq.bandLevelRange
        return (0 until eq.numberOfBands).map { i ->
            val band = i.toShort()
            EqBand(
                index = i,
                centerFreqHz = eq.getCenterFreq(band) / 1000,
                levelMillibel = eq.getBandLevel(band).toInt(),
                minLevelMillibel = range[0].toInt(),
                maxLevelMillibel = range[1].toInt(),
            )
        }
    }

    private fun persist() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsStore.saveEqualizerSettings(
                EqualizerSettings(
                    enabled = state.enabled,
                    bandLevels = state.bands.map { it.levelMillibel.toShort() },
                    presetIndex = state.selectedPresetIndex,
                    bassBoostEnabled = state.bassBoostEnabled,
                    bassBoostStrength = state.bassBoostStrength,
                ),
            )
        }
    }

    override fun onCleared() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        super.onCleared()
    }
}

/**
 * The platform names its presets in English whatever the language of the app. The ones Android's equalizer
 * ships are named here; anything a vendor adds is shown as it comes.
 */
private fun frenchPresetName(name: String): String = when (name.trim().lowercase()) {
    "normal" -> "Normal"
    "classical" -> "Classique"
    "dance" -> "Dance"
    "flat" -> "Neutre"
    "folk" -> "Folk"
    "heavy metal" -> "Métal"
    "hip hop" -> "Hip-hop"
    "jazz" -> "Jazz"
    "pop" -> "Pop"
    "rock" -> "Rock"
    "latin" -> "Latino"
    "bass boost" -> "Basses renforcées"
    "treble boost" -> "Aigus renforcés"
    "vocal", "voice" -> "Voix"
    else -> name
}
