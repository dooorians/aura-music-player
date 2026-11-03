package com.ikko.aura.data.musicbrainz

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzClientTest {

    private val sampleArtistSearchJson = """
    {
      "count": 2,
      "artists": [
        {
          "id": "6a9e8790-205e-40be-9b37-250325d70e6a",
          "name": "YENA",
          "sort-name": "YENA",
          "country": "KR",
          "disambiguation": "Choi Ye-na, South Korean singer and former member of IZ*ONE",
          "score": 100,
          "tags": [
            { "count": 15, "name": "k-pop" },
            { "count": 8, "name": "dance-pop" }
          ],
          "aliases": [
            { "name": "Choi Ye-na" },
            { "name": "최예na" }
          ]
        },
        {
          "id": "e8d6411e-28b3-4f9e-a89c-51ff6df91234",
          "name": "Yena",
          "country": "TH",
          "disambiguation": "Thai folk/indie band",
          "score": 85,
          "tags": [
            { "count": 4, "name": "indie folk" }
          ],
          "aliases": []
        }
      ]
    }
    """.trimIndent()

    private val sampleReleaseGroupsJson = """
    {
      "id": "6a9e8790-205e-40be-9b37-250325d70e6a",
      "name": "YENA",
      "release-groups": [
        {
          "id": "d13a9688-6617-4886-9a29-07498a4d4ea0",
          "title": "SMARTPHONE",
          "primary-type": "EP",
          "first-release-date": "2022-08-03"
        },
        {
          "id": "b8ef7988-8255-4422-9ee6-f5548681ca01",
          "title": "SMILEY",
          "primary-type": "EP",
          "first-release-date": "2022-01-17"
        },
        {
          "id": "c1111111-2222-3333-4444-555555555555",
          "title": "Hate Rodrigo",
          "primary-type": "Single",
          "first-release-date": "2023-06-27"
        }
      ]
    }
    """.trimIndent()

    @Test
    fun testParseArtistSearch() = runBlocking {
        val client = MusicBrainzClient(httpGet = { url ->
            assertTrue(url.contains("query=artist"))
            sampleArtistSearchJson
        })

        val artists = client.searchArtist("YENA")
        assertEquals(2, artists.size)

        val yenaKpop = artists[0]
        assertEquals("6a9e8790-205e-40be-9b37-250325d70e6a", yenaKpop.mbid)
        assertEquals("YENA", yenaKpop.name)
        assertEquals("KR", yenaKpop.country)
        assertTrue(yenaKpop.disambiguation?.contains("IZ*ONE") == true)
        assertTrue(yenaKpop.tags.any { it.first == "k-pop" })
        assertTrue(yenaKpop.aliases.contains("Choi Ye-na"))

        val yenaThai = artists[1]
        assertEquals("e8d6411e-28b3-4f9e-a89c-51ff6df91234", yenaThai.mbid)
        assertEquals("TH", yenaThai.country)
        assertTrue(yenaThai.tags.any { it.first == "indie folk" })
    }

    @Test
    fun testParseReleaseGroups() = runBlocking {
        val client = MusicBrainzClient(httpGet = { url ->
            assertTrue(url.contains("6a9e8790-205e-40be-9b37-250325d70e6a"))
            sampleReleaseGroupsJson
        })

        val releaseGroups = client.getArtistReleaseGroups("6a9e8790-205e-40be-9b37-250325d70e6a")
        assertEquals(3, releaseGroups.size)
        assertEquals("SMARTPHONE", releaseGroups[0].title)
        assertEquals("EP", releaseGroups[0].primaryType)
        assertEquals("2022-08-03", releaseGroups[0].firstReleaseDate)
    }
}
