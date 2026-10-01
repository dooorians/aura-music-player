package com.ikko.aura.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ikko.aura.data.settingsDataStore
import com.ikko.aura.playback.QueueSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
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

    suspend fun read(): QueueSnapshot? = withContext(Dispatchers.Default) {
        QueueSnapshot.fromJson(runCatching { context.settingsDataStore.data.first()[key] }.getOrNull())
    }

    suspend fun write(snapshot: QueueSnapshot) {
        val json = withContext(Dispatchers.Default) { snapshot.toJson() }
        runCatching { context.settingsDataStore.edit { it[key] = json } }
    }
}
