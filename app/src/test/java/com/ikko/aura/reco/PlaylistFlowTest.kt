package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generated playlists: a varied set (MMR), then an order that flows. */
class PlaylistFlowTest {
    private val profile = profileOf(listen("Song", "Liked Artist"))

    @Test fun `a discovery spreads over more artists before it takes second songs`() {
        // Thirty strangers with two songs each, close to the listener, and twenty with one song, a little less close.
        val doubles = (1..30).flatMap { a -> (1..2).map { s -> candidate("Tune $a-$s", "Stranger $a", provenance = listOf(Provenance(ProvKind.ARTIST, "Stranger $a", 0.8))) } }
        val singles = (1..20).map { a -> candidate("Single $a", "Newcomer $a", provenance = listOf(Provenance(ProvKind.ARTIST, "Newcomer $a", 0.7))) }
        val picks = Ranker.select(RecoKind.DISCOVERY, doubles + singles, ctx(profile), HashSet())
        val artists = picks.map { it.candidate.artist }.distinct().size
        assertTrue("${picks.size} picks over $artists artists", artists >= 30)
        // Still given back best first.
        assertEquals(picks.map { it.score }.sortedDescending(), picks.map { it.score })
    }

    @Test fun `the order of a generated playlist keeps its songs and spaces its artists`() {
        val pool = (1..12).flatMap { a -> (1..2).map { s -> candidate("T$a-$s", "Artist $a", provenance = listOf(Provenance(ProvKind.ARTIST, "Artist $a", 0.8))) } }
        val picks = Ranker.select(RecoKind.STYLE, pool, ctx(profile), HashSet())
        val ordered = PlaylistFlow.sequence(RecoKind.STYLE, picks, ctx(profile))
        assertEquals(picks.map { it.candidate.id }.toSet(), ordered.map { it.candidate.id }.toSet())
        assertEquals(picks.size, ordered.size)
        assertTrue(ordered.zipWithNext().none { (a, b) -> a.candidate.credits.first() == b.candidate.credits.first() })
    }

    @Test fun `an energetic playlist builds, a night one winds down`() {
        val pool = (1..16).map { i -> candidate("Track $i", "Artist $i", provenance = listOf(Provenance(ProvKind.ARTIST, "Artist $i", 0.8))) }
        val features = pool.withIndex().associate { (i, c) ->
            c.identity to SongFeatures(feel = ReccoFeatures(energy = (i * 7 % 16) / 15.0, acousticness = 0.3, valence = 0.5, danceability = 0.5, loudness = -8.0, tempo = 0.0))
        }
        val c = ctx(profile).copy(songFeatures = features)
        val picks = pool.mapIndexed { i, cand -> Pick(cand, Reason(ReasonCode.NONE), 1.0 - i * 0.001) }
        fun energies(kind: RecoKind) = PlaylistFlow.sequence(kind, picks, c).map { features.getValue(it.candidate.identity).feel!!.energy }
        val build = energies(RecoKind.ENERGY)
        val wind = energies(RecoKind.NIGHT)
        assertTrue("build $build", build.takeLast(5).average() > build.take(5).average() + 0.3)
        assertTrue("wind $wind", wind.take(5).average() > wind.takeLast(5).average() + 0.3)
    }

    @Test fun `tempo and energy are read from the best measure there is`() {
        val c = candidate("X", "Y").copy(bpm = 99.0)
        assertEquals(99.0, PlaylistFlow.bpmOf(c, null)!!, 0.0)
        assertEquals(120.0, PlaylistFlow.bpmOf(c, SongFeatures(bpm = 120.0))!!, 0.0)
        val probe = AudioProbe(bpm = 126.0, beatConfidence = 0.9, rmsDb = -12.0, lowShare = 0.3, variability = 0.4)
        assertEquals(126.0, PlaylistFlow.bpmOf(c, SongFeatures(bpm = 120.0, probe = probe))!!, 0.0)
        // An unsure beat is not trusted over the catalogue.
        assertEquals(120.0, PlaylistFlow.bpmOf(c, SongFeatures(bpm = 120.0, probe = probe.copy(beatConfidence = 0.1)))!!, 0.0)
        assertNull(PlaylistFlow.bpmOf(candidate("X", "Y"), SongFeatures(bpm = 0.0)))
        assertNull(PlaylistFlow.energyOf(null))
        assertNull(PlaylistFlow.energyOf(SongFeatures(bpm = 120.0)))
        assertTrue(PlaylistFlow.energyOf(SongFeatures(gainDb = -6.0))!! > PlaylistFlow.energyOf(SongFeatures(gainDb = -16.0))!!)
        assertTrue(PlaylistFlow.energyOf(SongFeatures(probe = probe))!! in 0.0..1.0)
    }

    @Test fun `tiny playlists are left as they are`() {
        val picks = listOf(Pick(candidate("A", "B"), Reason(ReasonCode.NONE), 1.0))
        assertEquals(picks, PlaylistFlow.sequence(RecoKind.DAILY, picks, ctx(profile)))
        assertTrue(PlaylistFlow.sequence(RecoKind.DAILY, emptyList(), ctx(profile)).isEmpty())
    }
}
