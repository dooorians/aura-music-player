package com.ikko.aura.data.home

import com.ikko.aura.data.model.Album
import com.ikko.aura.reco.Reason
import com.ikko.aura.reco.ReasonCode
import com.ikko.aura.reco.RecoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSectionsTest {
    private fun item(ref: String, album: String, artist: String, code: ReasonCode, subject: String) = RecoItem(
        trackId = "ytm:$ref", title = "t", artist = artist, album = album, durationMs = 1000, artworkUrl = "https://x/$ref",
        albumRef = ref, sourceId = "ytm", sourceName = "YouTube Music", reason = Reason(code, subject), verified = true,
    )

    private fun album(id: Long, title: String, artist: String) =
        Album(id = id, title = title, artist = artist, artistId = 0, artworkUri = null, trackCount = 5, year = 0)

    @Test fun mixesWhatIsPlayedWithWhatTheSourcesOffer() {
        val items = mapOf(
            "new" to listOf(
                item("a1", "Fresh One", "Rina", ReasonCode.NEW_FROM_ARTIST, "Rina"),
                item("a2", "Fresh Two", "Rina", ReasonCode.NEW_FROM_ARTIST, "Rina"),
                item("a3", "Fresh Three", "Rina", ReasonCode.NEW_FROM_ARTIST, "Rina"),
            ),
            "style" to listOf(
                item("b1", "Close A", "X", ReasonCode.RELATED_TO, "Prima"),
                item("b2", "Close B", "Y", ReasonCode.RELATED_TO, "Prima"),
                item("b3", "Close C", "Z", ReasonCode.RELATED_TO, "Prima"),
            ),
        )
        val library = listOf(
            album(1, "Prima", "Adela"), album(2, "Mayhem", "Adela"), album(7, "Third", "Adela"),
            album(3, "Sawayama", "Rina"), album(4, "Hold", "Rina"), album(8, "Chaos", "Rina"),
            album(5, "Solo", "Q"), album(6, "Duo", "W"), album(9, "Trio", "V"), album(10, "Quad", "U"),
        )
        val sections = HomeSections.build(items, library, recentAlbumIds = listOf(3, 1), topPlayedAlbumIds = listOf(5, 6, 9, 10))

        // new releases come first, and the artist played gets its library albums beside what the sources have
        assertEquals("new", sections.first().id)
        val artist = sections.first { it.id.startsWith("artist:") }
        assertTrue(artist.albums.any { it.album != null })
        assertTrue(sections.any { it.id == "similar:Prima" })
        assertTrue(sections.any { it.id == "often" })
        assertTrue(sections.all { it.albums.size % 3 == 0 })
        // an album shows once across the rows
        val keys = sections.flatMap { s -> s.albums.map { it.key } }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun anAlbumTheLibraryAlreadyHasIsNotOfferedAsNew() {
        val items = mapOf(
            "new" to listOf(
                item("a1", "Prima", "Adela", ReasonCode.NEW_RELEASE, ""),
                item("a2", "Other", "Z", ReasonCode.NEW_RELEASE, ""),
                item("a3", "Another", "Y", ReasonCode.NEW_RELEASE, ""),
            ),
        )
        val sections = HomeSections.build(items, listOf(album(1, "Prima", "Adela")), emptyList(), emptyList())
        // the library's Prima is left out: two albums are left, which is not a line of three
        assertFalse(sections.any { it.id == "new" })
    }

    @Test fun songsOfASourceWithoutAlbumPagesStillMakeARow() {
        fun song(id: String, album: String, artist: String) =
            item("", album, artist, ReasonCode.RELATED_TO, "Prima").copy(trackId = "jiosaavn:$id")
        val items = mapOf(
            "style" to listOf(
                song("1", "Alpha", "A"), song("2", "Alpha", "A"), // two songs of one album: one tile
                song("3", "Beta", "B"), song("4", "", "C"),
            ),
        )
        val row = HomeSections.build(items, emptyList(), emptyList(), emptyList()).single { it.id == "similar:Prima" }
        assertEquals(3, row.albums.size)
        assertEquals(2, row.albums.first { it.title == "Alpha" }.items.size)
        assertTrue(row.albums.all { it.ref == null })
    }

    @Test fun `a row that lost an album is completed with another found one, not dropped`() {
        fun r(ref: String, artist: String, code: ReasonCode = ReasonCode.NEW_RELEASE) = item(ref, "album $ref", artist, code, artist)
        val news = (1..4).map { r("n$it", "A$it", ReasonCode.NEW_RELEASE) }
        val full = HomeSections.build(mapOf("k" to news), emptyList(), emptyList(), emptyList())
        assertEquals(3, full.first { it.id == "new" }.albums.size)
        // One of the row's albums is refused (gone from the items): the row stays whole, with the next one found.
        val refused = HomeSections.build(mapOf("k" to news.drop(1)), emptyList(), emptyList(), emptyList())
        assertEquals(3, refused.first { it.id == "new" }.albums.size)
    }

    @Test fun aRowIsNotCompletedWithAlbumsThatHaveNothingToDoWithIt() {
        val items = mapOf(
            "style" to listOf(
                item("b1", "Close A", "X", ReasonCode.RELATED_TO, "Reol"),
                item("c1", "Elsewhere 1", "P", ReasonCode.LISTENED_ARTIST, "P"),
                item("c2", "Elsewhere 2", "Q", ReasonCode.LISTENED_ARTIST, "Q"),
                item("c3", "Elsewhere 3", "R", ReasonCode.NEW_RELEASE, ""),
            ),
        )
        val sections = HomeSections.build(items, emptyList(), emptyList(), emptyList())
        // One album is close to Reol and one is new: neither is a row, and nothing is borrowed to make them one.
        assertTrue(sections.none { it.id.startsWith("similar:") })
        assertTrue(sections.none { it.id == "new" })
    }

    @Test fun albumsLeftOutOfARowStayWithItAsSpares() {
        val items = mapOf(
            "style" to (1..5).map { item("s$it", "Close $it", "Art $it", ReasonCode.RELATED_TO, "Prima") },
        )
        val row = HomeSections.build(items, emptyList(), emptyList(), emptyList()).first { it.id == "similar:Prima" }
        assertEquals(3, row.albums.size)
        assertEquals(2, row.spare.size)
        assertTrue(row.spare.none { s -> row.albums.any { it.key == s.key } })
    }
}
