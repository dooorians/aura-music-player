package com.ikko.aura.reco

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ikko.aura.data.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What the listener decided about recommendations, kept on the device.
 *
 * [consent] is the one switch that lets the app send artist and song names — drawn from what the listener plays — to
 * the sources they use, to look for suggestions. It starts off. Without it nothing is sent, the profile is still kept
 * on the device and the playlists are made from the library alone.
 */
@Singleton
class RecoPreferences @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val consentKey = booleanPreferencesKey("reco_consent")
    private val seedsKey = stringPreferencesKey("reco_seed_artists")

    val consent: Flow<Boolean> = context.settingsDataStore.data.map { it[consentKey] ?: false }.distinctUntilChanged()

    /** Artists the listener typed in, for a profile with little to learn from. */
    val seedArtists: Flow<List<String>> = context.settingsDataStore.data
        .map { it[seedsKey].orEmpty().split('\u0000').filter(String::isNotBlank) }.distinctUntilChanged()

    private val pickedKey = stringPreferencesKey("reco_picked_artists")

    /** Artists chosen in the taste picker: a stronger statement than a name typed in, and kept as long as they stay in the list. */
    val pickedArtists: Flow<List<String>> = context.settingsDataStore.data
        .map { it[pickedKey].orEmpty().split('\u0000').filter(String::isNotBlank) }.distinctUntilChanged()

    suspend fun setPickedArtists(names: List<String>) {
        context.settingsDataStore.edit { it[pickedKey] = names.map { n -> n.trim() }.filter(String::isNotEmpty).distinct().joinToString("\u0000") }
    }

    private val versionKey = intPreferencesKey("reco_algorithm_version")

    /** The version of the selection rules the stored playlists were made with; 0 when none was. */
    suspend fun algorithmVersion(): Int = context.settingsDataStore.data.first()[versionKey] ?: 0

    suspend fun setAlgorithmVersion(v: Int) { context.settingsDataStore.edit { it[versionKey] = v } }

    suspend fun setConsent(on: Boolean) { context.settingsDataStore.edit { it[consentKey] = on } }

    suspend fun setSeedArtists(names: List<String>) {
        context.settingsDataStore.edit { it[seedsKey] = names.map { n -> n.trim() }.filter(String::isNotEmpty).distinct().joinToString("\u0000") }
    }
}
