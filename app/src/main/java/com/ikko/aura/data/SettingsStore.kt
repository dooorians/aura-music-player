package com.ikko.aura.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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

enum class AlbumLayoutMode(val label: String) {
    GRID_2("Grille 2 colonnes"),
    GRID_3("Grille 3 colonnes"),
    LIST("Liste"),
}

enum class TrackLayoutMode(val label: String) {
    LIST("Liste"),
    GRID_2("Grille 2 colonnes"),
}

@Singleton
class SettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val albumLayoutKey = stringPreferencesKey("library_album_layout")
    private val trackLayoutKey = stringPreferencesKey("library_track_layout")
    private val onlineLyricsKey = booleanPreferencesKey("online_lyrics")
    private val automixKey = booleanPreferencesKey("automix")
    private val ytmKey = booleanPreferencesKey("ytm_enabled")
    private val profileImageUriKey = stringPreferencesKey("profile_image_uri")
    private val profileFirstNameKey = stringPreferencesKey("profile_first_name")
    private val amoledBackgroundKey = booleanPreferencesKey("amoled_background")
    private val shuffleKey = booleanPreferencesKey("playback_shuffle_enabled")

    /**
     * One setting, read from the shared DataStore, emitting only when its own value changes. The store re-emits every
     * mapped value on any write to the file — the queue snapshot included, every few seconds while music plays — and
     * without this each write woke every collector: the queue writer (which then wrote again, a loop for as long as
     * music played), the library re-sort, the Home widgets.
     */
    private fun <T> setting(read: (Preferences) -> T): Flow<T> = context.settingsDataStore.data.map(read).distinctUntilChanged()

    val profileImageUri = setting { prefs -> prefs[profileImageUriKey] }
    val profileFirstName = setting { prefs -> prefs[profileFirstNameKey].orEmpty() }
    val amoledBackgroundEnabled = setting { prefs -> prefs[amoledBackgroundKey] ?: false }
    val shuffleEnabled = setting { prefs -> prefs[shuffleKey] ?: false }

    /** The Home cards' order (ids, comma-separated; the ones not listed follow in their usual order) and the hidden ones. */
    val homeCardOrder = setting { prefs -> prefs[stringPreferencesKey("home_card_order")].orEmpty().split(',').filter { it.isNotBlank() } }
    val homeCardHidden = setting { prefs -> prefs[stringPreferencesKey("home_card_hidden")].orEmpty().split(',').filter { it.isNotBlank() }.toSet() }

    suspend fun setHomeCardOrder(ids: List<String>) {
        context.settingsDataStore.edit { it[stringPreferencesKey("home_card_order")] = ids.joinToString(",") }
    }

    suspend fun setHomeCardHidden(ids: Set<String>) {
        context.settingsDataStore.edit { it[stringPreferencesKey("home_card_hidden")] = ids.joinToString(",") }
    }

    /** The playlists songs were last added to, most recent first. */
    val recentPlaylistIds = setting { prefs ->
        prefs[stringPreferencesKey("recent_playlists")].orEmpty().split(',').mapNotNull { it.toLongOrNull() }
    }

    suspend fun markPlaylistUsed(id: Long) {
        context.settingsDataStore.edit { prefs ->
            val key = stringPreferencesKey("recent_playlists")
            val now = (listOf(id) + prefs[key].orEmpty().split(',').mapNotNull { it.toLongOrNull() }.filter { it != id }).take(6)
            prefs[key] = now.joinToString(",")
        }
    }

    /** The playlist Aura made for [key] ("Découverte"…) and when it last filled it: `id:atMs`, or null if never. */
    suspend fun autoPlaylist(key: String): Pair<Long, Long>? =
        context.settingsDataStore.data.first()[stringPreferencesKey("auto_playlist_$key")]
            ?.split(':')?.takeIf { it.size == 2 }
            ?.let { (id, at) -> (id.toLongOrNull() ?: return null) to (at.toLongOrNull() ?: return null) }

    suspend fun setAutoPlaylist(key: String, playlistId: Long, atMs: Long) {
        context.settingsDataStore.edit { it[stringPreferencesKey("auto_playlist_$key")] = "$playlistId:$atMs" }
    }

    suspend fun setProfileImageUri(uri: String?) {
        context.settingsDataStore.edit { prefs ->
            if (uri == null) prefs.remove(profileImageUriKey) else prefs[profileImageUriKey] = uri
        }
    }

    suspend fun setProfileFirstName(name: String) {
        context.settingsDataStore.edit { it[profileFirstNameKey] = name.trim() }
    }

    suspend fun setAmoledBackgroundEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[amoledBackgroundKey] = enabled }
    }

    suspend fun setShuffleEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[shuffleKey] = enabled }
    }

    val albumLayoutMode = setting { prefs ->
        prefs[albumLayoutKey]?.let { name ->
            runCatching { AlbumLayoutMode.valueOf(name) }.getOrNull()
        } ?: AlbumLayoutMode.GRID_3
    }

    val trackLayoutMode = setting { prefs ->
        prefs[trackLayoutKey]?.let { name ->
            runCatching { TrackLayoutMode.valueOf(name) }.getOrNull()
        } ?: TrackLayoutMode.GRID_2
    }

    suspend fun setAlbumLayoutMode(mode: AlbumLayoutMode) {
        context.settingsDataStore.edit { it[albumLayoutKey] = mode.name }
    }

    suspend fun setTrackLayoutMode(mode: TrackLayoutMode) {
        context.settingsDataStore.edit { it[trackLayoutKey] = mode.name }
    }

    /**
     * Whether a track with no `.lrc` beside it may be looked up online. On by default —
     * it is what makes the feature work — but this is a local player reaching out to
     * three third-party hosts with a song title, so it is a switch and not a given.
     */
    val onlineLyricsEnabled = setting { prefs ->
        prefs[onlineLyricsKey] ?: true
    }

    val automixEnabled = setting { prefs ->
        prefs[automixKey] ?: false
    }

    /**
     * The YouTube Music source. Off by default and unofficial: it is a switch the user turns on knowing what it is,
     * and the way to stop it at once.
     */
    val ytmEnabled = setting { prefs -> prefs[ytmKey] ?: false }

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

    val equalizerSettings = setting { prefs ->
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
