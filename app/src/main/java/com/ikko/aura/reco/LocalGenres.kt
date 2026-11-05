package com.ikko.aura.reco

import android.content.Context
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The genre each local file carries, from the device's media index (MediaStore). Only what the files' tags say: a file
 * with no genre tag has none, and nothing is inferred for it.
 */
@Singleton
class LocalGenres @Inject constructor(@param:ApplicationContext private val context: Context) {

    @Volatile private var cache: Pair<Long, Map<String, String>>? = null

    /** MediaStore audio id (the local track's id) → genre name. */
    suspend fun byTrackId(): Map<String, String> {
        cache?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.let { return it.second }
        val map = withContext(Dispatchers.IO) { runCatching { read() }.getOrDefault(emptyMap()) }
        cache = System.currentTimeMillis() to map
        return map
    }

    private fun read(): Map<String, String> {
        val out = HashMap<String, String>()
        val resolver = context.contentResolver
        resolver.query(
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
            null, null, null,
        )?.use { genres ->
            while (genres.moveToNext()) {
                val id = genres.getLong(0)
                // Variants fold into one genre ("Hip Hop" and "Hip-Hop"), ID3 numbers become names, "Other" and "Unknown" are none.
                val name = GenreNormalizer.normalize(genres.getString(1)) ?: continue
                resolver.query(
                    MediaStore.Audio.Genres.Members.getContentUri("external", id),
                    arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null,
                )?.use { members ->
                    while (members.moveToNext()) out.putIfAbsent(members.getLong(0).toString(), name)
                }
            }
        }
        return out
    }

    private companion object { const val TTL_MS = 60 * 60_000L }
}
