package com.ikko.aura.reco.authority

import com.ikko.aura.data.deezer.DeezerClient
import com.ikko.aura.data.musicbrainz.MusicBrainzClient
import com.ikko.aura.reco.InMemoryDiscographyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscographyVerifierTest {

    private val ladyGaga = DisambiguatedArtist(
        canonicalKey = "lady gaga",
        canonicalName = "Lady Gaga",
        mbid = "650e7d30-d4a4-4cc5-a657-4af24ea4ac71",
        deezerId = 157L,
        country = "US",
        primaryTags = setOf("pop", "dance-pop"),
    )

    @Test
    fun testRejectsFakeAndMisattributedTracks() = runBlocking {
        val deezerClient = DeezerClient(httpGet = { url ->
            when {
                url.contains("artist/157/top") -> """
                {
                  "data": [
                    { "id": 1, "title": "Bad Romance", "title_short": "Bad Romance", "duration": 294, "artist": { "id": 157, "name": "Lady Gaga" } },
                    { "id": 2, "title": "Poker Face", "title_short": "Poker Face", "duration": 237, "artist": { "id": 157, "name": "Lady Gaga" } },
                    { "id": 3, "title": "Rain On Me (with Ariana Grande)", "title_short": "Rain On Me", "duration": 182, "artist": { "id": 157, "name": "Lady Gaga" } }
                  ]
                }
                """.trimIndent()
                url.contains("artist/157/albums") -> """
                {
                  "data": [
                    { "id": 102, "title": "Chromatica", "record_type": "album", "release_date": "2020-05-29" },
                    { "id": 103, "title": "Hold My Hand", "record_type": "single", "release_date": "2022-05-03" }
                  ]
                }
                """.trimIndent()
                url.contains("album/102/tracks") -> """
                {
                  "data": [
                    { "id": 11, "title": "Alice", "title_short": "Alice", "duration": 177, "artist": { "id": 157, "name": "Lady Gaga" } },
                    { "id": 12, "title": "Stupid Love", "title_short": "Stupid Love", "duration": 193, "artist": { "id": 157, "name": "Lady Gaga" } }
                  ]
                }
                """.trimIndent()
                url.contains("album/103/tracks") -> """
                {
                  "data": [
                    { "id": 13, "title": "Hold My Hand", "title_short": "Hold My Hand", "duration": 234, "artist": { "id": 157, "name": "Lady Gaga" } }
                  ]
                }
                """.trimIndent()
                else -> """{ "data": [] }"""
            }
        })

        val mbClient = MusicBrainzClient(httpGet = {
            """
            {
              "id": "650e7d30-d4a4-4cc5-a657-4af24ea4ac71",
              "release-groups": [
                { "id": "rg1", "title": "The Fame", "primary-type": "Album" },
                { "id": "rg2", "title": "Hold My Hand", "primary-type": "Single" }
              ]
            }
            """.trimIndent()
        })

        val verifier = DiscographyVerifier(deezerClient, mbClient, InMemoryDiscographyStore())

        // Official tracks MUST be accepted
        assertTrue(verifier.isOfficialTrack(ladyGaga, "Bad Romance"))
        assertTrue(verifier.isOfficialTrack(ladyGaga, "Poker Face"))
        assertTrue(verifier.isOfficialTrack(ladyGaga, "Rain On Me"))
        assertTrue(verifier.isOfficialTrack(ladyGaga, "Alice"))
        assertTrue(verifier.isOfficialTrack(ladyGaga, "Hold My Hand"))

        // Hallucinated / Editorial / Mal-attributed tracks MUST be rejected
        assertFalse(verifier.isOfficialTrack(ladyGaga, "Girlboss energy"))
        assertFalse(verifier.isOfficialTrack(ladyGaga, "OG Bichotas"))
        assertFalse(verifier.isOfficialTrack(ladyGaga, "Chill Lo-Fi Afternoon Beats"))
        assertFalse(verifier.isOfficialTrack(ladyGaga, "Lady Gaga Unreleased Leak Studio Session"))

        // Official albums MUST be accepted
        assertTrue(verifier.isOfficialAlbum(ladyGaga, "Chromatica"))
        assertTrue(verifier.isOfficialAlbum(ladyGaga, "The Fame"))
        assertTrue(verifier.isOfficialAlbum(ladyGaga, "Hold My Hand"))

        // Compilations / Playlists / Non-official releases MUST be rejected
        assertFalse(verifier.isOfficialAlbum(ladyGaga, "OG Bichotas"))
        assertFalse(verifier.isOfficialAlbum(ladyGaga, "Girlboss Energy"))
        assertFalse(verifier.isOfficialAlbum(ladyGaga, "Summer Party Hits 2024"))

        // Pair validation [track + album]
        assertTrue(verifier.isOfficialTrackAndRelease(ladyGaga, "Alice", "Chromatica"))
        assertTrue(verifier.isOfficialTrackAndRelease(ladyGaga, "Hold My Hand", "Hold My Hand"))
        // Track might be real, but from a third-party compilation -> MUST BE REJECTED
        assertFalse(verifier.isOfficialTrackAndRelease(ladyGaga, "Alice", "OG Bichotas"))
        assertFalse(verifier.isOfficialTrackAndRelease(ladyGaga, "Bad Romance", "Girlboss Energy"))
        // Fake track on real album -> REJECTED
        assertFalse(verifier.isOfficialTrackAndRelease(ladyGaga, "Girlboss energy", "Chromatica"))

        // Filtering a raw list
        val candidateTitles = listOf(
            "Bad Romance",
            "Girlboss energy",
            "Rain On Me",
            "OG Bichotas",
            "Alice",
        )
        val filtered = verifier.filterOfficialTracks(ladyGaga, candidateTitles)
        assertEquals(listOf("Bad Romance", "Rain On Me", "Alice"), filtered)
    }

    @Test
    fun testPersistsAcrossInstances() = runBlocking {
        val deezerClient = DeezerClient(httpGet = { url ->
            if (url.contains("artist/157/top")) """
            {
              "data": [ { "id": 1, "title": "Bad Romance", "title_short": "Bad Romance", "duration": 294, "artist": { "id": 157, "name": "Lady Gaga" } } ]
            }
            """.trimIndent()
            else """{ "data": [] }"""
        })
        val mbClient = MusicBrainzClient(httpGet = { """{ "id": "x", "release-groups": [], "recordings": [] }""" })
        val store = InMemoryDiscographyStore()

        val first = DiscographyVerifier(deezerClient, mbClient, store)
        assertTrue(first.isOfficialTrack(ladyGaga, "Bad Romance"))

        // A second verifier, built with the same store but a now-deaf source, still knows the title: it read the store.
        val deafDeezer = DeezerClient(httpGet = { """{ "data": [] }""" })
        val second = DiscographyVerifier(deafDeezer, mbClient, store)
        assertTrue(second.isOfficialTrack(ladyGaga, "Bad Romance"))
    }

    @Test
    fun testReadsTheTrackBpm() = runBlocking {
        val deezerClient = DeezerClient(httpGet = { url ->
            if (url.contains("artist/157/top")) """
            {
              "data": [ { "id": 1, "title": "Bad Romance", "title_short": "Bad Romance", "duration": 294, "bpm": 119, "artist": { "id": 157, "name": "Lady Gaga" } } ]
            }
            """.trimIndent() else """{ "data": [] }"""
        })
        val mbClient = MusicBrainzClient(httpGet = { """{ "id": "x", "release-groups": [], "recordings": [] }""" })
        val verifier = DiscographyVerifier(deezerClient, mbClient, InMemoryDiscographyStore())
        assertEquals(119.0, verifier.bpmOf(ladyGaga, "Bad Romance")!!, 1e-9)
    }
}
