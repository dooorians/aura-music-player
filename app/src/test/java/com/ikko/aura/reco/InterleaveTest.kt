package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterleaveTest {

    @Test
    fun `every size keeps every item once and each list's own order`() {
        for (m in 0..25) for (x in 0..25) {
            val main = (0 until m).map { "m$it" }
            val extra = (0 until x).map { "x$it" }
            val out = Interleave.spread(main, extra)
            assertEquals("$m/$x", m + x, out.size)
            assertEquals("$m/$x main order", main, out.filter { it.startsWith("m") })
            assertEquals("$m/$x extra order", extra, out.filter { it.startsWith("x") })
        }
    }

    @Test
    fun `the extras are spread out rather than bunched at one end`() {
        val out = Interleave.spread((0 until 20).map { "m$it" }, (0 until 4).map { "x$it" })
        val positions = out.withIndex().filter { it.value.startsWith("x") }.map { it.index }
        val gaps = positions.zipWithNext { a, b -> b - a }
        assertTrue("$positions", gaps.all { it >= 4 })
        assertTrue("$positions", positions.first() > 0)
    }

    @Test
    fun `more extras than main items still fits everything`() {
        val out = Interleave.spread(listOf("m"), (0 until 9).map { "x$it" })
        assertEquals(10, out.size)
        assertEquals(1, out.count { it == "m" })
    }
}
