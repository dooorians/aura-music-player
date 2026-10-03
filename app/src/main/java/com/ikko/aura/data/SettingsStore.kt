package com.ikko.aura.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

/** Shared with [FavoritesStore]: one store for everything small the app has to remember. */
internal val Context.settingsDataStore by preferencesDataStore(name = "aura_settings")

data class EqualizerSettings(
    val enabled: Boolean = false,
    val bandLevels: List<Short> = emptyList(),
    val presetIndex: Int = -1,
    val bassBoostEnabled: Boolean = false,
    val bassBoostStrength: Int = 0,
)

@Singleton
class SettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val onlineLyricsKey = booleanPreferencesKey("online_lyrics")
    private val automixKey = booleanPreferencesKey("automix")
    private val ytmKey = booleanPreferencesKey("ytm_enabled")

    /**
     * Whether a track with no `.lrc` beside it may be looked up online. On by default —
     * it is what makes the feature work — but this is a local player reaching out to
     * three third-party hosts with a song title, so it is a switch and not a given.
     */
    val onlineLyricsEnabled = context.settingsDataStore.data.map { prefs ->
        prefs[onlineLyricsKey] ?: true
    }

    val automixEnabled = context.settingsDataStore.data.map { prefs ->
        prefs[automixKey] ?: false
    }

    /**
     * The YouTube Music source. Off by default and unofficial: it is a switch the user turns on knowing what it is,
     * and the way to stop it at once.
     */
    val ytmEnabled = context.settingsDataStore.data.map { prefs -> prefs[ytmKey] ?: false }

    suspend fun setYtmEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[ytmKey] = enabled }
    }

    suspend fun setOnlineLyricsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[onlineLyricsKey] = enabled }
    }

    suspend fun setAutomixEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[automixKey] = enabled }
    }

    private val eqEnabledKey = booleanPreferencesKey("eq_enabled")
    private val eqBandLevelsKey = stringPreferencesKey("eq_band_levels")
    private val eqPresetKey = intPreferencesKey("eq_preset_index")
    private val eqBassBoostEnabledKey = booleanPreferencesKey("eq_bass_boost_enabled")
    private val eqBassBoostStrengthKey = intPreferencesKey("eq_bass_boost_strength")

    val equalizerSettings = context.settingsDataStore.data.map { prefs ->
        EqualizerSettings(
            enabled = prefs[eqEnabledKey] ?: false,
            bandLevels = prefs[eqBandLevelsKey]
                ?.takeIf { it.isNotBlank() }
                ?.split(",")
                ?.mapNotNull { it.toShortOrNull() }
                ?: emptyList(),
            presetIndex = prefs[eqPresetKey] ?: -1,
            bassBoostEnabled = prefs[eqBassBoostEnabledKey] ?: false,
            bassBoostStrength = prefs[eqBassBoostStrengthKey] ?: 0,
        )
    }

    suspend fun saveEqualizerSettings(settings: EqualizerSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[eqEnabledKey] = settings.enabled
            prefs[eqBandLevelsKey] = settings.bandLevels.joinToString(",")
            prefs[eqPresetKey] = settings.presetIndex
            prefs[eqBassBoostEnabledKey] = settings.bassBoostEnabled
            prefs[eqBassBoostStrengthKey] = settings.bassBoostStrength
        }
    }
}
