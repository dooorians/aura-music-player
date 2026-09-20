package com.ikko.aura.player.smart

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only access to what Automix has measured, for callers outside the playback service.
 *
 * [TrackAnalyzer] owns the writing side and lives in the service. This reads the same files —
 * one per track — which is all [SmartShuffle] needs, and means the library screen does not have
 * to reach into the player to ask.
 */
@Singleton
class AnalysisReader @Inject constructor(@ApplicationContext context: Context) {

    private val store = AnalysisStore(context)

    /**
     * Loads what is stored for each of [trackIds]. One file read apiece, off the main thread —
     * a library of a few thousand is a few milliseconds, and an id with nothing stored is
     * remembered as absent so a second pass costs no I/O at all.
     */
    suspend fun analysesFor(trackIds: List<String>): Map<String, TrackAnalysis> =
        withContext(Dispatchers.IO) {
            buildMap {
                trackIds.forEach { id -> store.load(id)?.let { put(id, it) } }
            }
        }
}
