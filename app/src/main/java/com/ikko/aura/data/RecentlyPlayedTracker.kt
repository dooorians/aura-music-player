package com.ikko.aura.data

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
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _recentAlbumIds = MutableStateFlow<List<Long>>(emptyList())
    val recentAlbumIds: StateFlow<List<Long>> = _recentAlbumIds.asStateFlow()

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

    fun recordPlayed(albumId: Long) {
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
