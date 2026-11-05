package com.ikko.aura.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Favourite albums, kept on disk.
 *
 * This used to be a [kotlinx.coroutines.flow.MutableStateFlow] and nothing else, so every
 * favourite was forgotten the moment the process died — a star that survives navigation and not
 * the app being closed is worse than no star at all, because it looks like it worked.
 *
 * Stored in the same DataStore as the settings rather than in Room: it is a set of ids that is
 * read whole and written whole, and a table would be three files of ceremony for that.
 */
@Singleton
class FavoritesStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = stringSetPreferencesKey("favorite_album_ids")

    /**
     * Kept hot for the app's lifetime ([SharingStarted.Eagerly]) rather than while subscribed:
     * Home reads it on first frame, and a favourites row that arrives a beat after the screen
     * does is the flicker this is meant to remove.
     */
    val favoriteAlbumIds: StateFlow<Set<Long>> = context.settingsDataStore.data
        .map { prefs -> prefs[key]?.mapNotNullTo(mutableSetOf()) { it.toLongOrNull() } ?: emptySet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun toggle(albumId: Long) {
        // 0 is "no album". Negative ids are valid: a streamed album's id is negative by design (never a MediaStore id).
        if (albumId == 0L) return
        scope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[key].orEmpty()
                val id = albumId.toString()
                prefs[key] = if (id in current) current - id else current + id
            }
        }
    }
}
