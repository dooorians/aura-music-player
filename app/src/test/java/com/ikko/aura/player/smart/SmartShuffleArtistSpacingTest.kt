package com.ikko.aura.player.smart

import com.ikko.aura.data.model.Track
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartShuffleArtistSpacingTest {
    private val uri = android.net.TestUri.INSTANCE

    private fun track(id: String, artist: String) =
        Track(id = id, title = id, artist = artist, album = "", artistId = 0, albumId = 0, artworkUri = null, contentUri = uri, durationMs = 200_000)

    private fun adjacentSameArtist(list: List<Track>) = list.zipWithNext().count { (a, b) -> a.artist == b.artist }

    @Test fun `with nothing analysed, it is still a shuffle that keeps an artist from following itself`() {
        val tracks = (0 until 30).map { track("t$it", "artist${it % 3}") }
        var total = 0
        repeat(20) { seed ->
            val out = SmartShuffle.order(tracks, analysisOf = { null }, random = Random(seed))
            assertEquals(tracks.toSet(), out.toSet())
            total += adjacentSameArtist(out)
        }
        // A plain shuffle of three artists puts about nine pairs side by side in thirty songs.
        assertTrue("mean adjacent pairs ${total / 20.0}", total / 20.0 < 3.0)
    }

    @Test fun `analysed songs keep their tempo order and their artists apart`() {
        val tracks = (0 until 24).map { track("t$it", "artist${it % 4}") }
        val analyses = tracks.withIndex().associate { (i, t) -> t.id to TrackAnalysis(status = TrackAnalysis.STATUS_READY, bpm = 100.0 + (i % 6) * 2) }
        val out = SmartShuffle.order(tracks, analysisOf = { analyses[it.id] }, random = Random(4))
        assertEquals(24, out.size)
        assertTrue("adjacent ${adjacentSameArtist(out)}", adjacentSameArtist(out) <= 2)
    }

    @Test fun `tiny lists`() {
        assertTrue(SmartShuffle.order(emptyList(), analysisOf = { null }).isEmpty())
        assertEquals(1, SmartShuffle.order(listOf(track("a", "x")), analysisOf = { null }).size)
    }
}
