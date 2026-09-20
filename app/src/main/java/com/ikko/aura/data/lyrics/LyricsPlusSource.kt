/*
 * Ported from BitChord (github.com/kushagrasinghx/BitChord), LyricsPlus.kt.
 */
package com.ikko.aura.data.lyrics

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Word-timed lyrics from the YouLy+ backend.
 *
 * Six community mirrors of the same service, because any one of them is regularly
 * down. They are asked at once and the first to answer with something *usable* wins —
 * the first to answer at all is often a mirror that simply doesn't have the track, and
 * letting that one win would throw away a mirror that does.
 *
 * The winner is remembered and asked first next time, so the common case is one
 * request rather than six.
 */
object LyricsPlusSource {

    private val MIRRORS = listOf(
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus-seven.vercel.app",
        "https://lyrics-plus-backend.vercel.app",
    )

    private val lastGood = AtomicReference<String?>(null)

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<LyricLine>? = coroutineScope {
        val hosts = lastGood.get()
            ?.let { good -> listOf(good) + MIRRORS.filterNot { it == good } }
            ?: MIRRORS

        val pending = hosts.map { host ->
            host to async(Dispatchers.IO) { fetch(host, title, artist, durationMs, album) }
        }.toMutableList()

        try {
            while (pending.isNotEmpty()) {
                val (host, lines) = select {
                    pending.forEach { (host, job) -> job.onAwait { host to it } }
                }
                pending.removeAll { it.first == host }
                if (!lines.isNullOrEmpty()) {
                    lastGood.set(host)
                    return@coroutineScope lines
                }
            }
            null
        } finally {
            pending.forEach { it.second.cancel() }
        }
    }

    private suspend fun fetch(
        host: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val seconds = durationMs / 1000
        val url = buildString {
            append(host).append("/v2/lyrics/get")
            append("?title=").append(title.cleanedForSearch().asQueryValue())
            append("&artist=").append(artist.cleanedForSearch().asQueryValue())
            if (seconds > 0) append("&duration=").append(seconds)
            if (!album.isNullOrBlank()) append("&album=").append(album.asQueryValue())
        }

        val body = lyricsGet(url) ?: return@withContext null
        runCatching { parse(JSONObject(body)) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    internal fun parse(response: JSONObject): List<LyricLine> {
        val rows = response.optJSONArray("lyrics") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            val line = rows.optJSONObject(index) ?: return@mapNotNull null
            if (!line.has("time")) return@mapNotNull null
            val start = line.optLong("time")
            val words = mergeSyllables(line)
            val text = line.optString("text").trim()
            when {
                words.isNotEmpty() -> LyricLine(
                    timeMs = minOf(start, words.first().startMs),
                    text = words.joinToString(" ") { it.text },
                    words = words,
                )
                // Some entries are only line-synced; still worth showing. The line's
                // duration is the only end it gets, and without it an interlude can't be
                // told from a slowly sung line.
                text.isNotBlank() -> LyricLine(
                    timeMs = start,
                    text = text,
                    sungUntilMs = line.optLong("duration").takeIf { it > 0 }?.let { start + it },
                )
                else -> null
            }
        }.sortedBy { it.timeMs }.withInstrumentalGaps()
    }

    /**
     * Glues syllables back into words.
     *
     * The API's own spacing is the word boundary — it emits `"e"` then `"nough "`, and
     * the trailing space is the only thing saying those are one word. Splitting on the
     * syllable instead would render "e nough".
     */
    private fun mergeSyllables(line: JSONObject): List<LyricWord> {
        val syllables = line.optJSONArray("syllabus") ?: return emptyList()
        val words = mutableListOf<LyricWord>()
        val current = StringBuilder()
        var start = 0L
        var end = 0L

        for (index in 0 until syllables.length()) {
            val syllable = syllables.optJSONObject(index) ?: continue
            val text = syllable.optString("text")
            if (text.isBlank()) continue
            if (!syllable.has("time")) continue
            val time = syllable.optLong("time")
            if (current.isEmpty()) start = time
            current.append(text.trim())
            end = time + syllable.optLong("duration")
            if (text.last().isWhitespace()) {
                words += LyricWord(start, end, current.toString())
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) words += LyricWord(start, end, current.toString())
        return words
    }
}
