package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Test

class TagRescanTest {

    private val current = TagScanVersion.CURRENT

    @Test
    fun `a file already read is not read again`() {
        val toRead = tagsToRead(listOf("1", "2"), listOf(TagMark("1", current), TagMark("2", current)), emptySet())
        assertEquals(emptySet<String>(), toRead)
    }

    @Test
    fun `a new file, and one read by an older version, are read`() {
        val toRead = tagsToRead(listOf("1", "2", "3"), listOf(TagMark("1", current), TagMark("2", current - 1)), emptySet())
        assertEquals(listOf("2", "3"), toRead.toList())
    }

    @Test
    fun `a file changed on disk since it was read is read again`() {
        val toRead = tagsToRead(listOf("1", "2"), listOf(TagMark("1", current), TagMark("2", current)), setOf("2"))
        assertEquals(setOf("2"), toRead)
    }

    @Test
    fun `a changed id that is no longer in the library is not read`() {
        val toRead = tagsToRead(listOf("1"), listOf(TagMark("1", current)), setOf("9"))
        assertEquals(emptySet<String>(), toRead)
    }

    @Test
    fun `the order of the library is kept`() {
        val toRead = tagsToRead(listOf("c", "a", "b"), emptyList(), emptySet())
        assertEquals(listOf("c", "a", "b"), toRead.toList())
    }
}
