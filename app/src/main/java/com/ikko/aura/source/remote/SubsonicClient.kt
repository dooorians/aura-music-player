package com.ikko.aura.source.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Subsonic API (as Navidrome, Airsonic, Gonic and others speak it), over HTTP with token authentication: the
 * password is never sent, only `md5(password + salt)` and the salt. The salt is the source's own, fixed, so a song's
 * address never changes and its bytes can be kept.
 */
class SubsonicClient(
    baseUrl: String,
    private val username: String,
    password: String,
    private val salt: String,
) : RemoteClient {

    private val base = baseUrl.trim().trimEnd('/')
    private val token = md5(password + salt)

    private fun auth(): String =
        "u=${enc(username)}&t=$token&s=$salt&v=$API_VERSION&c=Aura&f=json"

    private fun url(endpoint: String, params: String = ""): String =
        "$base/rest/$endpoint.view?${auth()}${if (params.isEmpty()) "" else "&$params"}"

    private fun call(endpoint: String, params: String = ""): JSONObject {
        val connection = URL(url(endpoint, params)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Aura")
            val code = connection.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val response = JSONObject(body).getJSONObject("subsonic-response")
            if (response.optString("status") != "ok") {
                val error = response.optJSONObject("error")
                throw IOException(error?.optString("message").orEmpty().ifEmpty { "refused" })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    override fun ping() { call("ping") }

    override fun search(query: String): RemoteResults {
        val result = call("search3", "query=${enc(query)}&artistCount=8&albumCount=12&songCount=30").optJSONObject("searchResult3")
            ?: return RemoteResults(emptyList(), emptyList(), emptyList())
        return RemoteResults(
            songs = result.list("song").map(::song),
            albums = result.list("album").map(::album),
            artists = result.list("artist").map { RemoteArtist(it.optString("id"), it.optString("name"), it.optString("coverArt").ifEmpty { null }) },
        )
    }

    override fun album(id: String): RemoteAlbumPage {
        val node = call("getAlbum", "id=${enc(id)}").getJSONObject("album")
        return RemoteAlbumPage(album(node), node.list("song").map(::song).sortedWith(compareBy({ it.trackNumber.takeIf { n -> n > 0 } ?: Int.MAX_VALUE })))
    }

    override fun artistAlbums(name: String): List<RemoteAlbum> {
        val key = name.lowercase()
        val artist = search(name).artists.firstOrNull { it.name.lowercase() == key } ?: return emptyList()
        return call("getArtist", "id=${enc(artist.id)}").getJSONObject("artist").list("album").map(::album)
    }

    override fun streamUrl(songId: String): String = url("stream", "id=${enc(songId)}&format=raw&maxBitRate=0")

    override fun coverUrl(coverId: String?, px: Int): String? =
        coverId?.takeIf { it.isNotEmpty() }?.let { url("getCoverArt", "id=${enc(it)}&size=$px") }

    private fun song(o: JSONObject) = RemoteSong(
        id = o.optString("id"),
        title = o.optString("title"),
        artist = o.optString("artist"),
        album = o.optString("album"),
        albumId = o.optString("albumId").ifEmpty { null },
        durationMs = o.optLong("duration") * 1000,
        coverId = o.optString("coverArt").ifEmpty { null },
        suffix = o.optString("suffix").ifEmpty { null },
        contentType = o.optString("contentType").ifEmpty { null },
        trackNumber = o.optInt("track"),
        year = o.optInt("year"),
    )

    private fun album(o: JSONObject) = RemoteAlbum(
        id = o.optString("id"),
        title = o.optString("name").ifEmpty { o.optString("title") },
        artist = o.optString("artist"),
        coverId = o.optString("coverArt").ifEmpty { null },
        year = o.optInt("year"),
        songCount = o.optInt("songCount"),
    )

    private fun JSONObject.list(name: String): List<JSONObject> {
        val array: JSONArray = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private companion object {
        const val API_VERSION = "1.16.1"
        fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
        fun md5(value: String): String =
            MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
