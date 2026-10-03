package com.ikko.aura.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsentCoversTest {

    @Test
    fun `a file added is found until the set is cleared`() {
        val set = BoundedKeySet(10)
        set.add("content://media/1")
        assertTrue(set.contains("content://media/1"))
        assertFalse(set.contains("content://media/2"))
        set.clear()
        assertFalse(set.contains("content://media/1"))
        assertEquals(0, set.size)
    }

    @Test
    fun `it holds at most max keys and drops the one least recently asked about`() {
        val set = BoundedKeySet(3)
        set.add("a"); set.add("b"); set.add("c")
        assertTrue(set.contains("a")) // a is now the most recently used
        set.add("d")
        assertEquals(3, set.size)
        assertTrue(set.contains("a"))
        assertFalse(set.contains("b"))
    }

    @Test
    fun `the global object is cleared by a scan`() {
        AbsentCovers.add("content://media/9")
        assertTrue("content://media/9" in AbsentCovers)
        AbsentCovers.clear()
        assertFalse("content://media/9" in AbsentCovers)
    }
}
