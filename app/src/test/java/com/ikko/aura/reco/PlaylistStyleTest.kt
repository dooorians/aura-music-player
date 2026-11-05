package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistStyleTest {

    // --- the judge shared with Home ---

    @Test fun `an artist belongs where one of their leading tags is, not where it comes fifth`() {
        val rock = setOf("rock", "punk", "metal")
        assertTrue(StyleFit.belongs(rock, listOf("alternative rock", "indie", "pop")))
        assertFalse(StyleFit.belongs(rock, listOf("pop", "female vocalists", "dance", "pop rock", "rock")))
        assertTrue(StyleFit.belongs(null, listOf("anything")))
    }

    @Test fun `no tags known keeps an artist in or out as the caller says`() {
        assertTrue(StyleFit.belongs(setOf("rock"), emptyList(), unknownOk = true))
        assertFalse(StyleFit.belongs(setOf("rock"), emptyList()))
    }

    @Test fun `R&B is told by Last_fm's spelling too`() {
        assertEquals("rnb", GenreNormalizer.family("rnb"))
        assertEquals("rnb", GenreNormalizer.family("alternative rnb"))
        assertEquals("rnb", GenreNormalizer.family("r&b"))
    }

    // --- the playlists ---

    private val profile = profileOf(
        *Array(14) { listen("Liked $it", "Liked Artist", ageDays = it + 1) },
        *Array(10) { listen("Other $it", "Other Artist", ageDays = it + 2) },
        *Array(8) { listen("Third $it", "Third Artist", ageDays = it + 3) },
        library = List(10) { LibraryEntry("Song $it", "Liked Artist", 2020, "Pop") },
    )

    private fun tagged(vararg pairs: Pair<String, List<String>>) = ctx(profile).copy(artistTags = pairs.associate { ArtistNames.key(it.first) to it.second })

    @Test fun `Dans ton style holds the styles the listener plays, judged on the artist's tags`() {
        val song = candidate("A Song", "Liked Artist")
        val pop = Ranker.select(RecoKind.STYLE, listOf(song), tagged("Liked Artist" to listOf("pop", "dance")), HashSet())
        assertEquals(listOf("A Song"), pop.map { it.candidate.title })
        val metal = Ranker.select(RecoKind.STYLE, listOf(song), tagged("Liked Artist" to listOf("heavy metal", "metal", "hard rock")), HashSet())
        assertTrue(metal.isEmpty())
    }

    @Test fun `an artist with no tags known is not turned away from a playlist`() {
        val song = candidate("A Song", "Liked Artist")
        assertEquals(1, Ranker.select(RecoKind.STYLE, listOf(song), ctx(profile), HashSet()).size)
    }

    @Test fun `a metal band is not Chill or Nuit, but a calm pop artist is`() {
        val calm = candidate("Soft Piano Lullaby", "Liked Artist", durationMs = 220_000)
        val metal = Ranker.select(RecoKind.CHILL, listOf(calm), tagged("Liked Artist" to listOf("metal", "heavy metal")), HashSet())
        assertTrue(metal.isEmpty())
        val pop = Ranker.select(RecoKind.CHILL, listOf(calm), tagged("Liked Artist" to listOf("pop", "acoustic")), HashSet())
        assertEquals(1, pop.size)
        val night = candidate("Midnight Nocturne", "Liked Artist", durationMs = 300_000)
        assertTrue(Ranker.select(RecoKind.NIGHT, listOf(night), tagged("Liked Artist" to listOf("punk", "pop punk")), HashSet()).isEmpty())
    }

    @Test fun `a symphony is not Ca bouge`() {
        val loud = candidate("Club Bass Banger", "Liked Artist", durationMs = 190_000)
        assertTrue(Ranker.select(RecoKind.ENERGY, listOf(loud), tagged("Liked Artist" to listOf("classical", "orchestral")), HashSet()).isEmpty())
        assertEquals(1, Ranker.select(RecoKind.ENERGY, listOf(loud), tagged("Liked Artist" to listOf("electronic", "dance")), HashSet()).size)
    }

    @Test fun `Decouvertes, the daily mix and Nouveautes are not narrowed to one style`() {
        val song = candidate("A Song", "Liked Artist")
        val metal = tagged("Liked Artist" to listOf("heavy metal", "metal"))
        listOf(RecoKind.DISCOVERY, RecoKind.DAILY, RecoKind.NEW).forEach { assertTrue(it.name, Ranker.suits(it, song, metal)) }
        assertFalse(Ranker.suits(RecoKind.STYLE, song, metal))
    }
}
