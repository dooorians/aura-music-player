package com.ikko.aura.ai.playlist

import com.ikko.aura.reco.ArcShape
import com.ikko.aura.reco.PlaylistFlow
import com.ikko.aura.reco.RecoKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlaylistPlannerTest {

    private fun plan(intent: PlaylistIntent) = AiPlaylistPlanner.plan(intent)

    @Test fun `a tempo becomes three windows, the one asked for, its half and its double`() {
        val p = plan(PlaylistIntent(targetBpm = 150, bpmTolerance = 15))
        assertEquals(
            TrackQuery(anyTempo = false, lo = 135.0, hi = 165.0, halfLo = 67.5, halfHi = 82.5, doubleLo = 270.0, doubleHi = 330.0),
            p.query,
        )
        assertEquals(150.0, p.targetBpm!!, 0.0)
        assertEquals(15.0, p.tolerance!!, 0.0)
    }

    @Test fun `the query admits exactly what the SQL keeps`() {
        val q = plan(PlaylistIntent(targetBpm = 150, bpmTolerance = 15)).query
        assertTrue(q.admits(150.0))
        assertTrue(q.admits(135.0))
        assertTrue("half time: a song tagged an octave low", q.admits(75.0))
        assertTrue("no tempo known: never refused", q.admits(0.0))
        assertFalse(q.admits(120.0))
        assertFalse(q.admits(100.0))
        assertTrue(TrackQuery.ANY.admits(42.0))
    }

    @Test fun `a tempo without a tolerance gets a DJ's pitch range, never less than 5`() {
        assertEquals(8.0, plan(PlaylistIntent(targetBpm = 128)).tolerance!!, 0.0)
        assertEquals(5.0, plan(PlaylistIntent(targetBpm = 60)).tolerance!!, 0.0)
    }

    @Test fun `no tempo, no window`() {
        val p = plan(PlaylistIntent(energy = EnergyLevel.LOW))
        assertEquals(TrackQuery.ANY, p.query)
        assertNull(p.targetBpm)
        assertNull(p.tolerance)
    }

    @Test fun `defaults for what the intent leaves open`() {
        val p = plan(PlaylistIntent.EMPTY)
        assertEquals(AiPlaylistPlanner.DEFAULT_MINUTES * 60_000L, p.targetDurationMs)
        assertNull(p.energyTarget)
        assertEquals(emptyList<String>(), p.include)
        assertEquals(ArcShape.WAVE, p.arc)
        assertEquals(Relaxation.NONE, p.relaxation)
        assertEquals(60 * 60_000L, plan(PlaylistIntent(targetDurationMinutes = 60)).targetDurationMs)
    }

    @Test fun `energy levels aim at the low, middle and top of the scale`() {
        assertEquals(0.2, plan(PlaylistIntent(energy = EnergyLevel.LOW)).energyTarget!!, 0.0)
        assertEquals(0.5, plan(PlaylistIntent(energy = EnergyLevel.MEDIUM)).energyTarget!!, 0.0)
        assertEquals(0.85, plan(PlaylistIntent(energy = EnergyLevel.HIGH)).energyTarget!!, 0.0)
    }

    @Test fun `the arc follows the atmosphere, then the intensity`() {
        assertEquals(ArcShape.BUILD, plan(PlaylistIntent(mood = "workout")).arc)
        assertEquals(ArcShape.PLATEAU, plan(PlaylistIntent(mood = "focus", energy = EnergyLevel.HIGH)).arc)
        assertEquals(ArcShape.WIND_DOWN, plan(PlaylistIntent(mood = "sleep")).arc)
        assertEquals("a night drive keeps moving", ArcShape.WAVE, plan(PlaylistIntent(mood = "night drive", energy = EnergyLevel.MEDIUM)).arc)
        assertEquals(ArcShape.BUILD, plan(PlaylistIntent(energy = EnergyLevel.HIGH)).arc)
        assertEquals(ArcShape.PLATEAU, plan(PlaylistIntent(energy = EnergyLevel.LOW)).arc)
    }

    @Test fun `the sequencer gets the weights of the generated playlists with the same arc`() {
        assertEquals(PlaylistFlow.weightsOf(RecoKind.ENERGY), plan(PlaylistIntent(mood = "workout")).weights)
        assertEquals(PlaylistFlow.weightsOf(RecoKind.CHILL), plan(PlaylistIntent(mood = "chill")).weights)
        assertEquals(PlaylistFlow.weightsOf(RecoKind.NIGHT), plan(PlaylistIntent(mood = "sleep")).weights)
        assertEquals(PlaylistFlow.weightsOf(RecoKind.STYLE), plan(PlaylistIntent.EMPTY).weights)
    }

    @Test fun `genres are compared under the app's own names, and refused ones are never asked for`() {
        val p = plan(PlaylistIntent(genresIncluded = listOf("rap", "Hip Hop", "house"), genresExcluded = listOf("house")))
        assertEquals(listOf("hip hop"), p.include)
        assertEquals(listOf("house"), p.exclude)
        assertTrue(p.strictGenres)
    }

    @Test fun `a genre asked for is answered by itself, a more precise one, or its family`() {
        val k = AiPlaylistPlanner::genreKey
        assertTrue(AiPlaylistPlanner.genreMatches(k("house"), k("Deep House")))
        assertTrue(AiPlaylistPlanner.genreMatches(k("rap"), k("French Rap")))
        assertTrue(AiPlaylistPlanner.genreMatches(k("electronic"), k("Techno")))
        assertTrue(AiPlaylistPlanner.genreMatches(k("rock"), k("Shoegaze")))
        assertTrue(AiPlaylistPlanner.genreMatches(k("r&b"), k("RnB")))
        assertFalse("asked for French rap, rap is not enough", AiPlaylistPlanner.genreMatches(k("french rap"), k("Hip-Hop")))
        assertFalse("house is not a family", AiPlaylistPlanner.genreMatches(k("house"), k("Techno")))
        assertFalse(AiPlaylistPlanner.genreMatches(k("jazz"), k("Metal")))
        assertFalse(AiPlaylistPlanner.genreMatches(k("unknown"), ""))
    }

    @Test fun `loosening goes wider tempo, any genre, any tempo, and keeps the refusals`() {
        val start = plan(PlaylistIntent(targetBpm = 120, bpmTolerance = 5, genresIncluded = listOf("jazz"), genresExcluded = listOf("metal")))
        val wider = AiPlaylistPlanner.relax(start)!!
        assertEquals(Relaxation.WIDER_TEMPO, wider.relaxation)
        assertEquals(10.0, wider.tolerance!!, 0.0)
        assertEquals(110.0, wider.query.lo, 0.0)
        assertTrue(wider.strictGenres)
        val anyGenre = AiPlaylistPlanner.relax(wider)!!
        assertEquals(Relaxation.ANY_GENRE, anyGenre.relaxation)
        assertFalse(anyGenre.strictGenres)
        assertFalse(anyGenre.query.anyTempo)
        val anyTempo = AiPlaylistPlanner.relax(anyGenre)!!
        assertEquals(Relaxation.ANY_TEMPO, anyTempo.relaxation)
        assertTrue(anyTempo.query.anyTempo)
        assertEquals(listOf("metal"), anyTempo.exclude)
        assertNull(AiPlaylistPlanner.relax(anyTempo))
    }

    @Test fun `nothing to loosen is the end of loosening`() {
        assertNull(AiPlaylistPlanner.relax(plan(PlaylistIntent(energy = EnergyLevel.HIGH))))
        assertEquals(Relaxation.ANY_GENRE, AiPlaylistPlanner.relax(plan(PlaylistIntent(genresIncluded = listOf("jazz"))))!!.relaxation)
    }
}
