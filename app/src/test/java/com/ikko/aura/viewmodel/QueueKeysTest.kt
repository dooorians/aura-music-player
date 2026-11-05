package com.ikko.aura.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QueueKeysTest {
    private val book = QueueKeys()

    @Test
    fun `every entry gets its own key even when the same song is queued twice`() {
        val keys = book.fresh(3) // e.g. [A, B, A]
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun `an entry keeps its key through a move`() {
        val keys = book.fresh(4)
        val moved = book.moved(keys, from = 0, to = 2)
        assertEquals(listOf(keys[1], keys[2], keys[0], keys[3]), moved)
    }

    @Test
    fun `a move past the end lands on the last slot like the queue does`() {
        val keys = book.fresh(3)
        assertEquals(listOf(keys[1], keys[2], keys[0]), book.moved(keys, from = 0, to = 99))
    }

    @Test
    fun `removing an occurrence leaves the other keys alone`() {
        val keys = book.fresh(3)
        assertEquals(listOf(keys[0], keys[2]), book.removed(keys, 1))
        assertEquals(keys, book.removed(keys, 7)) // out of range: untouched
    }

    @Test
    fun `inserted and appended keys are new and never reuse a removed one`() {
        val keys = book.fresh(2)
        val afterRemoval = book.removed(keys, 0)
        val inserted = book.inserted(afterRemoval, at = 0, count = 2)
        val appended = book.appended(inserted, 1)
        assertEquals(4, appended.size)
        assertEquals(4, appended.toSet().size)
        assertNotEquals(keys[0], appended[0])
        assertEquals(keys[1], appended[2])
    }

    @Test
    fun `an entry the keys do not cover still gets a unique key`() {
        val keys = listOf(5L)
        assertNotEquals(keys.keyAt(1), keys.keyAt(2))
        assertEquals(5L, keys.keyAt(0))
    }
}
