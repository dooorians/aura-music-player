package com.ikko.aura.player.smart

import com.ikko.aura.data.model.Track
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartShuffleTest {

    private fun track(i: Int) = Track(
        id = "t$i", title = "Song $i", artist = "Artist ${i % 7}", album = "Album ${i % 5}",
        artistId = 0L, albumId = 0L, artworkUri = null, contentUri = android.net.TestUri.INSTANCE, durationMs = 200_000L,
    )

    private fun analysis(bpm: Double, key: String = "", energy: Double = 0.5) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        bpm = bpm,
        key = key,
        keyConfidence = if (key.isEmpty()) 0.0 else 0.9,
        energyCurve = (0 until 20).map { EnergySample(it.toDouble(), energy) },
    )

    private fun assertPermutation(input: List<Track>, output: List<Track>) {
        assertEquals("size", input.size, output.size)
        assertEquals("same tracks, each once", input.map { it.id }.sorted(), output.map { it.id }.sorted())
    }

    @Test
    fun `tiny queues are just shuffled and nothing is lost`() {
        for (n in 0..3) {
            val tracks = (0 until n).map(::track)
            assertPermutation(tracks, SmartShuffle.order(tracks, { analysis(120.0) }, Random(n)))
        }
    }

    @Test
    fun `an unanalysed library is still a full permutation`() {
        val tracks = (0 until 300).map(::track)
        assertPermutation(tracks, SmartShuffle.order(tracks, { null }, Random(1)))
    }

    @Test
    fun `a partly analysed library keeps every track exactly once`() {
        val tracks = (0 until 500).map(::track)
        val known = tracks.filterIndexed { i, _ -> i % 3 == 0 }.associate { it.id to analysis(80.0 + it.id.drop(1).toInt() % 90) }
        repeat(5) { seed -> assertPermutation(tracks, SmartShuffle.order(tracks, { known[it.id] }, Random(seed))) }
    }

    @Test
    fun `the same seed gives the same order and another seed another one`() {
        val tracks = (0 until 60).map(::track)
        val a = { t: Track -> analysis(90.0 + t.id.drop(1).toInt()) }
        assertEquals(SmartShuffle.order(tracks, a, Random(7)), SmartShuffle.order(tracks, a, Random(7)))
        assertTrue(SmartShuffle.order(tracks, a, Random(7)) != SmartShuffle.order(tracks, a, Random(8)))
    }

    @Test
    fun `consecutive tracks follow each other better than a plain shuffle`() {
        // Two tempo families far apart: a smart order mostly stays inside one before crossing to the other.
        val tracks = (0 until 80).map(::track)
        val bpm = tracks.associate { it.id to if (it.id.drop(1).toInt() % 2 == 0) 100.0 else 165.0 }
        val a = { t: Track -> analysis(bpm.getValue(t.id)) }
        fun jumps(order: List<Track>) = order.zipWithNext().count { (x, y) -> bpm[x.id] != bpm[y.id] }
        val smart = (0 until 10).sumOf { jumps(SmartShuffle.order(tracks, a, Random(it))) }
        val plain = (0 until 10).sumOf { jumps(tracks.shuffled(Random(it))) }
        assertTrue("smart $smart vs plain $plain", smart < plain / 2)
    }

    /** Regression for the endless tempo fold: an analysis with an infinite tempo counts as "usable" (it is > 0). */
    @Test(timeout = 5_000)
    fun `an infinite tempo in a stored analysis does not hang the shuffle`() {
        val tracks = (0 until 20).map(::track)
        val order = SmartShuffle.order(tracks, { t -> if (t.id == "t3") analysis(Double.POSITIVE_INFINITY) else analysis(120.0) }, Random(3))
        assertPermutation(tracks, order)
    }

    @Test
    fun `compatibility is neutral without evidence and in 0 to 1 with it`() {
        assertEquals(0.5, SmartShuffle.compatibility(null, analysis(120.0)), 0.0)
        assertEquals(0.5, SmartShuffle.compatibility(TrackAnalysis(), analysis(120.0)), 0.0)
        val same = SmartShuffle.compatibility(analysis(120.0, "A minor"), analysis(120.0, "A minor"))
        val far = SmartShuffle.compatibility(analysis(120.0, "A minor"), analysis(97.0, "E♭ major", energy = 0.05))
        assertEquals(1.0, same, 1e-9)
        assertTrue(far in 0.0..1.0 && far < same)
        // Half time is the same pulse.
        assertEquals(1.0, SmartShuffle.compatibility(analysis(140.0, "A minor"), analysis(70.0, "A minor")), 1e-9)
        // No key on either side: the key part is neutral, not a match.
        assertEquals(0.5 * 1.0 + 0.3 * 0.5 + 0.2 * 1.0, SmartShuffle.compatibility(analysis(140.0), analysis(70.0)), 1e-9)
    }

    /** SmartShuffle is an object shared by every caller: concurrent shuffles (rapid taps on the button) must not interfere. */
    @Test(timeout = 30_000)
    fun `concurrent shuffles from many threads each return a full permutation`() {
        val tracks = (0 until 400).map(::track)
        val a = { t: Track -> analysis(70.0 + t.id.drop(1).toInt() % 110) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((0 until 64).map { seed -> Callable { SmartShuffle.order(tracks, a, Random(seed)) } })
            results.forEach { assertPermutation(tracks, it.get(10, TimeUnit.SECONDS)) }
            // And the result of a seed does not depend on what ran beside it.
            assertEquals(SmartShuffle.order(tracks, a, Random(5)), results[5].get())
        } finally {
            pool.shutdownNow()
        }
    }
}
