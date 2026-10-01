package com.ikko.aura.source

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ikko.aura.data.settingsDataStore
import com.ikko.aura.source.remote.RemoteSources
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Which source comes first. One list for them all — the device's own files ([LOCAL_ENTRY]), YouTube Music
 * ([YTM_ENTRY]) and every source the user added — set by dragging in Réglages. It decides the order the search lists
 * them in; a source higher up is asked, and shown, before one lower down.
 */
@Singleton
class SourceOrder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val remote: RemoteSources,
) {
    private val key = stringPreferencesKey("source_order")

    /** The entries in priority order: what was stored, then whatever is new, in the order it was added. */
    val order: Flow<List<String>> = combine(
        context.settingsDataStore.data.map { it[key].orEmpty().split(SEPARATOR).filter(String::isNotEmpty) },
        remote.configs,
    ) { stored, configs -> merge(stored, listOf(LOCAL_ENTRY, JIOSAAVN_ENTRY, YTM_ENTRY) + configs.map { it.id }) }

    /** Moves the entry at [from] to [to]. */
    suspend fun move(from: Int, to: Int) {
        val current = order.first().toMutableList()
        if (from !in current.indices || to !in current.indices || from == to) return
        current.add(to, current.removeAt(from))
        context.settingsDataStore.edit { it[key] = current.joinToString(SEPARATOR) }
    }

    companion object {
        const val LOCAL_ENTRY = "local"
        const val YTM_ENTRY = "ytm"
        const val JIOSAAVN_ENTRY = "jiosaavn"
        private const val SEPARATOR = ","

        /** [stored] kept in its order, minus what no longer exists, then what [available] has that it did not know. */
        fun merge(stored: List<String>, available: List<String>): List<String> {
            val known = stored.filter { it in available }.distinct()
            return known + available.filter { it !in known }
        }
    }
}
