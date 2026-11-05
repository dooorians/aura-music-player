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
 * Where the queue is kept between runs: one string in the same DataStore as the settings. Encoded and decoded off the
 * main thread — a queue that is the whole library is a lot of text.
 */
@Singleton
class QueueSnapshotStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("queue_snapshot")

    /** Lives as long as the process, not as long as whoever asked: see [writeDetached]. */
    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun read(): QueueSnapshot? = withContext(Dispatchers.Default) {
        QueueSnapshot.fromJson(runCatching { context.settingsDataStore.data.first()[key] }.getOrNull())
    }

    suspend fun write(snapshot: QueueSnapshot) {
        val json = withContext(Dispatchers.Default) { snapshot.toJson() }
        runCatching { context.settingsDataStore.edit { it[key] = json } }
    }

    /**
     * Writes [snapshot] even if the caller's scope is cancelled right after: the last word of a service being
     * destroyed, whose own scope goes in the same breath. Without it the final position — up to ten seconds of a
     * track, or the pause just made — was dropped, and the system killing the process next brought the queue back
     * from an older place.
     */
    fun writeDetached(snapshot: QueueSnapshot): Job = detached.launch { write(snapshot) }
}
