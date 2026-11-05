package com.ikko.aura.ui.equalizer

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.core.audio.dsp.BuiltInProfiles
import com.ikko.aura.core.audio.dsp.DspController
import com.ikko.aura.core.audio.dsp.EqProfile
import com.ikko.aura.core.audio.dsp.FilterSpec
import com.ikko.aura.core.audio.dsp.IsoBands
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The ten gains as the screen draws them. Each is its own primitive float state, so a drag writes one float and
 * invalidates only what reads it — the slider and the curve's draw pass — with no recomposition of the screen and
 * no list copied per frame.
 */
@Stable
class EqBandsState {
    val gains: List<MutableFloatState> = List(IsoBands.COUNT) { mutableFloatStateOf(0f) }

    operator fun get(band: Int): Float = gains[band].floatValue

    fun copyInto(out: FloatArray) {
        for (i in gains.indices) out[i] = gains[i].floatValue
    }

    fun snapshot(): List<Float> = gains.map { it.floatValue }
}

@Immutable
data class EqualizerUiState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val normalizationEnabled: Boolean = false,
    val targetLufs: Float = -14f,
    val preampDb: Float = 0f,
    val autoPreamp: Boolean = true,
    /** The automatic part of the preamp, ≤ 0 dB. */
    val headroomDb: Float = 0f,
    val builtInProfiles: List<EqProfile> = BuiltInProfiles.all,
    val customProfiles: List<EqProfile> = emptyList(),
    val profileId: String? = BuiltInProfiles.FLAT_ID,
    val profileName: String? = "Neutre",
    /** An AutoEq curve in place of the bands, or null. */
    val parametric: List<FilterSpec>? = null,
    val bluetoothDevice: String? = null,
    /** The profile tied to [bluetoothDevice], if any. */
    val deviceProfileId: String? = null,
)

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val controller: DspController,
) : ViewModel() {

    val bands = EqBandsState()

    /** Bands under a finger are not overwritten by the settings coming back. */
    private val dragging = BooleanArray(IsoBands.COUNT)

    val uiState: StateFlow<EqualizerUiState> = combine(
        controller.settings,
        controller.customProfiles,
        controller.bluetoothDevice,
        controller.deviceProfiles,
        controller.loaded,
    ) { settings, custom, device, devices, loaded ->
        val config = settings.toConfig()
        EqualizerUiState(
            loaded = loaded,
            enabled = settings.enabled,
            normalizationEnabled = settings.normalizationEnabled,
            targetLufs = settings.targetLufs,
            preampDb = settings.preampDb,
            autoPreamp = settings.autoPreamp,
            headroomDb = config.headroomDb.toFloat(),
            customProfiles = custom,
            profileId = settings.profileId,
            profileName = controller.profileById(settings.profileId)?.name,
            parametric = settings.parametric,
            bluetoothDevice = device,
            deviceProfileId = device?.let { devices[it] },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EqualizerUiState())

    init {
        viewModelScope.launch {
            controller.settings.collect { settings ->
                settings.bandGainsDb.forEachIndexed { i, g -> if (i < IsoBands.COUNT && !dragging[i]) bands.gains[i].floatValue = g }
            }
        }
    }

    fun onBandDragStart(band: Int) {
        dragging[band] = true
    }

    fun onBandChange(band: Int, gainDb: Float) {
        val quantised = quantise(gainDb)
        bands.gains[band].floatValue = quantised
        controller.previewBand(band, quantised)
    }

    fun onBandDragEnd(band: Int) {
        dragging[band] = false
        controller.commitBands(bands.snapshot())
    }

    /** A double tap on a band: back to 0 dB. */
    fun resetBand(band: Int) {
        bands.gains[band].floatValue = 0f
        controller.commitBands(bands.snapshot())
    }

    fun setEnabled(enabled: Boolean) = controller.setEnabled(enabled)
    fun setNormalization(enabled: Boolean) = controller.setNormalization(enabled)
    fun setTargetLufs(lufs: Float) = controller.setTargetLufs(lufs.roundToInt().toFloat())
    fun setPreamp(db: Float) = controller.setPreamp(quantise(db))
    fun setAutoPreamp(enabled: Boolean) = controller.setAutoPreamp(enabled)
    fun applyProfile(profile: EqProfile) = controller.applyProfile(profile)
    fun resetFlat() = controller.resetFlat()
    fun saveAs(name: String) = controller.saveCurrentAs(name)
    fun deleteProfile(id: String) = controller.deleteProfile(id)

    /** True when [text] was an AutoEq profile, now applied. */
    fun importAutoEq(name: String, text: String): Boolean = controller.importAutoEq(name, text) != null

    fun setDeviceAssociation(associate: Boolean) {
        controller.associateCurrentDevice(if (associate) uiState.value.profileId else null)
    }

    companion object {
        /** Gains move in tenths of a dB: finer than anyone hears, coarse enough for [GainLabels] to hold every one. */
        fun quantise(db: Float): Float =
            ((db * 10f).roundToInt() / 10f).coerceIn(-IsoBands.MAX_GAIN_DB, IsoBands.MAX_GAIN_DB)
    }
}
