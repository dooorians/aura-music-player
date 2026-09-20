/*
 * Ported from BitChord (github.com/kushagrasinghx/BitChord), LrcLib.kt.
 */
package com.ikko.aura.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Lyrics from LRCLIB — a free, key-less, community lyrics database.
 *
 * Two calls: an exact `get` keyed on artist + title + duration, and a fuzzy `search`
 * when that misses. Results are line-synced `[mm:ss.xx]` LRC — LRCLIB has no
 * word-level timing, so highlighting from this source is per line.
 */
object LrcLibSource {

    private const val BASE = "https://lrclib.net/api"

    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val cleanTitle = title.cleanedForSearch()
            val cleanArtist = artist.cleanedForSearch()
            val seconds = (durationMs / 1000).toInt()

            val synced = exactMatch(cleanTitle, cleanArtist, seconds)
                ?: bestSearchHit(cleanTitle, cleanArtist, seconds)
            synced?.let(LrcParser::parse)?.takeIf { it.isNotEmpty() }
        }

    private fun exactMatch(title: String, artist: String, seconds: Int): String? {
        val url = "$BASE/get?track_name=${title.asQueryValue()}" +
            "&artist_name=${artist.asQueryValue()}&duration=$seconds"
        val body = lyricsGet(url) ?: return null
        return runCatching {
            JSONObject(body).optString("syncedLyrics").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Fuzzy fallback. Prefers whichever hit is closest in length to what we're actually
     * playing — same song, different edit, would drift otherwise.
     */
    private fun bestSearchHit(title: String, artist: String, seconds: Int): String? {
        val url = "$BASE/search?track_name=${title.asQueryValue()}&artist_name=${artist.asQueryValue()}"
        val body = lyricsGet(url) ?: return null
        return runCatching {
            val hits = JSONArray(body)
            (0 until hits.length())
                .mapNotNull { hits.optJSONObject(it) }
                .filter { it.optString("syncedLyrics").isNotBlank() }
                .minByOrNull { abs(it.optDouble("duration", 0.0) - seconds) }
                ?.optString("syncedLyrics")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
