package com.ikko.aura.data.deezer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeezerClientTest {

    private val sampleArtistSearchJson = """
    {
      "data": [
        {
          "id": 14229385,
          "name": "YENA",
          "picture_xl": "https://api.deezer.com/artist/14229385/image",
          "nb_fan": 125000,
          "tracklist": "https://api.deezer.com/artist/14229385/top?limit=50"
        },
        {
          "id": 998877,
          "name": "Yena",
          "picture_xl": "",
          "nb_fan": 420,
          "tracklist": ""
        }
      ]
    }
    """.trimIndent()

    private val sampleAlbumsJson = """
    {
      "data": [
        {
          "id": 34891234,
          "title": "SMARTPHONE",
          "record_type": "ep",
          "release_date": "2022-08-03"
        },
        {
          "id": 31234567,
          "title": "SMILEY",
          "record_type": "ep",
          "release_date": "2022-01-17"
        },
        {
          "id": 45678901,
          "title": "Hate Rodrigo",
          "record_type": "single",
          "release_date": "2023-06-27"
        }
      ]
    }
    """.trimIndent()

    private val sampleTopTracksJson = """
    {
      "data": [
        {
          "id": 18239012,
          "title": "SMILEY (Feat. BIBI)",
          "title_short": "SMILEY",
          "duration": 180,
          "artist": {
            "id": 14229385,
            "name": "YENA"
          },
          "album": {
            "id": 31234567,
            "title": "SMILEY"
          }
        },
        {
          "id": 18239015,
          "title": "SMARTPHONE",
          "title_short": "SMARTPHONE",
          "duration": 190,
          "artist": {
            "id": 14229385,
            "name": "YENA"
          },
          "album": {
            "id": 34891234,
            "title": "SMARTPHONE"
          }
        }
      ]
    }
    """.trimIndent()

    private val sampleRelatedArtistsJson = """
    {
      "data": [
        {
          "id": 123456,
          "name": "Kwon Eunbi",
          "nb_fan": 98000,
          "picture_xl": "https://eunbi.jpg"
        },
        {
          "id": 789012,
          "name": "Jo Yu-ri",
          "nb_fan": 95000,
          "picture_xl": "https://yuri.jpg"
        }
      ]
    }
    """.trimIndent()

    @Test
    fun testSearchArtist() = runBlocking {
        val client = DeezerClient(httpGet = { url ->
            assertTrue(url.contains("search/artist"))
            sampleArtistSearchJson
        })

        val artists = client.searchArtist("YENA")
        assertEquals(2, artists.size)
        assertEquals(14229385L, artists[0].id)
        assertEquals("YENA", artists[0].name)
        assertEquals(125000, artists[0].nbFan)
        assertEquals("https://api.deezer.com/artist/14229385/image", artists[0].pictureXl)
    }

    @Test
    fun testGetArtistAlbums() = runBlocking {
        val client = DeezerClient(httpGet = { url ->
            assertTrue(url.contains("artist/14229385/albums"))
            sampleAlbumsJson
        })

        val albums = client.getArtistAlbums(14229385L)
        assertEquals(3, albums.size)
        assertEquals(34891234L, albums[0].id)
        assertEquals("SMARTPHONE", albums[0].title)
        assertEquals("ep", albums[0].recordType)
        assertEquals("2022-08-03", albums[0].releaseDate)
    }

    @Test
    fun testGetArtistTopTracks() = runBlocking {
        val client = DeezerClient(httpGet = { url ->
            assertTrue(url.contains("artist/14229385/top"))
            sampleTopTracksJson
        })

        val tracks = client.getArtistTopTracks(14229385L)
        assertEquals(2, tracks.size)
        assertEquals(18239012L, tracks[0].id)
        assertEquals("SMILEY (Feat. BIBI)", tracks[0].title)
        assertEquals("SMILEY", tracks[0].titleShort)
        assertEquals(14229385L, tracks[0].artistId)
        assertEquals("YENA", tracks[0].artistName)
    }

    @Test
    fun testGetRelatedArtists() = runBlocking {
        val client = DeezerClient(httpGet = { url ->
            assertTrue(url.contains("artist/14229385/related"))
            sampleRelatedArtistsJson
        })

        val related = client.getRelatedArtists(14229385L)
        assertEquals(2, related.size)
        assertEquals("Kwon Eunbi", related[0].name)
        assertEquals(123456L, related[0].id)
        assertEquals("Jo Yu-ri", related[1].name)
        assertEquals(789012L, related[1].id)
    }

    @Test
    fun testGetArtistDirect() = runBlocking {
        val client = DeezerClient(httpGet = { url ->
            assertTrue(url.contains("artist/1042268"))
            """
            {
              "id": 1042268,
              "name": "Angèle",
              "picture_xl": "https://api.deezer.com/artist/1042268/image",
              "nb_fan": 1750000,
              "tracklist": "https://api.deezer.com/artist/1042268/top"
            }
            """.trimIndent()
        })

        val artist = client.getArtist(1042268L)
        assertNotNull(artist)
        assertEquals(1042268L, artist?.id)
        assertEquals("Angèle", artist?.name)
        assertEquals("https://api.deezer.com/artist/1042268/image", artist?.pictureXl)
        assertEquals(1750000, artist?.nbFan)
    }

    @Test
    fun aQuotaErrorIsWaitedOutAndNeverTakenForAnEmptyAnswer() = runBlocking {
        var calls = 0
        val client = DeezerClient(
            httpGet = { _ ->
                calls++
                if (calls < 3) """{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}""" else sampleArtistSearchJson
            },
            minIntervalMs = 0, retryDelayMs = 1,
        )
        val artists = client.searchArtist("YENA")
        assertEquals(3, calls)
        assertEquals(2, artists.size)
    }

    @Test
    fun aQuotaErrorThatLastsIsNotCached() = runBlocking {
        var quota = true
        var calls = 0
        val client = DeezerClient(
            httpGet = { _ -> calls++; if (quota) """{"error":{"code":4,"message":"Quota limit exceeded"}}""" else sampleArtistSearchJson },
            minIntervalMs = 0, retryDelayMs = 1,
        )
        assertEquals(0, client.searchArtist("YENA").size)
        quota = false
        // The failure was not kept: the next ask goes to the network again and finds the artists.
        assertEquals(2, client.searchArtist("YENA").size)
    }

    @Test
    fun aGenreChartGivesAlbumsWithCoversAndNoCompilation() = runBlocking {
        val client = DeezerClient(httpGet = { url ->
            assertTrue(url.contains("chart/152/albums"))
            """{ "data": [
                { "id": 1, "title": "Nevermind", "record_type": "album", "cover_xl": "https://c/1", "artist": { "name": "Nirvana" } },
                { "id": 2, "title": "Now 99", "record_type": "compile", "cover_xl": "https://c/2", "artist": { "name": "Various" } },
                { "id": 3, "title": "No cover", "record_type": "album", "cover_xl": "", "artist": { "name": "Someone" } }
            ] }"""
        }, minIntervalMs = 0, retryDelayMs = 1)
        val albums = client.chartAlbums(152)
        assertEquals(listOf("Nevermind"), albums.map { it.title })
        assertEquals("Nirvana", albums.single().artist)
    }
}
