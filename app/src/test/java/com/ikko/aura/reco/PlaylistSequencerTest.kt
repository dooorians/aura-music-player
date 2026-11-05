package com.ikko.aura.reco

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSequencerTest {

    private fun item(key: String, artist: String = key, bpm: Double? = null, energy: Double? = null, relevance: Double = 0.5, familiar: Boolean = true, album: String = "") =
        SeqItem(key, key, artist, album, bpm, energy, relevance, familiar)

    private fun <T> asSeq(order: List<String>, items: List<SeqItem<T>>): List<SeqItem<T>> {
        val byKey = items.associateBy { it.key }
        return order.map { byKey.getValue(it) }
    }

    /** Spearman's rank correlation between position and energy. */
    private fun rankCorrelation(energies: List<Double>): Double {
        val n = energies.size
        val ranks = PlaylistSequencer.rankNormalize(energies).map { it!! * (n - 1) }
        val pos = (0 until n).map { it.toDouble() }
        val mr = ranks.average(); val mp = pos.average()
        val cov = ranks.indices.sumOf { (ranks[it] - mr) * (pos[it] - mp) }
        val vr = ranks.sumOf { (it - mr) * (it - mr) }; val vp = pos.sumOf { (it - mp) * (it - mp) }
        return cov / kotlin.math.sqrt(vr * vp)
    }

    @Test fun `empty, single and duplicated lists`() {
        assertTrue(PlaylistSequencer.order(emptyList<SeqItem<String>>()).isEmpty())
        assertEquals(listOf("a"), PlaylistSequencer.order(listOf(item("a"))))
        val dup = PlaylistSequencer.order(listOf(item("a"), item("a"), item("b")))
        assertEquals(setOf("a", "b"), dup.toSet())
        assertEquals(2, dup.size)
    }

    @Test fun `the same songs come out, each once`() {
        val rng = Random(3)
        val items = (1..40).map { item("t$it", "a${it % 7}", bpm = 70.0 + rng.nextDouble() * 110, energy = rng.nextDouble(), relevance = rng.nextDouble()) }
        val out = PlaylistSequencer.order(items, ArcShape.WAVE)
        assertEquals(items.map { it.item }.sorted(), out.sorted())
    }

    @Test fun `the same artist does not come back within three songs when the list allows it`() {
        // Four artists, three songs each: a round robin exists, gap four.
        val items = (0 until 12).map { item("t$it", "artist${it / 3}", relevance = 1.0 - it * 0.01) }
        val out = asSeq(PlaylistSequencer.order(items), items)
        assertTrue("gap ${PlaylistSequencer.minArtistGap(out)}", PlaylistSequencer.minArtistGap(out) >= 3)
        // An artist that is most of the list is spread as well as it can be, and nothing is lost.
        val heavy = (0 until 6).map { item("h$it", "solo") } + (0 until 3).map { item("o$it", "other$it") }
        val h = asSeq(PlaylistSequencer.order(heavy), heavy)
        assertEquals(9, h.size)
        val adjacent = h.zipWithNext().count { (a, b) -> a.artist == b.artist }
        assertTrue("adjacent same-artist pairs: $adjacent", adjacent <= 2)
    }

    @Test fun `one album is not played in a row`() {
        val items = (0 until 6).map { item("t$it", "artist$it", album = if (it < 3) "A" else "B") }
        val out = asSeq(PlaylistSequencer.order(items), items)
        assertTrue(out.zipWithNext().none { (a, b) -> a.album == b.album })
    }

    @Test fun `tempos follow each other far better than in a random order`() {
        var gain = 0.0
        repeat(10) { seed ->
            val rng = Random(seed)
            val items = (1..30).map { item("t$it", "a$it", bpm = 70.0 + rng.nextDouble() * 110.0) }
            val shuffled = items.shuffled(rng)
            val out = asSeq(PlaylistSequencer.order(shuffled), items)
            val sequenced = PlaylistSequencer.meanTempoAffinity(out)!!
            val random = PlaylistSequencer.meanTempoAffinity(shuffled)!!
            assertTrue("seed $seed: $sequenced", sequenced > 0.6)
            gain += sequenced - random
        }
        assertTrue("mean gain ${gain / 10}", gain / 10 > 0.25)
    }

    @Test fun `double time is a smooth step, an unrelated tempo is not`() {
        val opener = item("open", bpm = 128.0, relevance = 1.0)
        val double = item("double", bpm = 64.0, relevance = 0.5)
        val far = item("far", bpm = 97.0, relevance = 0.5)
        val out = PlaylistSequencer.order(listOf(far, double, opener))
        assertEquals(listOf("open", "double", "far"), out)
    }

    @Test fun `a song whose tempo is unknown is not held back`() {
        // The strongest song of the list has no tempo: it still opens.
        val items = listOf(item("unknown", bpm = null, relevance = 1.0)) + (1..10).map { item("k$it", bpm = 120.0 + it, relevance = 0.4) }
        assertEquals("unknown", PlaylistSequencer.order(items).first())
        // Songs of unknown tempo among songs of scattered tempos are spread through the list, not left for the end.
        var meanPos = 0.0
        repeat(20) { seed ->
            val rng = Random(100 + seed)
            val list = (1..24).map { i -> item("s$i", bpm = if (i % 4 == 0) null else 60.0 + rng.nextDouble() * 120.0, relevance = rng.nextDouble()) }
            val out = PlaylistSequencer.order(list)
            val positions = out.withIndex().filter { (_, k) -> k.removePrefix("s").toInt() % 4 == 0 }.map { it.index / (out.size - 1.0) }
            meanPos += positions.average()
        }
        meanPos /= 20
        assertTrue("mean relative position of unknown tempos: $meanPos", meanPos in 0.25..0.75)
    }

    @Test fun `energy follows the arc of the playlist`() {
        val rng = Random(5)
        val items = (1..24).map { item("t$it", energy = rng.nextDouble(), relevance = 0.5) }
        fun energies(shape: ArcShape) = asSeq(PlaylistSequencer.order(items, shape), items).map { it.energy!! }
        assertTrue(rankCorrelation(energies(ArcShape.BUILD)) > 0.6)
        assertTrue(rankCorrelation(energies(ArcShape.WIND_DOWN)) < -0.6)
        // A wave peaks inside the list, not at either end.
        val wave = energies(ArcShape.WAVE)
        val peak = wave.indexOf(wave.max())
        assertTrue("peak at $peak", peak in 4..20)
    }

    @Test fun `discoveries are anchored by familiar songs`() {
        val items = (1..6).map { item("new$it", familiar = false) } + (1..3).map { item("known$it", familiar = true) }
        val out = asSeq(PlaylistSequencer.order(items), items)
        var run = 0; var worst = 0
        for (s in out) { run = if (s.familiar) 0 else run + 1; worst = maxOf(worst, run) }
        assertTrue("longest run of unfamiliar songs: $worst", worst <= 2)
    }

    @Test fun `the order is the same for the same input, and nothing breaks on corrupt values`() {
        val rng = Random(9)
        val items = (1..30).map {
            item("t$it", "a${it % 5}", bpm = listOf(null, 0.0, Double.NaN, -1.0, 1e6, 90.0 + it).random(rng),
                energy = listOf(null, Double.NaN, Double.POSITIVE_INFINITY, rng.nextDouble()).random(rng),
                relevance = listOf(Double.NaN, Double.NEGATIVE_INFINITY, rng.nextDouble()).random(rng))
        }
        val a = PlaylistSequencer.order(items, ArcShape.BUILD)
        val b = PlaylistSequencer.order(items, ArcShape.BUILD)
        assertEquals(a, b)
        assertEquals(30, a.toSet().size)
    }

    @Test fun `normalisation guards against equal and missing values`() {
        assertEquals(listOf(0.5, 0.5), PlaylistSequencer.minMaxNormalize(listOf(3.0, 3.0)))
        assertEquals(listOf(0.5, 0.5), PlaylistSequencer.minMaxNormalize(listOf(Double.NaN, Double.NaN)))
        assertEquals(listOf(null, 0.5, null), PlaylistSequencer.rankNormalize(listOf(null, 7.0, Double.NaN)))
        // Ties share their mean rank: 1, 2, 5, 5 rank 0, 1, 2.5, 2.5 out of 3.
        val r = PlaylistSequencer.rankNormalize(listOf(1.0, 5.0, 5.0, 2.0)).map { it!! }
        assertEquals(0.0, r[0], 1e-9)
        assertEquals(2.5 / 3, r[1], 1e-9)
        assertEquals(2.5 / 3, r[2], 1e-9)
        assertEquals(1.0 / 3, r[3], 1e-9)
    }

    @Test fun `a large list is ordered in linear time`() {
        val rng = Random(1)
        val items = (1..5_000).map { item("t$it", "a${rng.nextInt(800)}", bpm = 60.0 + rng.nextDouble() * 120, energy = rng.nextDouble(), relevance = rng.nextDouble()) }
        val t0 = System.nanoTime()
        val out = PlaylistSequencer.order(items, ArcShape.WAVE)
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(5_000, out.size)
        assertTrue("5000 songs took $ms ms", ms < 5_000)
    }

    @Test fun `each playlist has its arc`() {
        assertEquals(ArcShape.BUILD, ArcShape.of(RecoKind.ENERGY))
        assertEquals(ArcShape.WIND_DOWN, ArcShape.of(RecoKind.NIGHT))
        assertEquals(ArcShape.PLATEAU, ArcShape.of(RecoKind.CHILL))
        for (shape in ArcShape.entries) for (t in listOf(-1.0, 0.0, 0.3, 1.0, 2.0, Double.NaN)) assertTrue(shape.target(t) in 0.0..1.0)
    }
}
