package com.ikko.aura.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** A host that answers what it is told to, and counts how often it was reached. */
private class FakeHost {
    var calls = 0
    var answer: () -> Triple<Int, String, Map<String, String>> = { Triple(200, "{}", emptyMap()) }

    fun connect(url: URL): HttpURLConnection {
        calls++
        val (code, body, headers) = answer()
        return object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode(): Int = code
            override fun getInputStream(): InputStream = if (code < 400) ByteArrayInputStream(body.toByteArray()) else throw IOException("HTTP $code")
            override fun getErrorStream(): InputStream? = if (code >= 400) ByteArrayInputStream(body.toByteArray()) else null
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getHeaderField(name: String?): String? = headers[name]
        }
    }
}

class NetGuardTest {
    private val host = FakeHost()
    private var online = true
    private var clock = 0L
    private val url = "https://api.example.com/artist/1"

    @Before fun setUp() {
        NetGuard.reset()
        NetGuard.isOnline = { online }
        NetGuard.clock = { clock }
        NetGuard.connect = host::connect
        NetGuard.cache = DiskCache(Files.createTempDirectory("netguard").toFile())
    }

    @After fun tearDown() {
        NetGuard.reset()
        NetGuard.isOnline = { true }
        NetGuard.clock = { System.nanoTime() / 1_000_000L }
        NetGuard.connect = { it.openConnection() as HttpURLConnection }
        NetGuard.cache = null
    }

    private fun get(maxAgeMs: Long = 0L) = NetGuard.get(url, maxAgeMs)

    @Test fun `offline with nothing kept fails at once without touching the network`() {
        online = false
        try { get(); fail() } catch (e: OfflineException) { /* expected */ }
        assertEquals(0, host.calls)
    }

    @Test fun `offline, the last copy is served whatever its age`() {
        host.answer = { Triple(200, "kept", emptyMap()) }
        get()
        online = false
        val answer = get(maxAgeMs = 0L)
        assertEquals("kept", answer.body)
        assertTrue(answer.fromCache)
        assertEquals(1, host.calls)
    }

    @Test fun `a fresh copy is served without asking`() {
        get(maxAgeMs = 60_000)
        get(maxAgeMs = 60_000)
        get(maxAgeMs = 60_000)
        assertEquals(1, host.calls)
    }

    @Test fun `repeated server errors open the circuit, then nothing is sent`() {
        host.answer = { Triple(503, "busy", emptyMap()) }
        repeat(3) { assertEquals(503, get().code) }
        assertEquals(3, host.calls)
        try { get(); fail() } catch (e: CircuitOpenException) { assertEquals("api.example.com", e.host) }
        assertEquals(3, host.calls)
    }

    @Test fun `connection failures count too, and the stale copy covers for them`() {
        host.answer = { Triple(200, "old", emptyMap()) }
        get()
        host.answer = { throw IOException("unreachable") }
        repeat(5) { assertEquals("old", get().body) }
        // three failures reached the host; the next two were refused by the open circuit.
        assertEquals(1 + 3, host.calls)
    }

    @Test fun `a 429 opens the circuit for what Retry-After asks, then a probe closes it`() {
        host.answer = { Triple(429, "slow down", mapOf("Retry-After" to "20")) }
        assertEquals(429, get().code)
        try { get(); fail() } catch (e: CircuitOpenException) { assertEquals(20_000, e.retryInMs) }
        clock = 20_000
        host.answer = { Triple(200, "ok", emptyMap()) }
        assertEquals("ok", get().body)
        assertEquals(CircuitBreaker.State.CLOSED, NetGuard.breakerFor("api.example.com").state())
    }

    @Test fun `the network coming back lets a failing host be asked again`() {
        host.answer = { throw IOException("no route") }
        repeat(3) { runCatching { get() } }
        assertFalse(NetGuard.breakerFor("api.example.com").allowRequest())
        NetGuard.onNetworkRestored()
        host.answer = { Triple(200, "back", emptyMap()) }
        assertEquals("back", get().body)
    }

    @Test fun `an answer the caller rejects is not kept`() {
        host.answer = { Triple(200, """{"error":{"code":4}}""", emptyMap()) }
        NetGuard.get(url, maxAgeMs = 60_000, keep = { !it.contains("error") })
        NetGuard.get(url, maxAgeMs = 60_000, keep = { !it.contains("error") })
        assertEquals(2, host.calls)
    }

    @Test fun `a 404 is an answer, not a failure of the host`() {
        host.answer = { Triple(404, "none", emptyMap()) }
        repeat(10) { assertEquals(404, get().code) }
        assertEquals(10, host.calls)
    }

    @Test fun `exchange refuses offline and never connects`() {
        online = false
        try { NetGuard.exchange("https://ws.audioscrobbler.com/2.0/") { _, code -> code }; fail() } catch (e: OfflineException) { /* expected */ }
        assertEquals(0, host.calls)
    }

    @Test fun `Retry-After is read in seconds and capped`() {
        assertEquals(5_000L, NetGuard.retryAfterMs("5"))
        assertEquals(3_600_000L, NetGuard.retryAfterMs("999999"))
        assertEquals(null, NetGuard.retryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(null, NetGuard.retryAfterMs(null))
    }
}
