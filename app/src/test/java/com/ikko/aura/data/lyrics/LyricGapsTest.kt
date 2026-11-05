package com.ikko.aura.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricGapsTest {

    private fun sung(at: Long, until: Long, text: String = "la") = LyricLine(at, text, words = listOf(LyricWord(at, until, text)))

    @Test
    fun `an empty list stays empty`() {
        val empty = emptyList<LyricLine>()
        assertSame(empty, empty.withInstrumentalGaps())
    }

    @Test
    fun `a long intro gets its own break at zero, a short one does not`() {
        assertEquals(LyricLine(0L, ""), listOf(sung(MIN_GAP_MS, MIN_GAP_MS + 500)).withInstrumentalGaps().first())
        assertEquals(1, listOf(sung(MIN_GAP_MS - 1, MIN_GAP_MS + 500)).withInstrumentalGaps().size)
    }

    @Test
    fun `a break is drawn where the singing stopped, not where the next line starts`() {
        val out = listOf(sung(1_000, 3_000), sung(10_000, 11_000)).withInstrumentalGaps()
        assertEquals(listOf(1_000L, 3_000L, 10_000L), out.map { it.timeMs })
        assertTrue(out[1].isGap)
    }

    @Test
    fun `a silence shorter than the minimum is not a break`() {
        val out = listOf(sung(1_000, 3_000), sung(3_000 + MIN_GAP_MS - 1, 9_000)).withInstrumentalGaps()
        assertEquals(2, out.size)
    }

    @Test
    fun `a line-synced source with no known end never gets a break after each line`() {
        val lines = (0 until 10).map { LyricLine(1_000L + it * 10_000L, "line $it") }
        assertEquals(lines, lines.withInstrumentalGaps())
    }

    @Test
    fun `a provider's own line end counts as a known end`() {
        val out = listOf(LyricLine(1_000, "a", sungUntilMs = 2_000), LyricLine(9_000, "b")).withInstrumentalGaps()
        assertEquals(listOf(1_000L, 2_000L, 9_000L), out.map { it.timeMs })
    }

    @Test
    fun `an end that does not move past the start never puts a marker on top of its line`() {
        val out = listOf(LyricLine(1_000, "a", sungUntilMs = 1_000), LyricLine(9_000, "b")).withInstrumentalGaps()
        assertEquals(2, out.size)
    }

    @Test
    fun `the answering vocal holding past the lead delays the break`() {
        val lead = sung(1_000, 2_000).copy(background = sung(1_500, 6_000, "(ooh)"))
        val out = listOf(lead, sung(11_000, 12_000)).withInstrumentalGaps()
        assertEquals(6_000L, out[1].timeMs)
    }

    @Test
    fun `gaps never break the order of the lines`() {
        val lines = (0 until 50).map { sung(5_000L + it * 7_000L, 5_000L + it * 7_000L + 1_500L) }
        val out = lines.withInstrumentalGaps()
        assertEquals(out.map { it.timeMs }.sorted(), out.map { it.timeMs })
        assertEquals(lines, out.filterNot { it.isGap })
    }
}
