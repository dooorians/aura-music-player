/*
 * Ported from BitChord (github.com/kushagrasinghx/BitChord), LrcWriter.kt. Their
 * writer targets a metadata atom in a downloaded file; Aura's target is its own
 * on-device cache, so only the enhanced ("A2") form is kept — the cache is read
 * back by this app alone, and dropping the word timings there would mean fetching
 * them again on every play.
 */
package com.ikko.aura.data.lyrics

/**
 * The lines as A2 LRC — `[mm:ss.xx]` per line, `<mm:ss.xx>` per word.
 *
 * Each line closes with a bare `<stamp>` at its end, which is what carries
 * [LyricLine.endMs] across — without it the last word of every line would come back
 * with no end, and the player reads a silence out of that.
 */
internal fun List<LyricLine>.toEnhancedLrc(): String {
    if (isEmpty()) return ""
    return sortedBy { it.timeMs }.joinToString("\n") { line -> stamp(line.timeMs) + line.enhancedBody() }
}

/**
 * One line as stamped word runs, or its plain text when it has none.
 *
 * The answering vocal rides along on the end — one line of the song is one line of
 * the file — but it keeps its own stamps, so the backing voice is still timed rather
 * than pinned to the lead's last word.
 */
private fun LyricLine.enhancedBody(): String {
    val runs = timedRuns()
    if (runs.isEmpty()) return background?.let { (text + " " + it.text).trim() } ?: text
    val out = StringBuilder()
    // Clamped to run forwards. A background vocal legitimately starts partway through
    // the lead it answers, so concatenating the two can hand us a stamp earlier than
    // the one before it — and a reader taking each run's end from the next one's start
    // would read that as a negative-length word.
    var previous = timeMs
    runs.forEachIndexed { index, word ->
        val start = maxOf(word.startMs, previous)
        out.append(wordStamp(start)).append(word.text)
        if (index != runs.lastIndex) out.append(' ')
        previous = start
    }
    out.append(wordStamp(maxOf(runs.maxOf { it.endMs }, previous)))
    return out.toString()
}

private fun LyricLine.timedRuns(): List<LyricWord> {
    if (words.isEmpty()) return emptyList()
    val answer = background?.let { bg ->
        bg.words.ifEmpty {
            if (bg.text.isBlank()) emptyList() else listOf(LyricWord(bg.timeMs, bg.endMs, bg.text))
        }
    }.orEmpty()
    return words + answer
}

private fun stamp(timeMs: Long): String = "[" + clock(timeMs) + "]"

private fun wordStamp(timeMs: Long): String = "<" + clock(timeMs) + ">"

/**
 * Assembled with [padStart] rather than `String.format`, which is not a style
 * preference: `%02d` formats through the default locale, and under a locale with its
 * own numerals — Arabic, Bengali, several Indic ones — that emits digits no LRC parser
 * on earth matches, including this package's own.
 */
private fun clock(timeMs: Long): String {
    val total = timeMs.coerceAtLeast(0L)
    val minutes = (total / 60_000).toString().padStart(2, '0')
    val seconds = (total % 60_000 / 1_000).toString().padStart(2, '0')
    val centiseconds = (total % 1_000 / 10).toString().padStart(2, '0')
    return "$minutes:$seconds.$centiseconds"
}
