package com.ikko.aura.core.audio.dsp

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** What the listener has set, as it is kept between launches. */
data class DspSettings(
    /** The main switch: off is the bypass. */
    val enabled: Boolean = false,
    val bandGainsDb: List<Float> = FLAT_BANDS,
    /** An imported AutoEq curve, which replaces the bands while it is set. */
    val parametric: List<FilterSpec>? = null,
    val preampDb: Float = 0f,
    val autoPreamp: Boolean = true,
    val normalizationEnabled: Boolean = false,
    val targetLufs: Float = LoudnessNormalizer.DEFAULT_TARGET_LUFS.toFloat(),
    /** The profile the curve came from, until it is changed by hand. */
    val profileId: String? = BuiltInProfiles.FLAT_ID,
) {
    fun toConfig(): DspConfig = DspConfig(
        bypass = !enabled,
        normalizationEnabled = normalizationEnabled,
        targetLufs = targetLufs.toDouble(),
        preampDb = preampDb.toDouble(),
        autoPreamp = autoPreamp,
        filters = parametric ?: bandGainsDb.indices.map { IsoBands.spec(it, bandGainsDb[it]) },
    )
}

/**
 * The equaliser's own DataStore file, apart from the app's general settings so the two never contend for a write
 * (a slider drag ends in one; the queue snapshot writes the other every few seconds).
 */
private val Context.dspDataStore by preferencesDataStore(name = "dsp_equalizer")

@Singleton
class DspSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val filtersSerializer = ListSerializer(StoredFilter.serializer())
    private val profilesSerializer = ListSerializer(EqProfile.serializer())
    private val devicesSerializer = MapSerializer(String.serializer(), String.serializer())

    private val enabledKey = booleanPreferencesKey("enabled")
    private val bandsKey = stringPreferencesKey("bands_db")
    private val parametricKey = stringPreferencesKey("parametric")
    private val preampKey = floatPreferencesKey("preamp_db")
    private val autoPreampKey = booleanPreferencesKey("auto_preamp")
    private val normalizationKey = booleanPreferencesKey("normalization")
    private val targetKey = floatPreferencesKey("target_lufs")
    private val profileKey = stringPreferencesKey("profile_id")
    private val customProfilesKey = stringPreferencesKey("custom_profiles")
    private val deviceProfilesKey = stringPreferencesKey("device_profiles")

    val settings: Flow<DspSettings> = context.dspDataStore.data.map(::readSettings).distinctUntilChanged()

    val customProfiles: Flow<List<EqProfile>> = context.dspDataStore.data
        .map { prefs -> decode(prefs[customProfilesKey], profilesSerializer).orEmpty() }
        .distinctUntilChanged()

    /** Output device name → profile id: the profile applied when that device becomes the output. */
    val deviceProfiles: Flow<Map<String, String>> = context.dspDataStore.data
        .map { prefs -> decode(prefs[deviceProfilesKey], devicesSerializer).orEmpty() }
        .distinctUntilChanged()

    suspend fun current(): DspSettings = settings.first()

    suspend fun save(settings: DspSettings) {
        context.dspDataStore.edit { prefs ->
            prefs[enabledKey] = settings.enabled
            prefs[bandsKey] = settings.bandGainsDb.joinToString(",")
            val parametric = settings.parametric
            if (parametric == null) prefs.remove(parametricKey) else prefs[parametricKey] = json.encodeToString(filtersSerializer, parametric.map(StoredFilter::of))
            prefs[preampKey] = settings.preampDb
            prefs[autoPreampKey] = settings.autoPreamp
            prefs[normalizationKey] = settings.normalizationEnabled
            prefs[targetKey] = settings.targetLufs
            val profile = settings.profileId
            if (profile == null) prefs.remove(profileKey) else prefs[profileKey] = profile
        }
    }

    suspend fun saveCustomProfiles(profiles: List<EqProfile>) {
        context.dspDataStore.edit { it[customProfilesKey] = json.encodeToString(profilesSerializer, profiles) }
    }

    suspend fun saveDeviceProfiles(map: Map<String, String>) {
        context.dspDataStore.edit { it[deviceProfilesKey] = json.encodeToString(devicesSerializer, map) }
    }

    private fun readSettings(prefs: Preferences): DspSettings {
        val defaults = DspSettings()
        val bands = prefs[bandsKey]?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
            ?.takeIf { it.size == IsoBands.COUNT }
            ?.map { it.coerceIn(-IsoBands.MAX_GAIN_DB, IsoBands.MAX_GAIN_DB) }
            ?: defaults.bandGainsDb
        return DspSettings(
            enabled = prefs[enabledKey] ?: defaults.enabled,
            bandGainsDb = bands,
            parametric = decode(prefs[parametricKey], filtersSerializer)?.map(StoredFilter::toSpec)?.takeIf { it.isNotEmpty() },
            preampDb = prefs[preampKey] ?: defaults.preampDb,
            autoPreamp = prefs[autoPreampKey] ?: defaults.autoPreamp,
            normalizationEnabled = prefs[normalizationKey] ?: defaults.normalizationEnabled,
            targetLufs = prefs[targetKey] ?: defaults.targetLufs,
            profileId = if (profileKey in prefs) prefs[profileKey] else defaults.profileId,
        )
    }

    private fun <T> decode(raw: String?, serializer: kotlinx.serialization.KSerializer<T>): T? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
}
