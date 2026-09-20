/*
 * Ported from BitChord (github.com/kushagrasinghx/BitChord), LyricsRepository.kt —
 * the race, the priority ordering and the word-synced-wins rule are theirs.
 */
package com.ikko.aura.data.lyrics

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Where a track's lyrics come from when they aren't already on the device.
 *
 * Every source is asked *at the same time*, but their answers are taken in priority
 * order: the loop awaits them one at a time in that sequence, so a lower-priority
 * source finishing first never preempts one still pending ahead of it. Asked one after
 * another instead, a miss on each would cost its own round trip before the next was
 * even tried, and a track with no lyrics anywhere would spend the best part of a minute
 * finding that out. Run together, a miss costs whatever the slowest one took.
 *
 * A word-timed answer wins outright. Failing that, a line-timed one is taken from the
 * highest-priority source that had it — better a whole line lighting up in sync than
 * the right animation on lyrics that don't exist.
 */
object OnlineLyrics {

    data class Result(val source: LyricsSource, val lines: List<LyricLine>)

    suspend fun lyrics(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): Result? = coroutineScope {
        if (title.isBlank() && artist.isBlank()) return@coroutineScope null

        val racing: List<Pair<LyricsSource, Deferred<List<LyricLine>?>>> =
            LyricsSource.entries.map { source ->
                source to async(Dispatchers.IO) {
                    runCatching { fetch(source, title, artist, album, durationMs) }.getOrNull()
                }
            }

        try {
            var lineSynced: Result? = null
            for ((source, job) in racing) {
                val lines = runCatching { job.await() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
                val finished = lines.withBackgroundVocals()
                if (finished.any { it.isWordSynced }) return@coroutineScope Result(source, finished)
                if (lineSynced == null) lineSynced = Result(source, finished)
            }
            lineSynced
        } finally {
            // Whoever lost the race is no longer worth waiting on, and coroutineScope
            // will not return while they are still running.
            racing.forEach { it.second.cancel() }
        }
    }

    private suspend fun fetch(
        source: LyricsSource,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): List<LyricLine>? = when (source) {
        LyricsSource.BETTER_LYRICS -> BetterLyricsSource.lyrics(title, artist, durationMs, album)
        LyricsSource.LYRICS_PLUS -> LyricsPlusSource.lyrics(title, artist, durationMs, album)
        LyricsSource.LRCLIB -> LrcLibSource.lyrics(title, artist, durationMs)
    }
}
