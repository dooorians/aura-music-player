package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackIdentityTest {

    private val index = TrackIdentity.Index(
        listOf(
            TrackIdentity.Entry("Hold The Girl", "Rina Sawayama", 244_000),
            TrackIdentity.Entry("Bad Idea!", "Girl in Red", 180_000),
            TrackIdentity.Entry("Untitled", "", 200_000),
        ),
    )

    private fun has(title: String, artist: String, durationMs: Long = 244_000) =
        index.contains(TrackIdentity.Entry(title, artist, durationMs))

    @Test fun `the same song under another id and spelling is a duplicate`() {
        assertTrue(has("Hold The Girl", "Rina Sawayama - Topic"))
        assertTrue(has("HOLD THE GIRL (Remastered 2023)", "rina sawayama"))
        assertTrue(has("Hold The Girl - Single Version", "Rina Sawayama", 246_500))
        assertTrue(has("Bad Idea!", "girl in red", 181_000))
    }

    @Test fun `a title with a featured artist matches the plain one`() {
        assertTrue(has("Hold The Girl (feat. Someone)", "Rina Sawayama"))
    }

    @Test fun `another recording of the same title is not a duplicate`() {
        assertFalse(has("Hold The Girl (Remix)", "Rina Sawayama"))
        assertFalse(has("Hold The Girl (Live)", "Rina Sawayama"))
        assertFalse(has("Hold The Girl", "Another Artist"))
        // Same title and artist but a very different length: a live cut or an edit.
        assertFalse(has("Hold The Girl", "Rina Sawayama", 400_000))
    }

    @Test fun `missing metadata does not hide a duplicate`() {
        assertTrue(index.contains(TrackIdentity.Entry("Untitled", "Anyone", 200_000)))
        assertEquals(TrackIdentity.titleKey("Song (Remastered)"), TrackIdentity.titleKey("Song"))
    }

    @Test fun `a channel branded with a first name is the same artist as the full name`() {
        val local = TrackIdentity.Index(listOf(TrackIdentity.Entry("Anaheim Star", "Rebecca Black", 166_000, album = "Age of the Exhibitionist")))
        // The artist's auto-generated channel is "rebecca"; the files say "Rebecca Black".
        assertTrue(local.contains(TrackIdentity.Entry("Anaheim Star", "rebecca - Topic", 166_000)))
        assertTrue(local.hasArtist("rebecca"))
        // A shorter shared first word is not enough: "Lisa" is not "Lisa Ono".
        val other = TrackIdentity.Index(listOf(TrackIdentity.Entry("Song", "LiSA", 200_000)))
        assertFalse(other.hasArtist("lisa ono"))
        assertFalse(other.contains(TrackIdentity.Entry("Song", "Lisa Ono", 200_000)))
    }

    @Test fun `another song of an album the listener has is not new to them`() {
        val local = TrackIdentity.Index(
            listOf(
                TrackIdentity.Entry("Anaheim Star", "Rebecca Black", 166_000, album = "Age of the Exhibitionist"),
                TrackIdentity.Entry("Hot Wet Delirious", "Rebecca Black", 190_000, album = "Age of the Exhibitionist (Deluxe)"),
            ),
        )
        assertTrue(local.hasAlbum("Age of the Exhibitionist", listOf("rebecca")))
        assertTrue(local.hasAlbum("Age Of The Exhibitionist (Deluxe Edition)", listOf("rebecca black")))
        // Same title, another artist: not the same album.
        assertFalse(local.hasAlbum("Age of the Exhibitionist", listOf("someone else")))
        assertFalse(local.hasAlbum("Another Album", listOf("rebecca black")))
        // An album a source does not name is no album.
        assertFalse(local.hasAlbum("", listOf("rebecca black")))
        assertFalse(local.hasAlbum("YouTube Music", listOf("rebecca black")))
    }
}
