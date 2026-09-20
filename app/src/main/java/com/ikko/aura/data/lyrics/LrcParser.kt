/*
 * Ported from BitChord (github.com/kushagrasinghx/BitChord) — the `parseLrc` and
 * `parseWordRuns` halves of their LrcLib.kt, lifted out of the provider because
 * Aura also reads `.lrc` files sitting next to the audio and writes its own
 * cache in the same syntax.
 */
package com.ikko.aura.data.lyrics

/**
 * Plain `[mm:ss.xx] words` LRC, plus the "enhanced" A2 extension where each word
 * carries its own `<mm:ss.xx>` stamp.
 *
 * Metadata tags carry no timestamp and fall out on their own. A stamp with no words
 * marks an instrumental break; those are kept, but only where the silence is long
 * enough to be worth showing — otherwise the line would blink out between two sung
 * phrases. A stamp with nothing after it closes the final line, so it always survives.
 */
object LrcParser {

    fun parse(lrc: String): List<LyricLine> {
        val all = lrc.lineSequence().mapNotNull { line ->
            val match = STAMP.find(line) ?: return@mapNotNull null
            val (minutes, seconds, fraction) = match.destructured
            val body = line.substring(match.range.last + 1)
            LyricLine(
                timeMs = msOf(minutes, seconds, fraction),
                // Stripped rather than rebuilt from the runs below: the spacing and
                // punctuation between two words belong to the line, and re-joining the
                // words with single spaces would quietly rewrite a line that never had them.
                text = body.replace(WORD_STAMP, "").trim(),
                words = parseWordRuns(body),
            )
        }.sortedBy { it.timeMs }.toList()

        val kept = all.filterIndexed { index, line ->
            if (!line.isGap) return@filterIndexed true
            // A trailing stamp closes off the last line — that's the outro.
            val next = all.getOrNull(index + 1) ?: return@filterIndexed true
            next.timeMs - line.timeMs >= MIN_GAP_MS
        }

        // Nothing stands for the intro — LRC files start at the first sung word — so
        // give the run-up its own break when it's long enough.
        val first = kept.firstOrNull() ?: return kept
        return if (!first.isGap && first.timeMs >= MIN_GAP_MS) {
            listOf(LyricLine(0L, "")) + kept
        } else {
            kept
        }
    }

    /**
     * The `<mm:ss.xx>` runs of an "enhanced" A2 line, as words.
     *
     * Each run ends where the next one starts, which is why a line written by
     * [toEnhancedLrc] closes with a bare stamp: that last one names no word, it just
     * states where the previous one stopped. A run with no text is therefore a
     * terminator rather than a word, here and in the files other A2 writers produce.
     *
     * Empty for a plain line, which is what keeps [LyricLine.isWordSynced] honest — a
     * line-synced source stays line-synced through this.
     */
    private fun parseWordRuns(body: String): List<LyricWord> {
        val marks = WORD_STAMP.findAll(body).toList()
        if (marks.isEmpty()) return emptyList()
        val runs = marks.mapIndexed { index, mark ->
            val until = marks.getOrNull(index + 1)?.range?.first ?: body.length
            msOf(mark) to body.substring(mark.range.last + 1, until)
        }
        return runs.mapIndexedNotNull { index, (startMs, text) ->
            if (text.isBlank()) return@mapIndexedNotNull null
            // The next run's stamp is this word's end — including when that run is the
            // closing terminator, which is the only thing that gives the last word of a
            // line an end at all.
            val endMs = runs.getOrNull(index + 1)?.first ?: startMs
            LyricWord(startMs = startMs, endMs = maxOf(endMs, startMs), text = text.trim())
        }
    }

    private fun msOf(mark: MatchResult): Long {
        val (minutes, seconds, fraction) = mark.destructured
        return msOf(minutes, seconds, fraction)
    }

    private fun msOf(minutes: String, seconds: String, fraction: String): Long {
        // Two digits mean centiseconds, three mean milliseconds.
        val fractionMs = when (fraction.length) {
            2 -> fraction.toLong() * 10
            3 -> fraction.toLong()
            else -> 0L
        }
        return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
    }

    private val STAMP = Regex("""\[(\d{1,3}):(\d{2})[.:](\d{2,3})]""")
    private val WORD_STAMP = Regex("""<(\d{1,3}):(\d{2})[.:](\d{2,3})>""")
}
