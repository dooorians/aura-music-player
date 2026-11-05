package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureSelectionTest {
    private fun songs(artist: String, n: Int) = (1..n).map { candidate("$artist $it", artist, id = "ytm:$artist-$it") }

    @Test fun `the songs measured come from many artists, not the hits of the few closest`() {
        val pool = (1..5).flatMap { songs("Close $it", 30) } + (1..40).flatMap { songs("Far $it", 2) }
        val tie = { c: Candidate -> if (c.artist.startsWith("Close")) 0.9 else 0.3 }
        val picked = FeatureSelection.spread(pool, limit = 100, tie = tie)
        // Five close artists at three songs each, forty far ones at their two: 95 songs, 45 artists.
        assertEquals(95, picked.size)
        // 45 artists have a first song picked, not 5.
        assertTrue(picked.map { it.artist }.toSet().size >= 40)
        assertTrue(picked.groupBy { it.artist }.values.all { it.size <= FeatureSelection.PER_ARTIST })
    }

    @Test fun `the artists closest to the listener come first`() {
        val pool = songs("Far", 3) + songs("Close", 3)
        val picked = FeatureSelection.spread(pool, limit = 2) { if (it.artist == "Close") 0.9 else 0.1 }
        assertEquals(listOf("Close 1", "Far 1").map { it.substringBefore(" ") }, picked.map { it.artist })
    }

    @Test fun `a small pool is taken whole, and nothing is asked for more than the limit`() {
        val pool = songs("A", 2) + songs("B", 1)
        assertEquals(3, FeatureSelection.spread(pool, limit = 50) { 0.5 }.size)
        assertEquals(1, FeatureSelection.spread(pool, limit = 1) { 0.5 }.size)
    }
}
