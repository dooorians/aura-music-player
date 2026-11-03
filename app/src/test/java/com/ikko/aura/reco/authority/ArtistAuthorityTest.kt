package com.ikko.aura.reco.authority

import com.ikko.aura.data.deezer.DeezerArtistProfile
import com.ikko.aura.data.deezer.DeezerClient
import com.ikko.aura.data.musicbrainz.MusicBrainzArtist
import com.ikko.aura.data.musicbrainz.MusicBrainzClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistAuthorityTest {

    private val mbYenaKpop = MusicBrainzArtist(
        mbid = "6a9e8790-205e-40be-9b37-250325d70e6a",
        name = "YENA",
        country = "KR",
        disambiguation = "Choi Ye-na, South Korean singer and former member of IZ*ONE",
        tags = listOf("k-pop" to 15, "dance-pop" to 8),
        aliases = listOf("Choi Ye-na", "최예나"),
        score = 100,
    )

    private val mbYenaThai = MusicBrainzArtist(
        mbid = "e8d6411e-28b3-4f9e-a89c-51ff6df91234",
        name = "Yena",
        country = "TH",
        disambiguation = "Thai folk/indie band",
        tags = listOf("indie folk" to 5, "thai" to 3),
        aliases = emptyList(),
        score = 85,
    )

    private val deezerYenaKpop = DeezerArtistProfile(
        id = 14229385L,
        name = "YENA",
        nbFan = 125000,
        pictureXl = "https://e-cdns-images.dzcdn.net/images/artist/14229385/1000x1000.jpg",
    )

    private val deezerYenaThai = DeezerArtistProfile(
        id = 998877L,
        name = "Yena",
        nbFan = 350,
        pictureXl = null,
    )

    @Test
    fun testDisambiguateYenaKpopByTagsAndCountry() = runBlocking {
        val mbClient = MusicBrainzClient(httpGet = {
            """
            {
              "count": 2,
              "artists": [
                {
                  "id": "6a9e8790-205e-40be-9b37-250325d70e6a",
                  "name": "YENA",
                  "country": "KR",
                  "disambiguation": "Choi Ye-na, South Korean singer and former member of IZ*ONE",
                  "score": 100,
                  "tags": [ { "count": 15, "name": "k-pop" } ],
                  "aliases": [ { "name": "Choi Ye-na" } ]
                },
                {
                  "id": "e8d6411e-28b3-4f9e-a89c-51ff6df91234",
                  "name": "Yena",
                  "country": "TH",
                  "disambiguation": "Thai folk/indie band",
                  "score": 85,
                  "tags": [ { "count": 5, "name": "indie folk" } ],
                  "aliases": []
                }
              ]
            }
            """.trimIndent()
        })

        val deezerClient = DeezerClient(httpGet = {
            """
            {
              "data": [
                { "id": 14229385, "name": "YENA", "nb_fan": 125000, "picture_xl": "https://yena.jpg" },
                { "id": 998877, "name": "Yena", "nb_fan": 350, "picture_xl": "" }
              ]
            }
            """.trimIndent()
        })

        val authority = ArtistAuthority(
            mbClient = mbClient,
            deezerClient = deezerClient,
            lastFmTagsProvider = { name ->
                if (name.equals("YENA", ignoreCase = true)) listOf("k-pop", "dance-pop", "korean") else emptyList()
            },
            lastFmMbidProvider = { _ -> null },
        )

        val resolved = authority.resolve("YENA", listenerContextTags = setOf("k-pop", "pop"))
        assertNotNull(resolved)
        assertEquals("6a9e8790-205e-40be-9b37-250325d70e6a", resolved!!.mbid)
        assertEquals(14229385L, resolved.deezerId)
        assertEquals("KR", resolved.country)
        assertTrue(resolved.primaryTags.contains("k-pop"))
        assertTrue(resolved.disambiguation?.contains("IZ*ONE") == true)
    }

    @Test
    fun testDisambiguateWithLastFmMbidExactMatch() = runBlocking {
        val mbClient = MusicBrainzClient(httpGet = {
            """
            {
              "count": 1,
              "artists": [
                {
                  "id": "3bb6ab0b-e448-4e14-be74-4b47eb689e4d",
                  "name": "Troye Sivan",
                  "country": "AU",
                  "score": 100,
                  "tags": [ { "count": 20, "name": "pop" } ]
                }
              ]
            }
            """.trimIndent()
        })

        val deezerClient = DeezerClient(httpGet = {
            """
            {
              "data": [
                { "id": 4518789, "name": "Troye Sivan", "nb_fan": 2000000, "picture_xl": "https://troye.jpg" }
              ]
            }
            """.trimIndent()
        })

        val authority = ArtistAuthority(
            mbClient = mbClient,
            deezerClient = deezerClient,
            lastFmTagsProvider = { listOf("pop", "indie pop") },
            lastFmMbidProvider = { "3bb6ab0b-e448-4e14-be74-4b47eb689e4d" },
        )

        val resolved = authority.resolve("Troye Sivan")
        assertNotNull(resolved)
        assertEquals("3bb6ab0b-e448-4e14-be74-4b47eb689e4d", resolved!!.mbid)
        assertEquals(4518789L, resolved.deezerId)
        assertEquals("Troye Sivan", resolved.canonicalName)
    }
}
