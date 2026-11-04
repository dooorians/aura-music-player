package com.ikko.aura.data.musicbrainz

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

data class MusicBrainzArtist(
    val mbid: String,
    val name: String,
    val sortName: String? = null,
    val country: String? = null,
    val disambiguation: String? = null,
    val tags: List<Pair<String, Int>> = emptyList(),
    val aliases: List<String> = emptyList(),
    val score: Int = 0,
)

data class MusicBrainzReleaseGroup(
    val id: String,
    val title: String,
    val primaryType: String? = null,
    val firstReleaseDate: String? = null,
)

@Singleton
class MusicBrainzClient(
    private val httpGet: (suspend (String) -> String)? = null,
) {
    @Inject
    constructor() : this(null)

    private val rateMutex = Mutex()
    private var lastRequestTimeMs = 0L

    private val searchCache = ConcurrentHashMap<String, List<MusicBrainzArtist>>()
    private val releaseGroupsCache = ConcurrentHashMap<String, List<MusicBrainzReleaseGroup>>()
    private val recordingsCache = ConcurrentHashMap<String, List<String>>()

    private suspend fun fetch(urlString: String): String {
        httpGet?.let { return it(urlString) }
        return rateMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestTimeMs
            if (elapsed < MIN_INTERVAL_MS) {
                delay(MIN_INTERVAL_MS - elapsed)
            }
            lastRequestTimeMs = System.currentTimeMillis()

            withContext(Dispatchers.IO) {
                val connection = URL(urlString).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")
                try {
                    val code = connection.responseCode
                    if (code in 200..299) {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        throw Exception("MusicBrainz HTTP $code: $error")
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    suspend fun searchArtist(name: String): List<MusicBrainzArtist> {
        val clean = name.trim()
        if (clean.isEmpty()) return emptyList()
        val key = clean.lowercase()
        searchCache[key]?.let { return it }

        return runCatching {
            // Use exact phrase query for artist name
            val query = URLEncoder.encode("artist:\"$clean\"", "UTF-8")
            val url = "$BASE_URL/artist/?query=$query&fmt=json&limit=10"
            val text = fetch(url)
            val json = JSONObject(text)
            val artistsArray = json.optJSONArray("artists") ?: JSONArray()
            val result = ArrayList<MusicBrainzArtist>(artistsArray.length())

            for (i in 0 until artistsArray.length()) {
                val item = artistsArray.getJSONObject(i)
                val mbid = item.optString("id")
                val aName = item.optString("name")
                if (mbid.isEmpty() || aName.isEmpty()) continue

                val tagsArr = item.optJSONArray("tags") ?: JSONArray()
                val tags = (0 until tagsArr.length()).map {
                    val t = tagsArr.getJSONObject(it)
                    t.optString("name") to t.optInt("count", 0)
                }

                val aliasesArr = item.optJSONArray("aliases") ?: JSONArray()
                val aliases = (0 until aliasesArr.length()).mapNotNull {
                    aliasesArr.getJSONObject(it).optString("name").takeIf { s -> s.isNotBlank() }
                }

                result += MusicBrainzArtist(
                    mbid = mbid,
                    name = aName,
                    sortName = item.optString("sort-name").takeIf { it.isNotBlank() },
                    country = item.optString("country").takeIf { it.isNotBlank() },
                    disambiguation = item.optString("disambiguation").takeIf { it.isNotBlank() },
                    tags = tags,
                    aliases = aliases,
                    score = item.optInt("score", 0),
                )
            }
            result.also { searchCache[key] = it }
        }.getOrDefault(emptyList())
    }

    suspend fun getArtistReleaseGroups(mbid: String): List<MusicBrainzReleaseGroup> {
        val cleanMbid = mbid.trim()
        if (cleanMbid.isEmpty()) return emptyList()
        releaseGroupsCache[cleanMbid]?.let { return it }

        return runCatching {
            // Browsed by pages of 100: the artist's own lookup answers with 25 release groups at most, far short of a long discography.
            val result = ArrayList<MusicBrainzReleaseGroup>()
            var offset = 0
            while (offset < MAX_RELEASE_GROUPS) {
                val json = JSONObject(fetch("$BASE_URL/release-group?artist=$cleanMbid&limit=100&offset=$offset&fmt=json"))
                val rgArray = json.optJSONArray("release-groups") ?: JSONArray()
                for (i in 0 until rgArray.length()) {
                    val rg = rgArray.getJSONObject(i)
                    val id = rg.optString("id")
                    val title = rg.optString("title")
                    if (id.isEmpty() || title.isEmpty()) continue
                    result += MusicBrainzReleaseGroup(
                        id = id,
                        title = title,
                        primaryType = rg.optString("primary-type").takeIf { it.isNotBlank() },
                        firstReleaseDate = rg.optString("first-release-date").takeIf { it.isNotBlank() },
                    )
                }
                val total = json.optInt("release-group-count", 0)
                offset += 100
                if (rgArray.length() < 100 || offset >= total) break
            }
            result.also { releaseGroupsCache[cleanMbid] = it }
        }.getOrDefault(emptyList())
    }

    /** The titles of an artist's recordings, browsed by pages of 100: what a long discography actually contains. */
    suspend fun getArtistRecordings(mbid: String, limit: Int = MAX_RECORDINGS): List<String> {
        val cleanMbid = mbid.trim()
        if (cleanMbid.isEmpty()) return emptyList()
        recordingsCache[cleanMbid]?.let { return it }

        return runCatching {
            val result = ArrayList<String>()
            var offset = 0
            while (offset < limit) {
                val json = JSONObject(fetch("$BASE_URL/recording?artist=$cleanMbid&limit=100&offset=$offset&fmt=json"))
                val arr = json.optJSONArray("recordings") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    arr.getJSONObject(i).optString("title").takeIf { it.isNotBlank() }?.let(result::add)
                }
                val total = json.optInt("recording-count", 0)
                offset += 100
                if (arr.length() < 100 || offset >= total || offset >= limit) break
            }
            result.distinct().also { recordingsCache[cleanMbid] = it }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val BASE_URL = "https://musicbrainz.org/ws/2"
        const val USER_AGENT = "AuraMusicPlayer/1.0.0 ( dooorians15@gmail.com )"
        const val MIN_INTERVAL_MS = 1000L // MusicBrainz limit: 1 request/second
        const val MAX_RELEASE_GROUPS = 500
        const val MAX_RECORDINGS = 500
    }
}
