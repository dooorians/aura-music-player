package com.ikko.aura.data

import com.ikko.aura.data.lyrics.LyricLine
import com.ikko.aura.data.lyrics.LyricsSource
import com.ikko.aura.data.lyrics.LyricsUnreachable
import com.ikko.aura.data.lyrics.OnlineLyrics
import com.ikko.aura.data.lyrics.lyricsGet
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsNegativeCacheTest {

    private var server: HttpServer? = null

    @After
    fun stop() { server?.stop(0) }

    private fun serve(status: Int, body: String = ""): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/lyrics"
    }

    // --- the transport: only a 404 is the host saying "none"

    @Test
    fun `a 404 is a confirmed absence`() {
        assertNull(lyricsGet(serve(404)))
    }

    @Test
    fun `a 200 returns its body`() {
        assertEquals("{\"ok\":1}", lyricsGet(serve(200, "{\"ok\":1}")))
    }

    @Test
    fun `a server error is unreachable, not absent`() {
        assertThrows(LyricsUnreachable::class.java) { lyricsGet(serve(500)) }
        assertThrows(LyricsUnreachable::class.java) { lyricsGet(serve(503)) }
    }

    @Test
    fun `a refusal or a rate limit is unreachable, not absent`() {
        assertThrows(LyricsUnreachable::class.java) { lyricsGet(serve(403)) }
        assertThrows(LyricsUnreachable::class.java) { lyricsGet(serve(429)) }
    }

    @Test
    fun `no network (connection refused) is an IOException turned unreachable`() {
        val url = serve(200).also { server?.stop(0) } // the port is now closed
        val e = assertThrows(LyricsUnreachable::class.java) { lyricsGet(url) }
        assertTrue(e is java.io.IOException)
    }

    // --- the verdict, and what may be cached

    private val someLines = listOf(LyricLine(timeMs = 0, text = "la"))

    @Test
    fun `every source answering none is a confirmed miss`() {
        assertEquals(OnlineLyrics.Lookup.NotFound, OnlineLyrics.verdict(null, anySourceFailed = false))
    }

    @Test
    fun `a source that could not answer keeps the question open`() {
        assertEquals(OnlineLyrics.Lookup.Unreachable, OnlineLyrics.verdict(null, anySourceFailed = true))
    }

    @Test
    fun `lyrics found win even if another source failed`() {
        val found = OnlineLyrics.Result(LyricsSource.LRCLIB, someLines)
        assertEquals(OnlineLyrics.Lookup.Found(found), OnlineLyrics.verdict(found, anySourceFailed = true))
    }

    @Test
    fun `an unreachable lookup never writes a cache row`() {
        assertNull(cacheEntryFor("42", OnlineLyrics.Lookup.Unreachable, nowMs = 1_000))
    }

    @Test
    fun `a confirmed miss is remembered as an empty row`() {
        val row = cacheEntryFor("42", OnlineLyrics.Lookup.NotFound, nowMs = 1_000)
        assertNotNull(row)
        assertEquals("", row!!.lrc)
        assertEquals(1_000L, row.fetchedAtMs)
    }

    @Test
    fun `found lyrics are stored with their source`() {
        val row = cacheEntryFor("42", OnlineLyrics.Lookup.Found(OnlineLyrics.Result(LyricsSource.LRCLIB, someLines)), nowMs = 5)
        assertEquals("LRCLIB", row!!.source)
        assertTrue(row.lrc.isNotEmpty())
    }
}
