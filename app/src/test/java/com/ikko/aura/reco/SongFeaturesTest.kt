package com.ikko.aura.reco

import com.ikko.aura.data.deezer.DeezerClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongFeaturesTest {
    private fun client(requests: MutableList<String> = ArrayList()) = DeezerClient(httpGet = { url ->
        requests += url
        when {
            url.contains("search/track") -> """
                { "data": [
                  { "id": 11, "title": "All Night", "title_short": "All Night", "artist": { "id": 1, "name": "Some Cover Band" } },
                  { "id": 22, "title": "All Night", "title_short": "All Night", "artist": { "id": 2, "name": "IVE" } }
                ] }
            """.trimIndent()
            url.endsWith("/track/22") -> """{ "id": 22, "bpm": 0, "gain": -6.7, "preview": "https://p/22.mp3" }"""
            else -> """{ "data": [] }"""
        }
    }, minIntervalMs = 0L)

    @Test fun `the song is the result with the same title by the same artist, and its page gives the tempo and the loudness`() = runBlocking {
        val f = client().trackFeatures("All Night", "IVE")
        assertNotNull(f)
        assertEquals(-6.7, f!!.gainDb!!, 1e-9)
        assertEquals(0.0, f.bpm, 0.0)
        assertEquals("https://p/22.mp3", f.previewUrl)
    }

    @Test fun `a song nobody has is none, and asked once`() = runBlocking {
        val requests = ArrayList<String>()
        val c = client(requests)
        assertNull(c.trackFeatures("Something Else Entirely", "IVE"))
        val after = requests.size
        assertNull(c.trackFeatures("Something Else Entirely", "IVE"))
        assertEquals(after, requests.size)
    }

    @Test fun `a song is looked up once`() = runBlocking {
        val requests = ArrayList<String>()
        val c = client(requests)
        c.trackFeatures("All Night", "IVE")
        val after = requests.size
        c.trackFeatures("All Night", "IVE")
        assertEquals(after, requests.size)
    }

    @Test fun `a playlist needs ten songs and a new-releases one five, three titles are not a playlist`() {
        RecoKind.entries.forEach { kind ->
            val needed = Ranker.specOf(kind).acceptable
            assertTrue("${kind.key} accepts $needed", needed >= if (kind == RecoKind.NEW) 5 else 10)
        }
    }
}
