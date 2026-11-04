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

    @Test fun `a row that cannot be made up stays with what it has left, and what shows elsewhere is not offered again`() {
        val live = listOf(row("rock", album(4), album(1)))
        val out = SectionHold.reconcile(held, live, setOf(ArtistNames.key("Artist 1")), emptySet())
        // The live row has nothing new that is not already shown in "jazz" or gone: "rock" keeps its two, it does not vanish.
        assertEquals(listOf("Album 2", "Album 3"), out.first { it.id == "rock" }.albums.map { it.title })
        assertEquals(listOf("rock", "jazz"), out.map { it.id })
    }

    @Test fun `refusing one album never takes its row away, however little there is to replace it`() {
        for (i in 1..3) {
            val out = SectionHold.reconcile(held, emptyList(), emptySet(), setOf(AlbumKeys.ofRef("r$i")))
            assertEquals(listOf("rock", "jazz"), out.map { it.id })
            assertEquals(2, out.first { it.id == "rock" }.albums.size)
            assertTrue(out.first { it.id == "rock" }.albums.none { it.ref == "r$i" })
        }
    }

    @Test fun `the artist that goes with a refused album takes the albums of theirs from every row, the rows stay`() {
        val mixed = listOf(
            row("rock", album(1, "Same"), album(2, "Other A"), album(3, "Other B")),
            row("jazz", album(4, "Same"), album(5, "Same"), album(6, "Other C")),
        )
        val out = SectionHold.reconcile(mixed, emptyList(), setOf(ArtistNames.key("Same")), emptySet())
        assertEquals(listOf("rock", "jazz"), out.map { it.id })
        assertEquals(listOf("Album 2", "Album 3"), out[0].albums.map { it.title })
        assertEquals(listOf("Album 6"), out[1].albums.map { it.title })
    }

    @Test fun `a row about the blocked artist goes with them, a row about another does not`() {
        val about = HomeSection("artist:Same", "Plus de", "Same", listOf(album(1, "Same"), album(2, "Same"), album(3, "Same")))
        val similar = HomeSection("similar:Same", "Plus de contenu similaire à", "Same", listOf(album(4, "Same"), album(5, "Someone"), album(6, "Someone Else")))
        val other = HomeSection("artist:Else", "Plus de", "Else", listOf(album(7, "Else"), album(8, "Else"), album(9, "Same")))
        val out = SectionHold.reconcile(listOf(about, similar, other), emptyList(), setOf(ArtistNames.key("Same")), emptySet())
        assertEquals(listOf("artist:Else"), out.map { it.id })
        assertEquals(2, out.single().albums.size)
    }

    @Test fun `a row with nothing left of it is dropped`() {
        val all = HomeSection("rock", null, "rock", listOf(album(1, "Same"), album(2, "Same"), album(3, "Same")))
        assertTrue(SectionHold.reconcile(listOf(all), emptyList(), setOf(ArtistNames.key("Same")), emptySet()).isEmpty())
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
