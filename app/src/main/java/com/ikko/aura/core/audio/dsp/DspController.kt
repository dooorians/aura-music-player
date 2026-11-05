package com.ikko.aura.core.audio.dsp

import com.ikko.aura.data.model.AudioOutputType
import com.ikko.aura.player.AudioOutputMonitor
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The one owner of the equaliser's state: what is saved, what the audio thread hears ([parameters]) and what the
 * screen shows all go through here.
 *
 * Two speeds. [previewBand] is for a finger on a slider: it reaches the audio at once and touches nothing else — no
 * flow emission, no disk. Everything else (and the end of a drag, [commitBands]) updates [settings] and is saved,
 * writes being coalesced over [SAVE_DEBOUNCE_MS].
 */
@Singleton
class DspController @Inject constructor(
    private val repository: DspSettingsRepository,
    audioOutputMonitor: AudioOutputMonitor,
) {
    val parameters = DspParameters()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _settings = MutableStateFlow(DspSettings())
    val settings: StateFlow<DspSettings> = _settings.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val customProfiles: StateFlow<List<EqProfile>> =
        repository.customProfiles.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val deviceProfiles: StateFlow<Map<String, String>> =
        repository.deviceProfiles.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The Bluetooth device the music is going to now, by name; null on any other output. */
    val bluetoothDevice: StateFlow<String?> = audioOutputMonitor.state
        .map { state -> state.deviceName?.takeIf { state.type == AudioOutputType.BLUETOOTH } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    private var saveJob: Job? = null

    /** The bands as the audio hears them while a finger drags; copied on each preview, never shared. */
    private val liveBands = FloatArray(IsoBands.COUNT)

    init {
        scope.launch {
            val saved = runCatching { repository.current() }.getOrDefault(DspSettings())
            publish(saved, persist = false)
            _loaded.value = true
            // A device with a profile of its own gets it when it becomes the output. Read from the stored flows, not
            // the state flows above, so a cold start does not look the device up before its profile has loaded.
            combine(bluetoothDevice, repository.deviceProfiles, repository.customProfiles) { device, devices, custom ->
                val id = device?.let { devices[it] }
                BuiltInProfiles.byId(id) ?: custom.firstOrNull { it.id == id }
            }
                .distinctUntilChangedBy { it?.id }
                .collect { profile -> if (profile != null && _settings.value.profileId != profile.id) applyProfile(profile) }
        }
    }

    fun setEnabled(enabled: Boolean) = edit { it.copy(enabled = enabled) }

    fun setNormalization(enabled: Boolean) = edit { it.copy(normalizationEnabled = enabled) }

    fun setTargetLufs(lufs: Float) = edit { it.copy(targetLufs = lufs.coerceIn(MIN_TARGET_LUFS, MAX_TARGET_LUFS)) }

    fun setPreamp(db: Float) = edit { it.copy(preampDb = db.coerceIn(-MAX_PREAMP_DB, MAX_PREAMP_DB)) }

    fun setAutoPreamp(enabled: Boolean) = edit { it.copy(autoPreamp = enabled) }

    /** A slider moving: heard at once, saved at [commitBands]. No flow emits and nothing is written. */
    @Synchronized
    fun previewBand(band: Int, gainDb: Float) {
        if (band !in 0 until IsoBands.COUNT) return
        liveBands[band] = gainDb.coerceIn(-IsoBands.MAX_GAIN_DB, IsoBands.MAX_GAIN_DB)
        val base = _settings.value
        parameters.set(base.copy(bandGainsDb = liveBands.toList(), parametric = null).toConfig())
    }

    /** The end of a drag: the bands become the settings, and the curve is now the listener's own. */
    fun commitBands(gainsDb: List<Float>) = edit {
        it.copy(bandGainsDb = gainsDb.map { g -> g.coerceIn(-IsoBands.MAX_GAIN_DB, IsoBands.MAX_GAIN_DB) }, parametric = null, profileId = null)
    }

    fun resetFlat() = applyProfile(BuiltInProfiles.byId(BuiltInProfiles.FLAT_ID)!!)

    fun applyProfile(profile: EqProfile) = edit {
        it.copy(
            bandGainsDb = profile.bandGainsDb.takeIf { b -> b.size == IsoBands.COUNT } ?: FLAT_BANDS,
            parametric = profile.parametric?.map(StoredFilter::toSpec)?.takeIf { p -> p.isNotEmpty() },
            preampDb = profile.preampDb,
            profileId = profile.id,
        )
    }

    /** Saves the current curve under [name]; a custom profile of the same name is replaced. */
    fun saveCurrentAs(name: String) {
        val trimmed = name.trim().take(MAX_NAME_LENGTH).ifEmpty { return }
        val current = _settings.value
        scope.launch {
            val existing = customProfiles.value
            val id = existing.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.id ?: "custom:${UUID.randomUUID()}"
            val profile = EqProfile(
                id = id,
                name = trimmed,
                bandGainsDb = current.bandGainsDb,
                parametric = current.parametric?.map(StoredFilter::of),
                preampDb = current.preampDb,
            )
            repository.saveCustomProfiles(existing.filterNot { it.id == id } + profile)
            edit { it.copy(profileId = id) }
        }
    }

    fun deleteProfile(id: String) {
        if (BuiltInProfiles.byId(id) != null) return
        scope.launch {
            repository.saveCustomProfiles(customProfiles.value.filterNot { it.id == id })
            repository.saveDeviceProfiles(deviceProfiles.value.filterValues { it != id })
            if (_settings.value.profileId == id) edit { it.copy(profileId = null) }
        }
    }

    /**
     * Imports an AutoEq ParametricEQ.txt as a custom profile and applies it. Returns what was read, or null when the
     * text is not an AutoEq profile.
     */
    fun importAutoEq(name: String, text: String): AutoEqParser.Result? {
        val result = AutoEqParser.parse(text) ?: return null
        val profile = EqProfile(
            id = "custom:${UUID.randomUUID()}",
            name = name.trim().take(MAX_NAME_LENGTH).ifEmpty { "AutoEq" },
            parametric = result.filters.map(StoredFilter::of),
            preampDb = result.preampDb.toFloat().coerceIn(-MAX_PREAMP_DB, MAX_PREAMP_DB),
        )
        scope.launch { repository.saveCustomProfiles(customProfiles.value + profile) }
        applyProfile(profile)
        return result
    }

    /** Ties the profile in use to the Bluetooth device playing now (or unties it with null). */
    fun associateCurrentDevice(profileId: String?) {
        val device = bluetoothDevice.value ?: return
        scope.launch {
            val map = deviceProfiles.value.toMutableMap()
            if (profileId == null) map.remove(device) else map[device] = profileId
            repository.saveDeviceProfiles(map)
        }
    }

    fun profileById(id: String?): EqProfile? = BuiltInProfiles.byId(id) ?: customProfiles.value.firstOrNull { it.id == id }

    private fun edit(transform: (DspSettings) -> DspSettings) {
        var next: DspSettings
        while (true) {
            val prev = _settings.value
            next = transform(prev)
            if (_settings.compareAndSet(prev, next)) break
        }
        publish(next, persist = true)
    }

    @Synchronized
    private fun publish(settings: DspSettings, persist: Boolean) {
        _settings.value = settings
        settings.bandGainsDb.forEachIndexed { i, g -> if (i < liveBands.size) liveBands[i] = g }
        parameters.set(settings.toConfig())
        if (!persist) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            repository.save(_settings.value)
        }
    }

    companion object {
        const val MIN_TARGET_LUFS = -23f
        const val MAX_TARGET_LUFS = -8f
        const val MAX_PREAMP_DB = 12f
        private const val MAX_NAME_LENGTH = 40
        private const val SAVE_DEBOUNCE_MS = 300L
    }
}
