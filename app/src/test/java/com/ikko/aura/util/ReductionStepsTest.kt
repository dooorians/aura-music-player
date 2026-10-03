package com.ikko.aura.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ReductionStepsTest {

    @Test
    fun `a picture that fits or is smaller than the target is never touched nor enlarged`() {
        assertTrue(reductionSteps(130, 130, 130).isEmpty())
        assertTrue(reductionSteps(100, 100, 130).isEmpty())
        assertTrue(reductionSteps(300, 200, 450).isEmpty())
        // Its shorter side already is under the target: the crop will enlarge it, reducing would only soften it more.
        assertTrue(reductionSteps(500, 425, 450).isEmpty())
        assertTrue(reductionSteps(425, 500, 450).isEmpty())
    }

    @Test
    fun `the shorter side ends on the target so the crop fills the box one to one`() {
        // What loadThumbnail returned for 130 asked, 166 x 141: reduced to 153 x 130, not to 130 x 110.
        assertEquals(listOf(153 to 130), reductionSteps(166, 141, 130))
        // And a portrait one.
        assertEquals(listOf(130 to 153), reductionSteps(141, 166, 130))
    }

    @Test
    fun `a square keeps its ratio`() {
        assertEquals(listOf(375 to 375), reductionSteps(700, 700, 375))
        assertEquals(listOf(450 to 450), reductionSteps(600, 600, 450))
    }

    @Test
    fun `a big reduction goes down in halvings at most and ends on the target`() {
        val steps = reductionSteps(2000, 1500, 130)
        var shorter = 1500
        for ((w, h) in steps) {
            val s = minOf(w, h)
            assertTrue("each step at most halves: $shorter -> $s", s >= (shorter + 1) / 2)
            assertTrue(s < shorter)
            shorter = s
        }
        assertEquals(130, shorter)
        val (w, h) = steps.last()
        assertTrue(abs(w.toFloat() / h - 2000f / 1500f) < 0.02f)
    }

    @Test
    fun `no step is ever larger than the previous nor larger than the source`() {
        for (src in listOf(200 to 200, 513 to 512, 1200 to 800, 64 to 4000, 4000 to 64)) {
            for (target in listOf(1, 85, 110, 130, 279, 450)) {
                var w = src.first; var h = src.second
                for ((nw, nh) in reductionSteps(src.first, src.second, target)) {
                    assertTrue(nw <= w && nh <= h)
                    assertTrue(nw >= 1 && nh >= 1)
                    w = nw; h = nh
                }
                assertTrue("$src -> $target ends at $w x $h", minOf(w, h) <= target || minOf(src.first, src.second) <= target)
            }
        }
    }

    @Test
    fun `a nonsense target is treated as one pixel, never an error`() {
        assertEquals(1, minOf(reductionSteps(100, 100, 0).last().first, reductionSteps(100, 100, -5).last().second))
    }
}
