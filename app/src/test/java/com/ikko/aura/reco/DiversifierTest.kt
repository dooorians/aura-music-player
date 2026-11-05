package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiversifierTest {
    private data class Song(val id: String, val artist: String, val score: Double)

    private val sameArtist: (Song, Song) -> Double = { a, b -> if (a.artist == b.artist) 1.0 else 0.0 }

    @Test fun `variety lets other artists in ahead of the best artist's fifth song`() {
        val star = (1..10).map { Song("s$it", "star", 1.0 - it * 0.01) }
        val others = (1..5).map { Song("o$it", "other$it", 0.6) }
        val plain = Diversifier.select(star + others, 5, 1.0, { it.score }, sameArtist)
        assertTrue(plain.all { it.artist == "star" })
        val varied = Diversifier.select(star + others, 5, 0.6, { it.score }, sameArtist)
        assertEquals("star", varied.first().artist)
        assertTrue("artists: ${varied.map { it.artist }}", varied.map { it.artist }.distinct().size >= 4)
    }

    @Test fun `a refused pick is skipped, not retried`() {
        val songs = (1..6).map { Song("s$it", "a${it % 2}", 1.0 - it * 0.1) }
        var asked = 0
        val perArtist = HashMap<String, Int>()
        val out = Diversifier.select(songs, 6, 0.8, { it.score }, sameArtist, admit = { s ->
            asked++
            if ((perArtist[s.artist] ?: 0) >= 1) false else { perArtist.merge(s.artist, 1, Int::plus); true }
        })
        assertEquals(2, out.size)
        assertEquals(6, asked)
    }

    @Test fun `limits and bad scores`() {
        assertTrue(Diversifier.select(emptyList<Song>(), 5, 0.7, { it.score }, sameArtist).isEmpty())
        val songs = listOf(Song("a", "x", Double.NaN), Song("b", "y", 1.0), Song("c", "z", Double.POSITIVE_INFINITY))
        assertTrue(Diversifier.select(songs, 0, 0.7, { it.score }, sameArtist).isEmpty())
        assertEquals(3, Diversifier.select(songs, 10, 0.7, { it.score }, sameArtist).size)
        assertEquals(3, Diversifier.select(songs, 10, Double.NaN, { it.score }, { _, _ -> Double.NaN }).size)
    }

    @Test fun `k picks cost k passes, not a pass per pair of picks`() {
        val songs = (1..4_000).map { Song("s$it", "a${it % 300}", (it * 7919 % 1000) / 1000.0) }
        var calls = 0L
        val t0 = System.nanoTime()
        val out = Diversifier.select(songs, 40, 0.75, { it.score }, { a, b -> calls++; sameArtist(a, b) })
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(40, out.size)
        assertTrue("calls $calls", calls <= 40L * songs.size)
        assertTrue("took $ms ms", ms < 3_000)
        assertEquals(40, out.map { it.artist }.distinct().size)
    }
}
