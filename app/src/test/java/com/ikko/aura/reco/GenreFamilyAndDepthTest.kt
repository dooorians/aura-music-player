package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreFamilyAndDepthTest {

    // --- genre families ---

    @Test fun `genres are grouped by what their names say`() {
        assertEquals("pop", GenreNormalizer.family("Dream Pop"))
        assertEquals("rock", GenreNormalizer.family("Shoegaze"))
        assertEquals("electronic", GenreNormalizer.family("Deep House"))
        assertEquals("hip-hop", GenreNormalizer.family("Trap"))
        assertEquals("metal", GenreNormalizer.family("Black Metal"))
        assertNull(GenreNormalizer.family("Zzyzx"))
    }

    @Test fun `similarity is full for the same, half in a family, a quarter next door, none far away`() {
        assertEquals(1.0, GenreNormalizer.similarity("Indie Rock", "indie rock"), 0.0)
        assertEquals(0.5, GenreNormalizer.similarity("Dream Pop", "Synthpop"), 0.0)
        assertEquals(0.25, GenreNormalizer.similarity("Dream Pop", "Shoegaze"), 0.0)
        assertEquals(0.0, GenreNormalizer.similarity("Dream Pop", "Death Metal"), 0.0)
        assertEquals(0.0, GenreNormalizer.similarity("Zzyzx", "Dream Pop"), 0.0)
    }

    @Test fun `the soft jaccard gives credit to close genres and keeps the exact cases exact`() {
        assertEquals(1.0, GenreNormalizer.softJaccard(setOf("shoegaze", "dream pop"), setOf("dream pop", "shoegaze")), 1e-9)
        assertEquals(0.0, GenreNormalizer.softJaccard(setOf("death metal"), setOf("bossa nova")), 1e-9)
        val close = GenreNormalizer.softJaccard(setOf("dream pop"), setOf("synthpop"))
        assertTrue(close > 0.0 && close < 1.0)
        assertEquals(0.0, GenreNormalizer.softJaccard(emptySet(), setOf("rock")), 0.0)
    }

    @Test fun `two songs of close styles are closer than two of distant ones for the ranker`() {
        fun entry(id: String, artist: String, g: String) = SmartEntry(id, artist, "al$id", setOf(g), null)
        val ctx = SmartContext(
            nowMs = 1_000L * 86_400_000L, dayPart = DayPart.EVENING, current = entry("cur", "x", "Dream Pop"),
            artistAffinity = emptyMap(), blockedArtists = emptySet(), similarToCurrent = emptyMap(), cooccurrence = emptyMap(), session = SessionContext(),
        )
        val scores = SmartRanker.score(listOf(entry("near", "a", "Synthpop"), entry("far", "b", "Death Metal")), ctx).associate { it.entry.trackId to it.score }
        assertTrue(scores.getValue("near") > scores.getValue("far"))
    }

    // --- hits and deep cuts ---

    private val profile = profileOf(*Array(6) { listen("Song $it", "Liked Artist") })

    private fun picksWith(hits: Map<String, List<String>>): List<String> {
        val hit = candidate("The Big Hit", "Stranger")
        val deep = candidate("A Deep Cut", "Stranger")
        val context = ctx(profile).copy(hits = hits)
        return Ranker.select(RecoKind.DISCOVERY, listOf(hit, deep), context, HashSet()).map { it.candidate.title }
    }

    @Test fun `an artist's hit comes after their deep cut in a discovery`() {
        val top = (listOf("The Big Hit", "Other Hit", "Third Hit") + (1..14).map { "Track $it" }).map(TrackIdentity::titleKey)
        val order = picksWith(mapOf("stranger" to top))
        assertEquals("A Deep Cut", order.first())
    }

    @Test fun `with no knowledge of the artist's hits nothing changes`() {
        assertEquals(picksWith(emptyMap()), picksWith(mapOf("someone else" to listOf("x"))))
    }

    @Test fun `a short list of tracks cannot call anything deep`() {
        val top = listOf("The Big Hit", "Other Hit", "Third Hit").map(TrackIdentity::titleKey)
        val c = candidate("A Deep Cut", "Stranger")
        assertEquals(0.0, Ranker.depthAdjust(c, ctx(profile).copy(hits = mapOf("stranger" to top))), 0.0)
        assertTrue(Ranker.depthAdjust(candidate("The Big Hit", "Stranger"), ctx(profile).copy(hits = mapOf("stranger" to top))) < 0.0)
    }
}
