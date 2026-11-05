package com.ikko.aura.reco.authority

import com.ikko.aura.data.deezer.DeezerClient
import com.ikko.aura.data.musicbrainz.MusicBrainzClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two artists of one name: the rapper comes first in Deezer's list, the pop group is the one the listener plays. */
class ArtistNamesakeTest {
    private val searchJson = """
        { "data": [
            { "id": 76551192, "name": "F5ve", "nb_fan": 861 },
            { "id": 263570761, "name": "F5ve", "nb_fan": 2893 }
        ] }
    """.trimIndent()

    private fun deezer(topOfFirst: String = """{ "data": [] }""") = DeezerClient(minIntervalMs = 0, httpGet = { url ->
        when {
            "/search/artist" in url -> searchJson
            "/artist/76551192/top" in url -> topOfFirst
            "/artist/263570761/top" in url -> """{ "data": [ { "id": 1, "title": "I Choose You" }, { "id": 2, "title": "Magic Clock" } ] }"""
            else -> """{ "data": [] }"""
        }
    })

    private fun authority(
        lastFmTop: List<String>,
        topOfFirst: String = """{ "data": [] }""",
        identities: ArtistIdentityStore? = null,
    ) = ArtistAuthority(
        mbClient = MusicBrainzClient(httpGet = { """{ "count": 0, "artists": [] }""" }),
        deezerClient = deezer(topOfFirst),
        lastFmTagsProvider = { emptyList() },
        lastFmMbidProvider = { null },
        lastFmTopTracksProvider = { lastFmTop },
        identities = identities,
    )

    @Test fun `of two artists of one name, the one whose songs Last_fm gives is taken, not the first of the list`() = runBlocking {
        val picked = authority(lastFmTop = listOf("I Choose You", "Bow Chika Wow Wow")).deezerArtist("F5ve")
        assertEquals(263570761L, picked?.id)
    }

    @Test fun `with nothing from Last_fm, a profile with songs comes before an empty one, then the most followed`() = runBlocking {
        val picked = authority(lastFmTop = emptyList()).deezerArtist("F5ve")
        assertEquals(263570761L, picked?.id)
    }

    @Test fun `the songs win over the fans`() = runBlocking {
        // The rapper has fewer fans, but his songs are the ones Last_fm lists.
        val rapperTop = """{ "data": [ { "id": 9, "title": "Fun N Stuff" } ] }"""
        val picked = authority(lastFmTop = listOf("Fun N Stuff"), topOfFirst = rapperTop).deezerArtist("F5ve")
        assertEquals(76551192L, picked?.id)
    }

    private class MemoryIdentities : ArtistIdentityStore {
        val map = ConcurrentHashMap<String, DisambiguatedArtist>()
        override fun get(key: String) = map[key]
        override fun put(key: String, artist: DisambiguatedArtist) { map[key] = artist }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test fun `a chosen artist is settled once, and asked first afterwards`() = runBlocking {
        val store = MemoryIdentities()
        val first = authority(lastFmTop = listOf("I Choose You"), identities = store)
        assertTrue(!first.isPinned("F5ve"))
        val pinned = first.pin("F5ve")
        assertEquals(263570761L, pinned?.deezerId)
        assertTrue(first.isPinned("F5ve"))
        // Later, whatever the catalogues answer (here: the other artist, and Last_fm knowing nothing), the settled one is kept.
        val later = authority(lastFmTop = listOf("Fun N Stuff"), topOfFirst = """{ "data": [ { "id": 9, "title": "Fun N Stuff" } ] }""", identities = store)
        assertEquals(263570761L, later.deezerArtist("F5ve")?.id)
        assertEquals(263570761L, later.resolve("F5ve")?.deezerId)
    }

    @Test fun `an artist no catalogue knows is not settled`() = runBlocking {
        val empty = ArtistAuthority(
            mbClient = MusicBrainzClient(httpGet = { """{ "count": 0, "artists": [] }""" }),
            deezerClient = DeezerClient(minIntervalMs = 0, httpGet = { """{ "data": [] }""" }),
            identities = MemoryIdentities(),
        )
        assertNull(empty.pin("Nobody At All"))
        assertTrue(!empty.isPinned("Nobody At All"))
    }
}
