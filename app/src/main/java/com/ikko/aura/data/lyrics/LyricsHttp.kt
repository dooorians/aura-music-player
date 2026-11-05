package com.ikko.aura.data.lyrics

import java.io.IOException
import java.net.HttpURLConnection
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
    val answer = try {
        // Kept a month: a song's words do not change, and a library browsed again (or in airplane mode) finds them at once.
        com.ikko.aura.net.NetGuard.get(
            url,
            maxAgeMs = LYRICS_MAX_AGE_MS,
            headers = mapOf("User-Agent" to AGENT, "Accept" to "application/json, text/plain, */*"),
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            maxBodyChars = MAX_BODY_BYTES,
        )
    } catch (e: IOException) {
        // Offline with nothing kept, a host left alone after repeated failures, a timeout, an oversized reply.
        throw LyricsUnreachable("request failed", e)
    } catch (e: SecurityException) {
        throw LyricsUnreachable("request refused", e)
    }
    if (answer.code == HttpURLConnection.HTTP_NOT_FOUND) return null
    if (answer.code !in 200..299) throw LyricsUnreachable("HTTP ${answer.code}")
    return answer.body
}

private const val LYRICS_MAX_AGE_MS = 30L * 24 * 3600 * 1000

/** Query-string encoding: `URLEncoder` writes `+` for a space, which these APIs read literally. */
internal fun String.asQueryValue(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
