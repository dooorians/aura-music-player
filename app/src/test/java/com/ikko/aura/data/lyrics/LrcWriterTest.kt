package com.ikko.aura.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcWriterTest {

    private fun line(at: Long, vararg words: Pair<String, LongRange>) = LyricLine(
        timeMs = at,
        text = words.joinToString(" ") { it.first },
        words = words.map { (w, r) -> LyricWord(r.first, r.last, w) },
    )

    @Test
    fun `nothing gives an empty file`() {
        assertEquals("", emptyList<LyricLine>().toEnhancedLrc())
    }

    @Test
    fun `stamps are zero padded minutes, seconds and centiseconds`() {
        val lrc = listOf(LyricLine(61_230L, "Hello"), LyricLine(5L, "Start"), LyricLine(3_599_990L, "Late")).toEnhancedLrc()
        assertEquals("[00:00.00]Start\n[01:01.23]Hello\n[59:59.99]Late", lrc)
    }

    @Test
    fun `a negative time is written as zero, never as a minus sign a parser cannot read`() {
        assertEquals("[00:00.00]Early", listOf(LyricLine(-500L, "Early")).toEnhancedLrc())
    }

    @Test
    fun `word timings and the end of the line survive a round trip through the parser`() {
        val original = listOf(
            line(10_000, "Never" to 10_000L..10_400L, "gonna" to 10_400L..10_900L, "give" to 11_000L..11_500L),
            line(13_000, "you" to 13_000L..13_300L, "up" to 13_300L..14_200L),
        )
        // The parser adds its own break before a late first line; only the sung lines are compared.
        val parsed = LrcParser.parse(original.toEnhancedLrc()).filterNot { it.isGap }
        assertEquals(2, parsed.size)
        assertEquals(original.map { it.timeMs }, parsed.map { it.timeMs })
        assertEquals(original.map { it.text }, parsed.map { it.text })
        assertEquals(original.map { l -> l.words.map { it.text } }, parsed.map { l -> l.words.map { it.text } })
        // The last word of each line keeps its end thanks to the closing stamp.
        assertEquals(14_200L, parsed[1].words.last().endMs)
        // A2 has no word ends: a word runs until the next one starts, so the short pause is credited to it.
        assertEquals(11_000L, parsed[0].words[1].endMs)
        assertEquals(10_400L, parsed[0].words[1].startMs)
    }

    @Test
    fun `word stamps never run backwards even when the answering vocal starts earlier`() {
        val lead = line(20_000, "Hold" to 20_000L..20_800L, "on" to 20_800L..21_500L)
        val withBackground = lead.copy(background = LyricLine(20_300L, "(hold)", words = listOf(LyricWord(20_300L, 20_900L, "(hold)"))))
        val lrc = listOf(withBackground).toEnhancedLrc()
        val stamps = Regex("<(\\d{2}):(\\d{2})\\.(\\d{2})>").findAll(lrc).map { m ->
            val (mm, ss, cc) = m.destructured
            mm.toLong() * 60_000 + ss.toLong() * 1_000 + cc.toLong() * 10
        }.toList()
        assertTrue(stamps.isNotEmpty())
        assertEquals(stamps.sorted(), stamps)
    }

    @Test
    fun `a line without word timings keeps its text and its background vocal`() {
        val lrc = listOf(LyricLine(1_000, "Plain", background = LyricLine(1_500, "(echo)"))).toEnhancedLrc()
        assertEquals("[00:01.00]Plain (echo)", lrc)
    }

    @Test
    fun `stamps are ascii digits whatever the default locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            val lrc = listOf(LyricLine(75_120, "x")).toEnhancedLrc()
            assertEquals("[01:15.12]x", lrc)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
