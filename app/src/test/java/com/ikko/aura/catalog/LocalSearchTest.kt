package com.ikko.aura.catalog

import android.net.TestUri
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Artist
import com.ikko.aura.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchTest {

    private fun track(id: String, title: String, artist: String, album: String, artistId: Long, albumId: Long) = Track(
        id = id, title = title, artist = artist, album = album, artistId = artistId, albumId = albumId,
        artworkUri = null, contentUri = TestUri.INSTANCE, durationMs = 200_000,
    )

    private val limits = LocalSearch.Limits(tracks = 30, albums = 15, artists = 15)

    @Test
    fun `the match expression is each word as a prefix, all required`() {
        assertEquals("edith* piaf*", LocalSearch.matchExpression("  Edith   Piaf "))
        assertEquals("beyoncé*", LocalSearch.matchExpression("BEYONCÉ"))
    }

    @Test
    fun `nothing typed can be read as FTS syntax`() {
        assertEquals("ac* dc*", LocalSearch.matchExpression("AC/DC"))
        assertEquals("guns* n* roses*", LocalSearch.matchExpression("Guns N' Roses"))
        assertEquals("a* or* b*", LocalSearch.matchExpression("a OR b"))
        assertEquals("near* not* and*", LocalSearch.matchExpression("NEAR NOT AND"))
        assertEquals("title* x*", LocalSearch.matchExpression("title:x"))
        assertEquals("rock*", LocalSearch.matchExpression("\"rock\"*"))
        assertEquals("live*", LocalSearch.matchExpression("-live"))
        assertEquals("100*", LocalSearch.matchExpression("100%"))
        assertEquals("a* b*", LocalSearch.matchExpression("(a) {b}"))
    }

    @Test
    fun `a query without a word asks nothing of the index`() {
        assertNull(LocalSearch.matchExpression(""))
        assertNull(LocalSearch.matchExpression("   "))
        assertNull(LocalSearch.matchExpression("\"*-:()"))
        assertNull(LocalSearch.matchExpression("🎵🎶"))
    }

    @Test
    fun `letters of every script are words, and accents stay on the word they belong to`() {
        assertEquals(listOf("björk"), LocalSearch.terms("Björk"))
        assertEquals(listOf("東京事変"), LocalSearch.terms("東京事変"))
        assertEquals(listOf("кино"), LocalSearch.terms("Кино"))
        // A decomposed é (e + combining accent) is one word, not "e" and nothing.
        assertEquals(listOf("café"), LocalSearch.terms("Café"))
    }

    @Test
    fun `terms are capped and deduplicated`() {
        val many = (1..20).joinToString(" ") { "w$it" }
        assertEquals(8, LocalSearch.terms(many).size)
        assertEquals(listOf("la"), LocalSearch.terms("la LA La"))
    }

    @Test
    fun `fold drops case and accents`() {
        assertEquals("edith piaf", LocalSearch.fold("ÉDITH Piaf"))
        assertEquals("sigur ros", LocalSearch.fold("Sigur Rós"))
        assertEquals("cafe", LocalSearch.fold("Café"))
    }

    @Test
    fun `startsWords wants every term at the start of a word`() {
        assertTrue(LocalSearch.startsWords("The Beatles", listOf("beat")))
        assertTrue(LocalSearch.startsWords("Édith Piaf", listOf("pia", "edi")))
        assertFalse(LocalSearch.startsWords("The Beatles", listOf("eatles")))
        assertFalse(LocalSearch.startsWords("Daft Punk", listOf("daft", "rock")))
        assertFalse(LocalSearch.startsWords("anything", emptyList()))
    }

    private val tracks = listOf(
        track("1", "Get Lucky", "Daft Punk feat. Pharrell Williams", "Random Access Memories", artistId = 10, albumId = 100),
        track("2", "Happy", "Pharrell Williams", "G I R L", artistId = 20, albumId = 200),
        track("3", "La Vie en rose", "Édith Piaf", "La Vie en rose", artistId = 30, albumId = 300),
        track("ytm:x", "Hymne à l'amour", "Édith Piaf", "", artistId = 0, albumId = 0),
    )
    private val albums = listOf(
        Album(100, "Random Access Memories", "Daft Punk", 10, null, 1, 2013),
        Album(200, "G I R L", "Pharrell Williams", 20, null, 1, 2014),
        Album(300, "La Vie en rose", "Édith Piaf", 30, null, 1, 1947),
    )
    private val artists = listOf(
        Artist(10, "Daft Punk", 1, 1),
        Artist(20, "Pharrell Williams", 1, 1),
        Artist(30, "Édith Piaf", 1, 1),
    )

    @Test
    fun `hits become tracks in library order, and the albums and artists named like the query`() {
        val hits = listOf(
            SearchHit("ytm:x", TrackSearchSql.ARTIST),
            SearchHit("3", TrackSearchSql.ARTIST),
        )
        val r = LocalSearch.fromHits("edith", hits, tracks, albums, artists, limits)
        assertEquals(listOf("3", "ytm:x"), r.tracks.map { it.id })
        assertEquals(listOf(300L), r.albums.map { it.id })
        assertEquals(listOf(30L), r.artists.map { it.id })
    }

    @Test
    fun `a featured artist does not list the main artist`() {
        // "pharrell" is in the artist field of track 1, whose artist on screen is Daft Punk.
        val hits = listOf(SearchHit("1", TrackSearchSql.ARTIST), SearchHit("2", TrackSearchSql.ARTIST))
        val r = LocalSearch.fromHits("pharrell", hits, tracks, albums, artists, limits)
        assertEquals(listOf("1", "2"), r.tracks.map { it.id })
        assertEquals(listOf(20L), r.artists.map { it.id })
        assertEquals(listOf(200L), r.albums.map { it.id })
    }

    @Test
    fun `an album hit lists the album, not its tracks`() {
        val r = LocalSearch.fromHits("random", listOf(SearchHit("1", TrackSearchSql.ALBUM)), tracks, albums, artists, limits)
        assertTrue(r.tracks.isEmpty())
        assertEquals(listOf(100L), r.albums.map { it.id })
    }

    @Test
    fun `a key outside the library and a hidden kind are left out`() {
        val hits = listOf(SearchHit("gone", TrackSearchSql.TITLE), SearchHit("2", TrackSearchSql.TITLE))
        val r = LocalSearch.fromHits("happy", hits, tracks, albums, artists, LocalSearch.Limits(0, 15, 15))
        assertTrue(r.tracks.isEmpty())
        val all = LocalSearch.fromHits("happy", hits, tracks, albums, artists, limits)
        assertEquals(listOf("2"), all.tracks.map { it.id })
    }

    @Test
    fun `the scan still finds part of a word, accents aside`() {
        val r = LocalSearch.scan("dith", tracks, albums, artists, limits)
        assertEquals(listOf("3", "ytm:x"), r.tracks.map { it.id })
        assertEquals(listOf(30L), r.artists.map { it.id })
        assertEquals(listOf("ytm:x"), LocalSearch.scan("HYMNE A", tracks, albums, artists, limits).tracks.map { it.id })
        assertTrue(LocalSearch.scan("   ", tracks, albums, artists, limits).isEmpty)
    }
}
