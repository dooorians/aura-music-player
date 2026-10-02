package com.ikko.aura.data.lyrics

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Shared plumbing for the lyric providers.
 *
 * They are raced against each other by [OnlineLyrics], so a provider that hangs holds
 * up the whole lookup. The deadlines here are deliberately tight: a lyric that arrives
 * after the second chorus is of no use to anyone, and the fallbacks behind it are the
 * better answer.
 *
 * `HttpURLConnection` rather than okhttp — three GETs against three hosts is not worth
 * a dependency, and the platform client already pools connections.
 */
private const val CONNECT_TIMEOUT_MS = 4_000
private const val READ_TIMEOUT_MS = 6_000
private const val MAX_BODY_BYTES = 2 * 1024 * 1024

private const val AGENT = "Aura/1.0 (local music player; https://github.com/ikko/aura)"

/**
 * A request that got no definitive answer: no network, a timeout, a server error, an answer that is not lyrics.
 * Not the same as the host saying it has none — that is a null from [lyricsGet] — and the difference decides whether a
 * miss may be remembered.
 */
class LyricsUnreachable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Body of a successful GET; null when the host says it has no such lyrics (HTTP 404); [LyricsUnreachable] for anything
 * else — a network error, a timeout, a 5xx, a refusal (403, 429…), an oversized or unreadable reply.
 */
internal fun lyricsGet(url: String): String? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        val code = connection.responseCode
        if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
        if (code !in 200..299) throw LyricsUnreachable("HTTP $code")
        // Capped rather than read whole: these are third-party hosts and a lyric
        // document is a few kilobytes, so anything at this size is a mistake or a
        // redirect into something that isn't lyrics at all.
        connection.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(8 * 1024)
            val out = StringBuilder()
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                out.append(buffer, 0, read)
                if (out.length > MAX_BODY_BYTES) throw LyricsUnreachable("reply too large")
            }
            out.toString()
        }
    } catch (e: LyricsUnreachable) {
        throw e
    } catch (e: IOException) {
        throw LyricsUnreachable("request failed", e)
    } catch (e: SecurityException) {
        throw LyricsUnreachable("request refused", e)
    } finally {
        connection?.disconnect()
    }
}

/** Query-string encoding: `URLEncoder` writes `+` for a space, which these APIs read literally. */
internal fun String.asQueryValue(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
