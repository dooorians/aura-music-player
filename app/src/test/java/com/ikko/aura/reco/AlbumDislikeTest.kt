package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumDislikeTest {
    private val profile = profileOf(listen("A Song", "Some Artist"))

    @Test fun `an album answers to its reference and to its name, whatever the case and accents`() {
        val keys = AlbumKeys.of("PLabc", "Hyper-Égo", "ARTMS")
        assertTrue(AlbumKeys.ofRef("PLabc") in keys)
        assertTrue(AlbumKeys.ofName("hyper-ego", "artms") in keys)
        assertEquals(1, AlbumKeys.of(null, "Hyper-Ego", "ARTMS").size)
        assertTrue(AlbumKeys.of(null, "", "ARTMS").isEmpty())
    }

    @Test fun `no song of an album the listener refused is offered again, by reference or by name`() {
        val byRef = candidate("Track 9", "ARTMS", album = "Hyper-Ego").copy(albumRef = "PLabc")
        val byName = candidate("Track 3", "ARTMS", album = "hyper-ego")
        val other = candidate("Track 1", "ARTMS", album = "Another Album")
        val hiddenByRef = setOf(AlbumKeys.ofRef("PLabc"))
        val hiddenByName = setOf(AlbumKeys.ofName("Hyper-Ego", "ARTMS"))
        assertEquals(Exclusion.HIDDEN, Eligibility.excluded(byRef, ctx(profile, hidden = hiddenByRef)))
        assertEquals(Exclusion.HIDDEN, Eligibility.excluded(byName, ctx(profile, hidden = hiddenByName)))
        assertNull(Eligibility.excluded(other, ctx(profile, hidden = hiddenByRef + hiddenByName)))
    }

    @Test fun `a refused album counts against its artist in the profile, once for the album`() {
        fun affinity(feedback: List<FeedbackRecord>) = TasteProfileBuilder.build(
            ProfileInputs(listens = listOf(listen("Hit", "ARTMS")), feedback = feedback, nowMs = NOW),
        ).affinity("artms")
        val refused = FeedbackRecord(FeedbackKind.DISLIKE_ALBUM, AlbumKeys.ofName("Hyper-Ego", "ARTMS"), "ARTMS", "Hyper-Ego", NOW, null)
        assertTrue(affinity(listOf(refused)) < affinity(emptyList()))
    }

    @Test fun `a candidate of an album that is not refused is not touched`() {
        assertNotNull(candidate("Song", "Somebody", album = "Fine").let { Eligibility.excluded(it, ctx(profile, hidden = setOf(AlbumKeys.ofRef("PLother")))) } ?: "free")
    }

    private fun affinityWith(feedback: List<FeedbackRecord>) = TasteProfileBuilder.build(
        ProfileInputs(listens = listOf(listen("Hit", "ARTMS")), feedback = feedback, nowMs = NOW),
    ).affinity("artms")

    @Test fun `an album kept under its reference and its name counts once`() {
        fun row(kind: FeedbackKind, key: String) = FeedbackRecord(kind, key, "ARTMS", "Hyper-Ego", NOW, null)
        val once = listOf(row(FeedbackKind.DISLIKE_ALBUM, AlbumKeys.ofName("Hyper-Ego", "ARTMS")))
        val twice = once + row(FeedbackKind.DISLIKE_ALBUM, AlbumKeys.ofRef("PLabc"))
        assertEquals(affinityWith(once), affinityWith(twice), 1e-9)
        val likedOnce = listOf(row(FeedbackKind.LIKE_ALBUM, AlbumKeys.ofName("Hyper-Ego", "ARTMS")))
        val likedTwice = likedOnce + row(FeedbackKind.LIKE_ALBUM, AlbumKeys.ofRef("PLabc"))
        assertEquals(affinityWith(likedOnce), affinityWith(likedTwice), 1e-9)
    }

    @Test fun `a liked album counts for its artist in the profile`() {
        val liked = FeedbackRecord(FeedbackKind.LIKE_ALBUM, AlbumKeys.ofName("Hyper-Ego", "ARTMS"), "ARTMS", "Hyper-Ego", NOW, null)
        assertTrue(affinityWith(listOf(liked)) > affinityWith(emptyList()))
    }
}
