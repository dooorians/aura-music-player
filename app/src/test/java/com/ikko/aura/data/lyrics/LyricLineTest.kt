package com.ikko.aura.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineTest {

    private val line = LyricLine(
        timeMs = 1_000,
        text = "la la land",
        words = listOf(LyricWord(1_000, 1_200, "la"), LyricWord(1_500, 1_700, "la"), LyricWord(2_000, 3_000, "land")),
    )

    @Test
    fun `a line without words reveals all at once at its stamp`() {
        val plain = LyricLine(5_000, "plain")
        assertEquals(0f, plain.revealedChars(4_999), 0f)
        assertEquals(5f, plain.revealedChars(5_000), 0f)
        assertFalse(plain.hasKnownEnd)
        assertEquals(5_000L, plain.endMs)
    }

    @Test
    fun `a repeated word is lined up with its own place in the text`() {
        assertEquals(0f, line.revealedChars(900), 0f)
        assertEquals(1f, line.revealedChars(1_100), 1e-4f) // halfway through the first "la"
        assertEquals(4f, line.revealedChars(1_600), 1e-4f) // halfway through the second, not the first
        assertEquals(line.text.length.toFloat(), line.revealedChars(10_000), 0f)
    }

    @Test
    fun `the reveal never goes backwards as the song moves on`() {
        var last = -1f
        for (t in 0L..4_000L step 7) {
            val r = line.revealedChars(t)
            assertTrue("at $t: $r after $last", r >= last)
            assertTrue(r in 0f..line.text.length.toFloat())
            last = r
        }
    }

    @Test
    fun `a zero-length word does not divide by zero`() {
        val l = LyricLine(0, "a b", words = listOf(LyricWord(100, 100, "a"), LyricWord(100, 100, "b")))
        for (t in 0L..300L) {
            assertTrue(l.revealedChars(t).isFinite())
            assertTrue(l.glowIntensity(t).isFinite())
        }
    }

    @Test
    fun `a word missing from the text still reveals within the line`() {
        val l = LyricLine(0, "hello", words = listOf(LyricWord(0, 100, "bonjour")))
        for (t in 0L..200L) assertTrue(l.revealedChars(t).isFinite())
    }

    @Test
    fun `glow is dark between words, after the last one, and within 0 to 1 while singing`() {
        assertEquals(0f, line.glowIntensity(1_300), 0f) // between the first two words
        assertEquals(0f, line.glowIntensity(5_000), 0f) // after the line
        for (t in 1_000L until 3_000L) assertTrue(line.glowIntensity(t) in 0f..1f)
    }

    @Test
    fun `a held note glows more than patter`() {
        val held = LyricLine(0, "oh", words = listOf(LyricWord(0, 1_000, "oh"))).glowIntensity(500)
        val quick = LyricLine(0, "oh", words = listOf(LyricWord(0, 100, "oh"))).glowIntensity(50)
        assertTrue("$held > $quick", held > quick)
    }

    @Test
    fun `the end of a line counts its answering vocal`() {
        assertTrue(line.hasKnownEnd)
        assertEquals(3_000L, line.endMs)
        val answered = line.copy(background = LyricLine(2_500, "(oh)", sungUntilMs = 4_200))
        assertEquals(4_200L, answered.endMs)
        assertEquals(3_000L, line.copy(background = LyricLine(1_200, "(a)", sungUntilMs = 1_400)).endMs)
    }

    @Test
    fun `a blank line is an instrumental gap`() {
        assertTrue(LyricLine(0, "").isGap)
        assertFalse(LyricLine(0, " x ").isGap)
    }
}
