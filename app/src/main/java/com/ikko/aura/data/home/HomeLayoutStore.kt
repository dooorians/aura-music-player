package com.ikko.aura.data.home

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ikko.aura.data.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Home layout, kept on disk in the settings DataStore as one JSON string (see [HomeLayoutCodec]).
 *
 * The in-memory list is the source of truth and changes the instant [update] is called; the disk follows. A drag
 * reorders widgets many times a second, and waiting for a DataStore round trip between two moves would have each move
 * decided against a layout that was already out of date. Writes are serialised and always write the latest list, so the
 * last word is never lost to an earlier write finishing late.
 *
 * [widgets] is null until the first read of the disk is done: Home shows nothing for those few milliseconds rather than
 * the default layout followed by the saved one.
 */
@Singleton
class HomeLayoutStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = stringPreferencesKey("home_widgets_v1")
    private val writes = Mutex()
    private val state = MutableStateFlow<List<HomeWidget>?>(null)

    val widgets: StateFlow<List<HomeWidget>?> = state

    init {
        scope.launch {
            val saved = HomeLayoutCodec.decode(context.settingsDataStore.data.first()[key])
            // An edit made before the read finished has already replaced null, and stands.
            state.compareAndSet(null, saved ?: defaultHomeLayout())
        }
    }

    fun update(transform: (List<HomeWidget>) -> List<HomeWidget>) {
        while (true) {
            val current = state.value ?: return
            val next = transform(current)
            if (next == current) return
            if (state.compareAndSet(current, next)) break
        }
        persist()
    }

    /** Back to the layout a fresh install has. */
    fun reset() = update { defaultHomeLayout() }

    private fun persist() {
        scope.launch {
            writes.withLock {
                val latest = state.value ?: return@withLock
                context.settingsDataStore.edit { it[key] = HomeLayoutCodec.encode(latest) }
            }
        }
    }
}
