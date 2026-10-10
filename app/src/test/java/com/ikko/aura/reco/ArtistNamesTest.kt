package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistNamesTest {

    @Test fun `case accents and topic suffix are one artist`() {
        assertEquals(ArtistNames.key("Beyoncé"), ArtistNames.key("BEYONCE - Topic"))
        assertEquals("rina sawayama", ArtistNames.key("Rina Sawayama - Topic"))
    }

    @Test fun `a credit line is split into its artists`() {
        assertEquals(listOf("grimes", "poppy", "kid cudi"), ArtistNames.split("Grimes, Poppy & Kid Cudi"))
        assertEquals(listOf("ac/dc"), ArtistNames.split("AC/DC"))
        assertEquals(listOf("a", "b"), ArtistNames.split("A x B"))
    }

    @Test fun `featured artists in a title are credits`() {
        assertEquals(listOf("pinkpantheress"), ArtistNames.featuredIn("Wild And Alone (feat. PinkPantheress)"))
        assertEquals(listOf("a", "b"), ArtistNames.featuredIn("Song [ft. A & B]"))
        assertEquals(setOf("a", "b", "c"), ArtistNames.creditsOf("A", "Song (with B)", listOf("C")).toSet())
        assertEquals("a", ArtistNames.creditsOf("A", "Song (with B)", listOf("C")).first())
    }

    @Test fun `blocking an artist covers collaborations`() {
        val blocked = setOf("kid cudi")
        assertTrue(ArtistNames.involves(ArtistNames.creditsOf("Kid Cudi", "Day 'n' Nite"), "Kid Cudi", blocked))
        // As a featured artist in the title.
        assertTrue(ArtistNames.involves(ArtistNames.creditsOf("Grimes", "Song (feat. Kid Cudi)"), "Grimes", blocked))
        // As one of several artists of the field.
        assertTrue(ArtistNames.involves(ArtistNames.creditsOf("Grimes & Kid Cudi", "Song"), "Grimes & Kid Cudi", blocked))
        // As a featured artist a source lists apart.
        assertTrue(ArtistNames.involves(ArtistNames.creditsOf("Grimes", "Song", listOf("Kid Cudi")), "Grimes", blocked))
        assertFalse(ArtistNames.involves(ArtistNames.creditsOf("Grimes", "Song"), "Grimes", blocked))
    }

    @Test fun `a blocked name that is itself a group matches inside a longer credit`() {
        val blocked = setOf(ArtistNames.key("Tyler, The Creator"))
        assertTrue(ArtistNames.involves(ArtistNames.split("Tyler, The Creator & Kali Uchis"), "Tyler, The Creator & Kali Uchis", blocked))
        // But a single-word name is not matched inside another artist's name.
        assertFalse(ArtistNames.involves(ArtistNames.split("Steve Lacy"), "Steve Lacy", setOf("eve")))
    }
}
