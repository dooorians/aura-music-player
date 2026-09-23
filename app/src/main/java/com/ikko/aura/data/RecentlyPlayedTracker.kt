package com.ikko.aura.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The session's "recently played albums" ordering, and the durable play counts behind it.
 *
 * The ordering is in memory on purpose: it answers "what have you just been listening to", and
 * that question resets when you put the phone down. The counts are in Room, because the question
 * Home's "Redécouvrir" row asks — what have you *not* been listening to — cannot be answered from
 * one session at all.
 */
@Singleton
class RecentlyPlayedTracker @Inject constructor(
    private val albumPlaysDao: AlbumPlaysDao,
    @param:ApplicationContext private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _recentAlbumIds = MutableStateFlow<List<Long>>(emptyList())
    val recentAlbumIds: StateFlow<List<Long>> = _recentAlbumIds.asStateFlow()

    /**
     * The tracks themselves, most recent first — what Home's "écoutés récemment" is a list of.
     *
     * Kept in the settings store as one ordered string rather than in a table: it is a short list read
     * whole and written whole, and the order is the whole point, which a set of ids cannot keep.
     */
    private val trackKey = stringPreferencesKey("recent_track_ids")
    val recentTrackIds: StateFlow<List<String>> = context.settingsDataStore.data
        .map { prefs -> prefs[trackKey]?.split('\u0000')?.filter { it.isNotEmpty() } ?: emptyList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Every album's running play count, for Home's "Redécouvrir" row. */
    val playCounts: Flow<List<AlbumPlayEntity>> = albumPlaysDao.observeAll()

    init {
        // Seeded from disk so "écoutés récemment" is not empty every time the app is opened.
        // The session's own plays always win: if anything has already been recorded by the time
        // this read comes back, the seed is dropped rather than pushed in front of it.
        scope.launch {
            val stored = runCatching { albumPlaysDao.recentAlbumIds(MAX_ENTRIES) }.getOrDefault(emptyList())
            if (stored.isNotEmpty()) {
                _recentAlbumIds.update { current -> current.ifEmpty { stored } }
            }
        }
    }

    /** Remembers a track as the latest thing played, and its album with it. */
    fun recordPlayed(albumId: Long, trackId: String? = null) {
        if (!trackId.isNullOrEmpty()) {
            scope.launch {
                runCatching {
                    context.settingsDataStore.edit { prefs ->
                        val current = prefs[trackKey]?.split('\u0000')?.filter { it.isNotEmpty() }.orEmpty()
                        // Separated by a character a media id cannot contain, so an id with a comma in it
                        // cannot split one entry into two.
                        prefs[trackKey] = (listOf(trackId) + current.filterNot { it == trackId })
                            .take(MAX_ENTRIES)
                            .joinToString("\u0000")
                    }
                }
            }
        }
        if (albumId <= 0) return
        _recentAlbumIds.update { current ->
            (listOf(albumId) + current.filterNot { it == albumId }).take(MAX_ENTRIES)
        }
        // Best effort and deliberately not awaited: this is called from a player callback, and a
        // play that fails to be counted is worth less than a track change that stutters.
        scope.launch { runCatching { albumPlaysDao.record(albumId, System.currentTimeMillis()) } }
    }

    companion object {
        private const val MAX_ENTRIES = 20
    }
}
