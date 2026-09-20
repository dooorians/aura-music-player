package com.ikko.aura.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.ikko.aura.data.lyrics.LrcParser
import com.ikko.aura.data.lyrics.LyricLine
import com.ikko.aura.data.lyrics.LyricsCacheDao
import com.ikko.aura.data.lyrics.LyricsCacheEntity
import com.ikko.aura.data.lyrics.OnlineLyrics
import com.ikko.aura.data.lyrics.toEnhancedLrc
import com.ikko.aura.data.lyrics.withBackgroundVocals
import com.ikko.aura.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Where a set of lyrics came from, for the line the player shows under them. */
data class LyricsResult(val lines: List<LyricLine>, val sourceLabel: String)

/**
 * A track's synced lyrics, looked for in the order that costs least:
 *
 *  1. A `.lrc` sidecar next to the audio file. The user put it there on purpose; it
 *     outranks anything a database has, and it is free to read.
 *  2. This app's own cache — one row per track, written the first time a lookup
 *     succeeded, so a song is only ever fetched once. Misses are remembered too.
 *  3. The network, via [OnlineLyrics].
 *
 * Sidecars are parsed with the same [LrcParser] as the cache, so a hand-made enhanced
 * ("A2") file lights up word by word exactly as a fetched one does.
 */
@Singleton
class LyricsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cacheDao: LyricsCacheDao,
    private val settingsStore: SettingsStore,
) {

    suspend fun loadLyrics(track: Track): LyricsResult? {
        readSidecar(track)?.let { lines ->
            return LyricsResult(lines.withBackgroundVocals(), "Fichier .lrc")
        }

        val cached = runCatching { cacheDao.find(track.id) }.getOrNull()
        if (cached != null) {
            if (cached.lrc.isNotEmpty()) {
                val lines = LrcParser.parse(cached.lrc)
                if (lines.isNotEmpty()) {
                    return LyricsResult(lines.withBackgroundVocals(), cached.source)
                }
            } else if (System.currentTimeMillis() - cached.fetchedAtMs < MISS_RETRY_MS) {
                // A remembered miss, still fresh. Asking three hosts again on every play
                // of a track nobody has published lyrics for is the case this exists for.
                return null
            }
        }

        if (!settingsStore.onlineLyricsEnabled.first()) return null

        val found = OnlineLyrics.lyrics(
            title = track.title,
            artist = track.artist,
            album = track.album.takeIf { it.isNotBlank() },
            durationMs = track.durationMs,
        )

        runCatching {
            cacheDao.save(
                LyricsCacheEntity(
                    trackId = track.id,
                    source = found?.source?.label.orEmpty(),
                    lrc = found?.lines?.toEnhancedLrc().orEmpty(),
                    fetchedAtMs = System.currentTimeMillis(),
                ),
            )
        }

        return found?.let { LyricsResult(it.lines, it.source.label) }
    }

    /** Drops what is stored for a track so the next play looks it up again. */
    suspend fun forget(trackId: String) {
        runCatching { cacheDao.forget(trackId) }
    }

    suspend fun clearCache() {
        runCatching { cacheDao.clear() }
    }

    suspend fun storedCount(): Int = runCatching { cacheDao.storedCount() }.getOrDefault(0)

    /**
     * A `.lrc` sitting in the same directory under the same base filename. Read directly
     * where the path is reachable, and through MediaStore where scoped storage has taken
     * the direct path away.
     */
    private suspend fun readSidecar(track: Track): List<LyricLine>? = withContext(Dispatchers.IO) {
        runCatching {
            val dataPath = queryDataPath(track.contentUri) ?: return@runCatching null
            val file = File(dataPath)
            val dir = file.parentFile ?: return@runCatching null
            val baseName = file.nameWithoutExtension

            val text = readSidecarFile(dir, baseName)
                ?: readSidecarViaMediaStore(dir.absolutePath, baseName)
            text?.let(LrcParser::parse)?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun readSidecarFile(dir: File, baseName: String): String? = runCatching {
        val lrcFile = File(dir, "$baseName.lrc")
        if (lrcFile.exists() && lrcFile.canRead()) lrcFile.readText() else null
    }.getOrNull()

    private fun readSidecarViaMediaStore(dirPath: String, baseName: String): String? = runCatching {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.DATA} = ?"
        val targetPath = "$dirPath/$baseName.lrc"
        context.contentResolver.query(collection, projection, selection, arrayOf(targetPath), null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
            context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
        }
    }.getOrNull()

    private fun queryDataPath(contentUri: Uri): String? = runCatching {
        context.contentResolver.query(contentUri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val col = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (col >= 0) cursor.getString(col) else null
            }
    }.getOrNull()

    companion object {
        /**
         * How long a remembered miss stands. Lyrics do get published, so this is not
         * forever — but a fortnight is long enough that a library of tracks nobody has
         * transcribed stops costing three network calls apiece.
         */
        const val MISS_RETRY_MS = 14L * 24 * 60 * 60 * 1000
    }
}
