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

    /**
     * What a lookup concluded. [NotFound] is the only answer that may be remembered as a miss: every source answered and
     * none has the lyrics. [Unreachable] — no network, a timeout, a server error from a source — says nothing about the
     * lyrics, and is never cached.
     */
    sealed interface Lookup {
        data class Found(val result: Result) : Lookup
        data object NotFound : Lookup
        data object Unreachable : Lookup
    }

    private sealed interface Answer {
        class Lines(val lines: List<LyricLine>) : Answer
        data object Absent : Answer
        data object Failed : Answer
    }

    suspend fun lyrics(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): Result? = (lookup(title, artist, album, durationMs) as? Lookup.Found)?.result

    suspend fun lookup(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): Lookup = coroutineScope {
        if (title.isBlank() && artist.isBlank()) return@coroutineScope Lookup.NotFound

        val racing: List<Pair<LyricsSource, Deferred<Answer>>> =
            LyricsSource.entries.map { source ->
                source to async(Dispatchers.IO) { answerOf(source, title, artist, album, durationMs) }
            }

        try {
            var lineSynced: Result? = null
            var failed = false
            for ((source, job) in racing) {
                when (val answer = job.await()) {
                    is Answer.Lines -> {
                        val finished = answer.lines.withBackgroundVocals()
                        if (finished.any { it.isWordSynced }) return@coroutineScope Lookup.Found(Result(source, finished))
                        if (lineSynced == null) lineSynced = Result(source, finished)
                    }
                    Answer.Absent -> Unit
                    Answer.Failed -> failed = true
                }
            }
            verdict(lineSynced, failed)
        } finally {
            // Whoever lost the race is no longer worth waiting on, and coroutineScope
            // will not return while they are still running.
            racing.forEach { it.second.cancel() }
        }
    }

    /** Pure, so it can be tested: found beats everything; otherwise a failed source keeps the question open. */
    internal fun verdict(lineSynced: Result?, anySourceFailed: Boolean): Lookup = when {
        lineSynced != null -> Lookup.Found(lineSynced)
        anySourceFailed -> Lookup.Unreachable
        else -> Lookup.NotFound
    }

    private suspend fun answerOf(
        source: LyricsSource,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): Answer = try {
        val lines = fetch(source, title, artist, album, durationMs)
        if (lines.isNullOrEmpty()) Answer.Absent else Answer.Lines(lines)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        Answer.Failed
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
