package com.ikko.aura.data.home

import com.ikko.aura.reco.AlbumKeys
import com.ikko.aura.reco.ArtistNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionHoldTest {
    private fun album(i: Int, artist: String = "Artist $i") = SectionAlbum("Album $i", artist, ref = "r$i", artworkUrl = "https://a/$i")
    private fun row(id: String, vararg a: SectionAlbum) = HomeSection(id, null, id, a.toList())

    private val held = listOf(row("rock", album(1), album(2), album(3)), row("jazz", album(4), album(5), album(6)))

    @Test fun `with nothing blocked or refused the rows are exactly the ones held, whatever the arrangement of the moment`() {
        val live = listOf(row("rock", album(10), album(11), album(12)))
        assertSame(held, SectionHold.reconcile(held, live, emptySet(), emptySet()))
    }

    @Test fun `a blocked artist leaves every row and the row is made up from the same row of the moment, the others untouched`() {
        val live = listOf(row("rock", album(1), album(2), album(3), ), row("jazz", album(40), album(41), album(42)))
        val liveRock = listOf(row("rock", album(1), album(7), album(8)), row("jazz", album(40), album(41), album(42)))
        val out = SectionHold.reconcile(held, liveRock, setOf(ArtistNames.key("Artist 1")), emptySet())
        assertEquals(listOf("Album 2", "Album 3", "Album 7"), out.first { it.id == "rock" }.albums.map { it.title })
        assertEquals(held[1], out.first { it.id == "jazz" })
    }

    @Test fun `an album refused as a whole goes, by reference or by name`() {
        val refusedByRef = setOf(AlbumKeys.ofRef("r5"))
        val live = listOf(row("jazz", album(4), album(9), album(10), album(11)))
        val out = SectionHold.reconcile(held, live, emptySet(), refusedByRef)
        assertTrue(out.first { it.id == "jazz" }.albums.none { it.ref == "r5" })
        assertEquals(3, out.first { it.id == "jazz" }.albums.size)
        val byName = SectionHold.reconcile(held, live, emptySet(), setOf(AlbumKeys.ofName("Album 4", "Artist 4")))
        assertTrue(byName.first { it.id == "jazz" }.albums.none { it.title == "Album 4" })
    }

    @Test fun `a row that cannot be made up is dropped, not left short, and what shows elsewhere is not offered again`() {
        val live = listOf(row("rock", album(4), album(1)))
        val out = SectionHold.reconcile(held, live, setOf(ArtistNames.key("Artist 1")), emptySet())
        // The live row has nothing new that is not already shown in "jazz" or gone: "rock" has only two left.
        assertTrue(out.none { it.id == "rock" })
        assertEquals(listOf("jazz"), out.map { it.id })
    }

    @Test fun `a radio or an artist tile of a blocked artist is gone too`() {
        val radio = SectionAlbum("Sean Paul", "Station", playsItems = true, blockableArtist = "Sean Paul")
        val fav = SectionAlbum("Sean Paul", "Artiste", isArtist = true)
        assertTrue(SectionHold.gone(radio, setOf(ArtistNames.key("Sean Paul")), emptySet()))
        assertTrue(SectionHold.gone(fav, setOf(ArtistNames.key("Sean Paul")), emptySet()))
    }

    @Test fun `a row that lost an album is made up from the albums it kept in reserve`() {
        val withSpare = HomeSection("rock", null, "rock", listOf(album(1), album(2), album(3)), spare = listOf(album(20), album(21)))
        val out = SectionHold.reconcile(listOf(withSpare), emptyList(), emptySet(), setOf(AlbumKeys.ofRef("r2")))
        assertEquals(listOf("Album 1", "Album 3", "Album 20"), out.single().albums.map { it.title })
        assertEquals(listOf("Album 21"), out.single().spare.map { it.title })
    }
}
