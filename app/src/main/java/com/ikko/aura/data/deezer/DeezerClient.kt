package com.ikko.aura.data.deezer

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class DeezerArtistProfile(
    val id: Long,
    val name: String,
    val nbFan: Int = 0,
    val pictureXl: String? = null,
    val tracklistUrl: String? = null,
)

data class DeezerAlbumInfo(
    val id: Long,
    val title: String,
    val recordType: String = "album", // album, single, ep, compile
    val releaseDate: String? = null,
)

/** An album, EP or single of an artist: its type ("album", "ep", "single"), its date ("2024-05-03") and its cover. */
data class DeezerRelease(val id: Long, val title: String, val type: String, val releaseDate: String, val cover: String)

/** An album of a genre's chart, with its cover. */
data class DeezerChartAlbum(val id: Long, val title: String, val artist: String, val coverXl: String)

data class DeezerTrackInfo(
    val id: Long,
    val title: String,
    val titleShort: String,
    val artistId: Long,
    val artistName: String,
    val albumId: Long? = null,
    val albumTitle: String? = null,
    val durationSec: Int = 0,
)

@Singleton
class DeezerClient(
    private val httpGet: (suspend (String) -> String)? = null,
    /** The least time between two requests: Deezer answers 50 per 5 seconds, and past that an error that looks like an empty answer. */
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    /** How long a refused request waits before it is tried again (it grows with each try). */
    private val retryDelayMs: Long = QUOTA_RETRY_MS,
) {
    @Inject
    constructor() : this(null)

    private val spacing = Mutex()
    private var lastRequestAt = 0L

    private val searchCache = ConcurrentHashMap<String, List<DeezerArtistProfile>>()
    private val albumsCache = ConcurrentHashMap<Long, List<DeezerAlbumInfo>>()
    private val topTracksCache = ConcurrentHashMap<Long, List<DeezerTrackInfo>>()
    private val albumTracksCache = ConcurrentHashMap<Long, List<DeezerTrackInfo>>()
    private val relatedCache = ConcurrentHashMap<Long, List<DeezerArtistProfile>>()
    private val chartCache = ConcurrentHashMap<Int, List<DeezerChartAlbum>>()
    private val coveredAlbumsCache = ConcurrentHashMap<Long, List<DeezerChartAlbum>>()
    private val releasesCache = ConcurrentHashMap<Long, List<DeezerRelease>>()

    /**
     * One answer, never an error taken for an answer: requests are spaced out, and Deezer's "quota exceeded" (it comes with HTTP
     * 200 and no data, so it reads as an artist with no albums) is waited out and asked again, then reported as a failure so
     * that nothing is cached from it.
     */
    private suspend fun fetch(urlString: String): String {
        repeat(QUOTA_TRIES) { attempt ->
            spacing.withLock {
                val wait = minIntervalMs - (System.currentTimeMillis() - lastRequestAt)
                if (wait > 0) delay(wait)
                lastRequestAt = System.currentTimeMillis()
            }
            val text = rawFetch(urlString)
            if (!isQuotaError(text)) return text
            delay(retryDelayMs * (attempt + 1))
        }
        throw Exception("Deezer: quota exceeded")
    }

    private fun isQuotaError(text: String): Boolean {
        if (!text.contains("\"error\"")) return false
        return runCatching { JSONObject(text).optJSONObject("error")?.optInt("code") == QUOTA_CODE }.getOrDefault(false)
    }

    private suspend fun rawFetch(urlString: String): String {
        httpGet?.let { return it(urlString) }
        return withContext(Dispatchers.IO) {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            try {
                val code = connection.responseCode
                if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw Exception("Deezer HTTP $code: $error")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun searchArtist(name: String, limit: Int = 10): List<DeezerArtistProfile> {
        val clean = name.trim()
        if (clean.isEmpty()) return emptyList()
        val key = clean.lowercase()
        searchCache[key]?.let { return it }

        return runCatching {
            val q = URLEncoder.encode(clean, "UTF-8")
            val url = "$BASE_URL/search/artist?q=$q&limit=$limit"
            val text = fetch(url)
            val json = JSONObject(text)
            val data = json.optJSONArray("data") ?: JSONArray()
            val result = ArrayList<DeezerArtistProfile>(data.length())

            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val id = item.optLong("id")
                val aName = item.optString("name")
                if (id <= 0 || aName.isBlank()) continue

                val pic = item.optString("picture_xl").ifEmpty {
                    item.optString("picture_big").ifEmpty { item.optString("picture_medium") }
                }.takeIf { it.isNotBlank() && !it.contains("/artist//") }

                result += DeezerArtistProfile(
                    id = id,
                    name = aName,
                    nbFan = item.optInt("nb_fan", 0),
                    pictureXl = pic,
                    tracklistUrl = item.optString("tracklist").takeIf { it.isNotBlank() },
                )
            }
            result.also { searchCache[key] = it }
        }.getOrDefault(emptyList())
    }

    /** The albums most listened to in a Deezer genre (152 is rock, 116 rap, 129 jazz…), with their covers; empty when it does not answer. */
    suspend fun chartAlbums(genreId: Int, limit: Int = 40): List<DeezerChartAlbum> {
        chartCache[genreId]?.let { return it }
        return runCatching {
            val json = JSONObject(fetch("$BASE_URL/chart/$genreId/albums?limit=$limit"))
            val data = json.optJSONArray("data") ?: JSONArray()
            val out = ArrayList<DeezerChartAlbum>(data.length())
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val id = item.optLong("id")
                val title = item.optString("title")
                val artist = item.optJSONObject("artist")?.optString("name").orEmpty()
                val cover = item.optString("cover_xl").ifEmpty { item.optString("cover_big") }
                // A compilation is not an artist's album: it would carry no artist to speak of.
                if (id <= 0 || title.isBlank() || artist.isBlank() || cover.isBlank() || item.optString("record_type") == "compile") continue
                out += DeezerChartAlbum(id, title, artist, cover)
            }
            out.also { if (it.isNotEmpty()) chartCache[genreId] = it }
        }.getOrDefault(emptyList())
    }

    suspend fun getArtist(artistId: Long): DeezerArtistProfile? {
        if (artistId <= 0) return null
        return runCatching {
            val url = "$BASE_URL/artist/$artistId"
            val text = fetch(url)
            val item = JSONObject(text)
            val id = item.optLong("id")
            val aName = item.optString("name")
            if (id <= 0 || aName.isBlank()) return null

            val pic = item.optString("picture_xl").ifEmpty {
                item.optString("picture_big").ifEmpty { item.optString("picture_medium") }
            }.takeIf { it.isNotBlank() && !it.contains("/artist//") }

            DeezerArtistProfile(
                id = id,
                name = aName,
                nbFan = item.optInt("nb_fan", 0),
                pictureXl = pic,
                tracklistUrl = item.optString("tracklist").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    suspend fun getArtistAlbums(artistId: Long, limit: Int = 100): List<DeezerAlbumInfo> {
        if (artistId <= 0) return emptyList()
        albumsCache[artistId]?.let { return it }

        return runCatching {
            val url = "$BASE_URL/artist/$artistId/albums?limit=$limit"
            val text = fetch(url)
            val json = JSONObject(text)
            val data = json.optJSONArray("data") ?: JSONArray()
            val result = ArrayList<DeezerAlbumInfo>(data.length())

            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val id = item.optLong("id")
                val title = item.optString("title")
                if (id <= 0 || title.isBlank()) continue

                result += DeezerAlbumInfo(
                    id = id,
                    title = title,
                    recordType = item.optString("record_type", "album"),
                    releaseDate = item.optString("release_date").takeIf { it.isNotBlank() },
                )
            }
            result.also { albumsCache[artistId] = it }
        }.getOrDefault(emptyList())
    }

    /** An artist's albums and EPs, each with its cover (no compilation, no single), the most recent first; empty when Deezer does not answer. */
    suspend fun artistAlbumsWithCovers(artistId: Long, artistName: String, limit: Int = 24): List<DeezerChartAlbum> {
        if (artistId <= 0) return emptyList()
        coveredAlbumsCache[artistId]?.let { return it }
        return runCatching {
            val json = JSONObject(fetch("$BASE_URL/artist/$artistId/albums?limit=$limit"))
            val data = json.optJSONArray("data") ?: JSONArray()
            val out = ArrayList<Pair<String, DeezerChartAlbum>>(data.length())
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val id = item.optLong("id")
                val title = item.optString("title")
                val cover = item.optString("cover_xl").ifEmpty { item.optString("cover_big") }
                val type = item.optString("record_type", "album")
                if (id <= 0 || title.isBlank() || cover.isBlank() || (type != "album" && type != "ep")) continue
                out += item.optString("release_date") to DeezerChartAlbum(id, title, artistName, cover)
            }
            out.sortedByDescending { it.first }.map { it.second }.also { if (it.isNotEmpty()) coveredAlbumsCache[artistId] = it }
        }.getOrDefault(emptyList())
    }

    /** Everything an artist released, albums, EPs and singles (no compilation), the most recent first, with date and cover; empty when Deezer does not answer. */
    suspend fun artistReleases(artistId: Long, limit: Int = 100): List<DeezerRelease> {
        if (artistId <= 0) return emptyList()
        releasesCache[artistId]?.let { return it }
        return runCatching {
            val data = JSONObject(fetch("$BASE_URL/artist/$artistId/albums?limit=$limit")).optJSONArray("data") ?: JSONArray()
            val out = ArrayList<DeezerRelease>(data.length())
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val id = item.optLong("id")
                val title = item.optString("title")
                val type = item.optString("record_type", "album")
                val cover = item.optString("cover_xl").ifEmpty { item.optString("cover_big") }
                if (id <= 0 || title.isBlank() || (type != "album" && type != "ep" && type != "single")) continue
                out += DeezerRelease(id, title, type, item.optString("release_date"), cover)
            }
            out.sortedByDescending { it.releaseDate }.also { if (it.isNotEmpty()) releasesCache[artistId] = it }
        }.getOrDefault(emptyList())
    }

    suspend fun getArtistTopTracks(artistId: Long, limit: Int = 100): List<DeezerTrackInfo> {
        if (artistId <= 0) return emptyList()
        topTracksCache[artistId]?.let { return it }

        return runCatching {
            val url = "$BASE_URL/artist/$artistId/top?limit=$limit"
            val text = fetch(url)
            val json = JSONObject(text)
            val data = json.optJSONArray("data") ?: JSONArray()
            val result = ArrayList<DeezerTrackInfo>(data.length())

            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val id = item.optLong("id")
                val title = item.optString("title")
                if (id <= 0 || title.isBlank()) continue

                val artistObj = item.optJSONObject("artist")
                val aId = artistObj?.optLong("id") ?: artistId
                val aName = artistObj?.optString("name").orEmpty()
                val albumObj = item.optJSONObject("album")

                result += DeezerTrackInfo(
                    id = id,
                    title = title,
                    titleShort = item.optString("title_short", title),
                    artistId = aId,
                    artistName = aName,
                    albumId = albumObj?.optLong("id"),
                    albumTitle = albumObj?.optString("title"),
                    durationSec = item.optInt("duration", 0),
                )
            }
            result.also { topTracksCache[artistId] = it }
        }.getOrDefault(emptyList())
    }

    suspend fun getAlbumTracks(albumId: Long): List<DeezerTrackInfo> {
        if (albumId <= 0) return emptyList()
        albumTracksCache[albumId]?.let { return it }

        return runCatching {
            val url = "$BASE_URL/album/$albumId/tracks?limit=100"
            val text = fetch(url)
            val json = JSONObject(text)
            val data = json.optJSONArray("data") ?: JSONArray()
            val result = ArrayList<DeezerTrackInfo>(data.length())

            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val id = item.optLong("id")
                val title = item.optString("title")
                if (id <= 0 || title.isBlank()) continue

                val artistObj = item.optJSONObject("artist")

                result += DeezerTrackInfo(
                    id = id,
                    title = title,
                    titleShort = item.optString("title_short", title),
                    artistId = artistObj?.optLong("id") ?: 0L,
                    artistName = artistObj?.optString("name").orEmpty(),
                    albumId = albumId,
                    durationSec = item.optInt("duration", 0),
                )
            }
            result.also { albumTracksCache[albumId] = it }
        }.getOrDefault(emptyList())
    }

    suspend fun getRelatedArtists(artistId: Long, limit: Int = 30): List<DeezerArtistProfile> {
        if (artistId <= 0) return emptyList()
        relatedCache[artistId]?.let { return it }

        return runCatching {
            val url = "$BASE_URL/artist/$artistId/related?limit=$limit"
            val text = fetch(url)
            val json = JSONObject(text)
            val data = json.optJSONArray("data") ?: JSONArray()
            val result = ArrayList<DeezerArtistProfile>(data.length())

            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val id = item.optLong("id")
                val aName = item.optString("name")
                if (id <= 0 || aName.isBlank()) continue

                val pic = item.optString("picture_xl").ifEmpty {
                    item.optString("picture_big").ifEmpty { item.optString("picture_medium") }
                }.takeIf { it.isNotBlank() && !it.contains("/artist//") }

                result += DeezerArtistProfile(
                    id = id,
                    name = aName,
                    nbFan = item.optInt("nb_fan", 0),
                    pictureXl = pic,
                    tracklistUrl = item.optString("tracklist").takeIf { it.isNotBlank() },
                )
            }
            result.also { relatedCache[artistId] = it }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val BASE_URL = "https://api.deezer.com"
        const val MIN_INTERVAL_MS = 110L
        const val QUOTA_RETRY_MS = 1_200L
        const val QUOTA_TRIES = 3
        const val QUOTA_CODE = 4
        const val USER_AGENT = "AuraMusicPlayer/1.0.0 ( dooorians15@gmail.com )"
    }
}
