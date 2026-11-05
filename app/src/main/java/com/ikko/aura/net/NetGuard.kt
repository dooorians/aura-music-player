package com.ikko.aura.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** No network at all (airplane mode, out of range): the request was not even tried, and nothing was cached for it. */
class OfflineException(message: String = "offline") : IOException(message)

/** The host failed too often lately and is left alone for [retryInMs]; nothing was sent. */
class CircuitOpenException(val host: String, val retryInMs: Long) : IOException("circuit open for $host ($retryInMs ms)")

/** An answer to a guarded GET: [fromCache] when no network was used to give it. */
class CachedResponse(val code: Int, val body: String, val fromCache: Boolean)

/**
 * The one gate every metadata request goes through (Last.fm, lyrics, Deezer, MusicBrainz, artist photos).
 *
 * - **Offline**, nothing is sent: the radio is not woken, no socket waits out its timeout. A GET is answered from [cache]
 *   however old the copy, or fails at once with [OfflineException].
 * - **A failing host** trips its [CircuitBreaker] (three failures in a row, or one 429): further requests fail at once with
 *   [CircuitOpenException] — or get the last copy — until its cool-down, which doubles at each new trip, has passed.
 * - **A fresh copy** (younger than the caller's `maxAgeMs`) is returned without touching the network at all, which is what
 *   makes browsing the library instant and free on data.
 *
 * A process-wide object rather than an injected class: the clients it guards are built in many ways (some by hand in
 * tests), and a breaker is only of use if all of them share it.
 */
object NetGuard {
    /** Whether the device has a network now. [NetworkMonitor] sets it; it stays "online" where nothing does (unit tests). */
    @Volatile var isOnline: () -> Boolean = { true }

    /** Where GET bodies are kept; null (nothing kept) until the application installs one. */
    @Volatile var cache: DiskCache? = null

    @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000L }

    @Volatile var connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }

    private val breakers = ConcurrentHashMap<String, CircuitBreaker>()

    fun breakerFor(host: String): CircuitBreaker = breakers.getOrPut(host) { CircuitBreaker(clock = { clock() }) }

    /** The network is back: every host that only failed for want of one may be asked again. */
    fun onNetworkRestored() = breakers.values.forEach { it.onNetworkRestored() }

    /** Forgets every breaker (tests, and a change of the injected clock). */
    fun reset() = breakers.clear()

    /**
     * A request through the breaker, never cached: POSTs (scrobbles, sign-in) and downloads. [setup] configures the connection
     * (and writes a body); [read] gets it with its status code once the answer is in. A 429 or a 5xx counts against the host,
     * as does a request that could not connect or was cut off; any other answer counts for it.
     */
    fun <T> exchange(url: String, setup: (HttpURLConnection) -> Unit = {}, read: (HttpURLConnection, Int) -> T): T {
        val target = URL(url)
        if (!isOnline()) throw OfflineException()
        val breaker = breakerFor(target.host)
        if (!breaker.allowRequest()) throw CircuitOpenException(target.host, breaker.remainingCooldownMs())
        var connection: HttpURLConnection? = null
        try {
            val code = try {
                connection = connect(target)
                setup(connection)
                connection.responseCode
            } catch (e: IOException) {
                breaker.onFailure()
                throw e
            }
            record(breaker, connection, code)
            return try {
                read(connection, code)
            } catch (e: IOException) {
                // Cut off half-way through the body: the host (or the path to it) is not answering well.
                breaker.onFailure()
                throw e
            }
        } finally {
            connection?.disconnect()
        }
    }

    /** The kept body of [url] if it is younger than [maxAgeMs]: what a client checks before it waits its turn to ask. */
    fun fresh(url: String, maxAgeMs: Long): String? =
        cache?.get(url)?.takeIf { System.currentTimeMillis() - it.savedAt in 0 until maxAgeMs }?.body

    /**
     * A GET whose body is worth keeping. A copy younger than [maxAgeMs] is returned without asking; otherwise the host is
     * asked, and a 2xx body that [keep] accepts is stored. When the host cannot be asked — offline, breaker open, no answer —
     * the last copy is returned whatever its age, and only without one does the failure reach the caller.
     */
    fun get(
        url: String,
        maxAgeMs: Long,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 10_000,
        maxBodyChars: Int = 4 * 1024 * 1024,
        keep: (String) -> Boolean = { true },
    ): CachedResponse {
        val store = cache
        val copy = store?.get(url)
        if (copy != null && System.currentTimeMillis() - copy.savedAt in 0 until maxAgeMs) {
            return CachedResponse(200, copy.body, fromCache = true)
        }
        return try {
            exchange(
                url,
                setup = { c ->
                    c.requestMethod = "GET"
                    c.connectTimeout = connectTimeoutMs
                    c.readTimeout = readTimeoutMs
                    c.instanceFollowRedirects = true
                    // Bodies are kept here, by this class: the platform's own cache would hold audio streams as well.
                    c.useCaches = false
                    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                },
                read = { c, code ->
                    val stream = if (code in 200..299) c.inputStream else c.errorStream
                    val body = stream?.bufferedReader()?.use { readCapped(it, maxBodyChars) }.orEmpty()
                    CachedResponse(code, body, fromCache = false)
                },
            ).also { if (it.code in 200..299 && keep(it.body)) store?.put(url, it.body) }
        } catch (e: IOException) {
            copy?.let { CachedResponse(200, it.body, fromCache = true) } ?: throw e
        }
    }

    private fun readCapped(reader: java.io.Reader, max: Int): String {
        val out = StringBuilder()
        val buffer = CharArray(8 * 1024)
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            out.append(buffer, 0, n)
            if (out.length > max) throw IOException("reply too large")
        }
        return out.toString()
    }

    private fun record(breaker: CircuitBreaker, connection: HttpURLConnection, code: Int) {
        when {
            code == 429 -> breaker.onFailure(rateLimited = true, retryAfterMs = retryAfterMs(connection.getHeaderField("Retry-After")))
            code >= 500 -> breaker.onFailure()
            else -> breaker.onSuccess()
        }
    }

    /** `Retry-After` in seconds (the form these APIs use); an HTTP date or nonsense is ignored. Capped at an hour. */
    internal fun retryAfterMs(value: String?): Long? =
        value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(3600)?.times(1000)
}
