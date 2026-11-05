package com.ikko.aura.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ikko.aura.data.settingsDataStore
import com.ikko.aura.playback.QueueSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Where the queue is kept between runs: two files of its own ([QueueSnapshotFiles]), so a position tick no longer
 * rewrites the whole queue and every setting with it. A queue written by an older version, as one string in the
 * settings' DataStore, is still read, and that string is removed once the queue has been written the new way.
 */
@Singleton
class QueueSnapshotStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("queue_snapshot")
    private val files = QueueSnapshotFiles(java.io.File(context.filesDir, "queue"))
    @Volatile private var legacyCleared = false

    /** Lives as long as the process, not as long as whoever asked: see [writeDetached]. */
    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun read(): QueueSnapshot? = files.read() ?: readLegacy()

    suspend fun write(snapshot: QueueSnapshot) {
        files.write(snapshot)
        if (!legacyCleared && files.exists()) {
            legacyCleared = true
            runCatching {
                // Read first: the settings' file is only rewritten when it still holds the old queue.
                if (context.settingsDataStore.data.first().contains(key)) context.settingsDataStore.edit { it.remove(key) }
            }
        }
    }

    private suspend fun readLegacy(): QueueSnapshot? = withContext(Dispatchers.Default) {
        QueueSnapshot.fromJson(runCatching { context.settingsDataStore.data.first()[key] }.getOrNull())
    }

    /**
     * Writes [snapshot] even if the caller's scope is cancelled right after: the last word of a service being
     * destroyed, whose own scope goes in the same breath. Without it the final position — up to ten seconds of a
     * track, or the pause just made — was dropped, and the system killing the process next brought the queue back
     * from an older place.
     */
    fun writeDetached(snapshot: QueueSnapshot): Job = detached.launch { runCatching { write(snapshot) } }
}
