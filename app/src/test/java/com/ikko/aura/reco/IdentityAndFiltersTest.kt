package com.ikko.aura.reco

import com.ikko.aura.reco.authority.CompilationTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityAndFiltersTest {

    // --- deep cuts ---

    @Test fun `the first three hits are set aside and the next ones to the twentieth are kept`() {
        val next = (1..30).toList()
        assertEquals((4..20).toList(), DeepCuts.pick(next))
    }

    @Test fun `a short list is kept whole rather than emptied`() {
        assertEquals(listOf(1, 2, 3, 4, 5), DeepCuts.pick(listOf(1, 2, 3, 4, 5)))
        assertEquals(listOf(1, 2), DeepCuts.pick(listOf(1, 2)))
        assertTrue(DeepCuts.pick(emptyList<Int>()).isEmpty())
        assertEquals(listOf(4, 5, 6), DeepCuts.pick((1..6).toList()))
    }

    // --- blacklist ---

    @Test fun `covers, nightcore, slowed and karaoke are rejected at the door`() {
        listOf(
            "Blinding Lights (Nightcore)", "Heat Waves (Slowed + Reverb)", "Heat Waves - slowed and reverb", "Karaoke Hits", "Song (Sped Up)",
            "Hello (Cover)", "Hello - Piano Cover", "Hello cover by Someone", "Greatest Covers",
        ).forEach { assertTrue(it, CompilationTitles.matches(it)) }
    }

    @Test fun `titles that merely contain the letters stay`() {
        listOf("Discovery", "Cover Me Up", "Undercover", "Recovery", "Covert Affairs", "Slow Dancing in the Dark").forEach {
            assertFalse(it, CompilationTitles.isBlacklisted(it))
        }
    }

    @Test fun `a blacklisted form the request itself carries is not held against the result`() {
        assertTrue(CompilationTitles.matches("Heat Waves (Slowed + Reverb)", "Heat Waves"))
        assertFalse(CompilationTitles.matches("Heat Waves (Slowed + Reverb)", "Heat Waves (Slowed + Reverb)"))
    }

    // --- albums: record and edition ---

    @Test fun `the base key is the record and the edition tag says which one`() {
        assertEquals(AlbumIdentity.baseAlbumKey("Divide"), AlbumIdentity.baseAlbumKey("Divide (Deluxe)"))
        assertEquals("", AlbumIdentity.editionTag("Divide"))
        assertEquals("deluxe", AlbumIdentity.editionTag("Divide (Deluxe Edition)"))
        assertEquals("live", AlbumIdentity.editionTag("Divide - Live"))
        assertNotEquals(AlbumIdentity.key("Divide"), AlbumIdentity.key("Divide (Deluxe)"))
    }

    @Test fun `the same edition fits best, a deluxe of the standard fits less, another record does not fit at all`() {
        assertEquals(2, AlbumIdentity.fit("Divide", "Divide"))
        assertEquals(1, AlbumIdentity.fit("Divide", "Divide (Deluxe)"))
        assertEquals(2, AlbumIdentity.fit("Divide (Deluxe)", "Divide (Deluxe)"))
        assertNull(AlbumIdentity.fit("Divide", "Divide (Live)"))
        assertNull(AlbumIdentity.fit("Divide", "Divide (Instrumental)"))
        assertNull(AlbumIdentity.fit("Divide", "Divide - Tribute"))
        assertNull(AlbumIdentity.fit("Divide", "Divide Remixes"))
        assertNull(AlbumIdentity.fit("Divide", "Multiply"))
        assertNull(AlbumIdentity.fit("", ""))
    }

    // --- songs ---

    @Test fun `a local file and a streamed song share one key whatever the feat, case or remaster tag`() {
        val local = TrackIdentity.canonicalKey("Charli XCX", "Von dutch")
        assertEquals(local, TrackIdentity.canonicalKey("CHARLI XCX", "Von Dutch (feat. A. G. Cook)"))
        assertEquals(TrackIdentity.canonicalKey("Queen", "Bohemian Rhapsody"), TrackIdentity.canonicalKey("Queen", "Bohemian Rhapsody - Remastered 2011"))
        assertEquals(TrackIdentity.canonicalKey("Beyoncé", "Halo"), TrackIdentity.canonicalKey("Beyonce", "Halo"))
    }

    @Test fun `another recording keeps its own key`() {
        assertNotEquals(TrackIdentity.canonicalKey("Queen", "Bohemian Rhapsody"), TrackIdentity.canonicalKey("Queen", "Bohemian Rhapsody (Live)"))
        assertNotEquals(TrackIdentity.canonicalKey("Queen", "Bohemian Rhapsody"), TrackIdentity.canonicalKey("Muse", "Bohemian Rhapsody"))
    }
}
