/*
 * Ported from BitChord (github.com/kushagrasinghx/BitChord), BetterLyrics.kt.
 */
package com.ikko.aura.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Word-timed lyrics from BetterLyrics — the backend behind the YouTube Music browser
 * extension of the same name.
 *
 * One key-less call keyed on title, artist and duration, answering with Apple Music's
 * own TTML. That combination is why it leads the chain: no track-id lookup, no token
 * to scrape, no login, and the timing is per-syllable.
 *
 * This is the extension's original host. The project's newer Cloudflare API puts the
 * same endpoint behind a Turnstile challenge, which a native client cannot answer.
 */
object BetterLyricsSource {

    private const val BASE = "https://lyrics-api.boidu.dev/getLyrics"

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val seconds = durationMs / 1000
        val url = buildString {
            append(BASE)
            append("?s=").append(title.cleanedForSearch().asQueryValue())
            append("&a=").append(artist.cleanedForSearch().asQueryValue())
            if (seconds > 0) append("&d=").append(seconds)
            if (!album.isNullOrBlank()) append("&al=").append(album.asQueryValue())
        }

        val body = lyricsGet(url) ?: return@withContext null
        val ttml = try {
            JSONObject(body).optString("ttml").takeIf { it.isNotBlank() }
        } catch (e: org.json.JSONException) {
            throw LyricsUnreachable("unreadable reply", e)
        } ?: return@withContext null

        TtmlLyrics.parse(ttml).takeIf { it.isNotEmpty() }
    }
}
