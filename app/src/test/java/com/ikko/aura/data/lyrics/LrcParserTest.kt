package com.ikko.aura.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The project's first test, picked for being pure Kotlin with a real specification: it shows the JVM harness works
// on code that already exists before any of the new code leans on it.
class LrcParserTest {

    // The parser opens a long run-up with a break line of its own (tested below); the other tests are about the lines.
    private fun sung(lrc: String) = LrcParser.parse(lrc).filterNot { it.isGap }

    @Test
    fun `a stamped line becomes a line at that time`() {
        val lines = sung("[01:02.30]Hello there")
        assertEquals(1, lines.size)
        assertEquals(62_300L, lines.single().timeMs)
        assertEquals("Hello there", lines.single().text)
        assertFalse(lines.single().isWordSynced)
    }

    @Test
    fun `two digits are centiseconds and three are milliseconds`() {
        assertEquals(5_120L, sung("[00:05.12]a").single().timeMs)
        assertEquals(5_123L, sung("[00:05.123]a").single().timeMs)
    }

    @Test
    fun `lines come out in time order whatever order they were written in`() {
        val lines = sung("[00:20.00]second\n[00:10.00]first")
        assertEquals(listOf("first", "second"), lines.map { it.text })
    }

    @Test
    fun `text without a stamp is ignored`() {
        assertTrue(LrcParser.parse("[ar:Somebody]\njust a sentence").isEmpty())
        assertTrue(LrcParser.parse("").isEmpty())
    }

    @Test
    fun `a long run-up before the first line gets a break of its own`() {
        val lines = LrcParser.parse("[00:30.00]first line")
        assertEquals(2, lines.size)
        assertTrue(lines.first().isGap)
        assertEquals(0L, lines.first().timeMs)
    }

    @Test
    fun `word stamps give a word-synced line whose words end where the next begins`() {
        val line = sung("[00:10.00]<00:10.00>one <00:10.50>two <00:11.00>").single()
        assertTrue(line.isWordSynced)
        assertEquals(listOf("one", "two"), line.words.map { it.text })
        assertEquals(10_500L, line.words.first().endMs)
        assertEquals(11_000L, line.words.last().endMs)
        assertEquals("one two", line.text)
    }
}
