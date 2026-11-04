package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteProfileTest {

    @Test fun `one isolated listen is a small step`() {
        val p = profileOf(listen("Song", "Solo Artist"))
        val a = p.affinity("solo artist")
        assertTrue("one listen should not dominate: $a", a in 0.05..0.3)
        assertTrue(p.coldStart)
    }

    @Test fun `a habit counts far more than one listen`() {
        val habit = profileOf(*Array(25) { listen("Song $it", "Fav Artist", ageDays = it) })
        assertTrue(habit.affinity("fav artist") > 0.6)
        assertFalse(habit.coldStart)
    }

    @Test fun `recent listening outweighs an old habit of the same size`() {
        val old = profileOf(*Array(12) { listen("S$it", "Old Artist", ageDays = 300 + it) })
        val recent = profileOf(*Array(12) { listen("S$it", "New Artist", ageDays = 1 + it) })
        assertTrue(recent.affinity("new artist") > old.affinity("old artist"))
        // And the old habit is not forgotten.
        assertTrue(old.affinity("old artist") > 0.1)
    }

    @Test fun `an interruption is not a rejection and a skip is`() {
        val base = Array(10) { listen("S$it", "Artist", ageDays = it + 1) }
        val interrupted = profileOf(*base, *Array(6) { listen("X$it", "Artist", Outcome.INTERRUPTED, ageDays = 1) })
        val skipped = profileOf(*base, *Array(6) { listen("X$it", "Artist", Outcome.SKIPPED_EARLY, ageDays = 1) })
        assertEquals(0.0, interrupted.penalty["artist"] ?: 0.0, 1e-9)
        assertTrue((skipped.penalty["artist"] ?: 0.0) > 0.2)
        // A skip dents the liking without cancelling it.
        assertTrue(skipped.affinity("artist") > 0.0)
        assertTrue(skipped.affinity("artist") < interrupted.affinity("artist"))
    }

    @Test fun `a skip in one playlist weighs less in the others`() {
        val p = profileOf(*Array(4) { listen("X$it", "Artist", Outcome.SKIPPED, context = "night") })
        val inNight = p.dislike(listOf("artist"), "night")
        val inEnergy = p.dislike(listOf("artist"), "energy")
        assertTrue(inNight > inEnergy)
        assertTrue(inEnergy > 0.0)
    }

    @Test fun `a known track is not a rejection nor a like`() {
        val inputs = ProfileInputs(
            feedback = listOf(FeedbackRecord(FeedbackKind.KNOWN, "ytm:1", "Some Artist", "Some Song", NOW, null)),
            nowMs = NOW,
        )
        val p = TasteProfileBuilder.build(inputs)
        assertEquals(0.0, p.affinity("some artist"), 1e-9)
        assertEquals(0.0, p.penalty["some artist"] ?: 0.0, 1e-9)
        assertTrue("some artist" in p.knownArtists)
    }

    @Test fun `a blocked artist leaves the profile`() {
        val inputs = ProfileInputs(
            listens = List(10) { listen("S$it", "Blocked One") },
            feedback = listOf(FeedbackRecord(FeedbackKind.BLOCK_ARTIST, "blocked one", "Blocked One", "", NOW, null)),
            nowMs = NOW,
        )
        assertEquals(0.0, TasteProfileBuilder.build(inputs).affinity("blocked one"), 1e-9)
    }

    @Test fun `with no history the library and the typed artists give a start`() {
        val library = List(8) { LibraryEntry("T$it", "Library Artist", 2019, "Pop") }
        val p = profileOf(library = library, seeds = listOf("Typed Artist"))
        assertTrue(p.coldStart)
        assertTrue(p.affinity("typed artist") > p.affinity("library artist"))
        assertTrue(p.affinity("library artist") > 0.0)
        assertEquals(1.0, p.genres["Pop"]!!, 1e-9)
    }

    @Test fun `no signal at all gives an empty profile`() {
        val p = TasteProfileBuilder.build(ProfileInputs(nowMs = NOW))
        assertTrue(p.artists.isEmpty())
        assertTrue(p.coldStart)
    }

    @Test fun `an artist chosen in the picker weighs more than one typed in, and more than a first like`() {
        val typed = TasteProfileBuilder.build(ProfileInputs(seedArtists = listOf("Magdalena Bay"), nowMs = NOW))
        val picked = TasteProfileBuilder.build(ProfileInputs(seedArtists = listOf("Magdalena Bay"), pickedArtists = listOf("Magdalena Bay"), nowMs = NOW))
        assertTrue(picked.affinity("magdalena bay") > typed.affinity("magdalena bay") + 0.2)
        assertTrue(picked.affinity("magdalena bay") > 0.6)
    }

    @Test fun `summary lists what the algorithm learned, most-loved first`() {
        val p = profileOf(
            listen("A1", "Artist A"), listen("A2", "Artist A"),
            listen("B1", "Artist B"),
        )
        val s = p.summary()
        assertEquals("Artist A", s.topArtists.first().first)
        assertTrue(s.topArtists.first().second > s.topArtists.last().second)
    }
}
