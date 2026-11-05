package com.ikko.aura.lastfm

import android.content.Context
import com.ikko.aura.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What Last.fm asks for, apart from the network: when a listen counts, and how a call is signed. */
object LastFmRules {
    const val MIN_SONG_MS = 30_000L
    const val MAX_NEEDED_MS = 240_000L

    /** A song counts once half of it, or four minutes, has played; and only if it is longer than 30 seconds. */
    fun counts(playedMs: Long, durationMs: Long): Boolean =
        durationMs >= MIN_SONG_MS && playedMs >= minOf(durationMs / 2, MAX_NEEDED_MS)

    /** md5 of every parameter's name and value, by name, then the secret (`format` and `callback` are left out). */
    fun sign(params: Map<String, String>, secret: String): String {
        val text = params.filterKeys { it != "format" && it != "callback" }.toSortedMap()
            .entries.joinToString("") { it.key + it.value } + secret
        return MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Last.fm scrobbling, automatic once connected: the song playing is announced, and each one heard enough is sent when it is
 * left. What could not be sent (no network) is kept on the device and sent with the next one.
 *
 * The app's own key and secret (from last.fm/api/account/create) are typed once, with the account; the password is only
 * sent to Last.fm to get a session and is not kept.
 */
@Singleton
class LastFm @Inject constructor(@ApplicationContext context: Context) : com.ikko.aura.reco.ArtistInsight {
    private val prefs = context.getSharedPreferences("lastfm", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()

    private val _user = MutableStateFlow(prefs.getString("user", null))
    /** The connected account's name, null when not connected. */
    val user: StateFlow<String?> = _user.asStateFlow()

    private val _pending = MutableStateFlow(queue().length())
    /** How many listens wait to be sent. */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    /** Whether the app carries its own key and secret (local.properties), so the account is all that is asked. */
    val hasBuiltInKey: Boolean = com.ikko.aura.BuildConfig.LASTFM_KEY.isNotEmpty() && com.ikko.aura.BuildConfig.LASTFM_SECRET.isNotEmpty()
    val savedKey: String get() = com.ikko.aura.BuildConfig.LASTFM_KEY.ifEmpty { prefs.getString("apiKey", "").orEmpty() }
    val savedSecret: String get() = com.ikko.aura.BuildConfig.LASTFM_SECRET.ifEmpty { prefs.getString("secret", "").orEmpty() }

    /** The address to open in the browser, where the account is approved: a token from Last.fm, in its page. Null with a reason on failure. */
    suspend fun startWebLogin(apiKey: String, secret: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = call(apiKey, secret, mapOf("method" to "auth.getToken")).getString("token")
            token to "https://www.last.fm/api/auth/?api_key=${URLEncoder.encode(apiKey, "UTF-8")}&token=$token"
        }.recoverCatching { throw Exception(if (it is LastFmError) it.message else "Pas de réseau") }
    }

    /** Whether the token has been approved yet: null while it has not (error 14), true once connected, else the reason. */
    suspend fun finishWebLogin(apiKey: String, secret: String, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                val session = call(apiKey, secret, mapOf("method" to "auth.getSession", "token" to token)).getJSONObject("session")
                prefs.edit().putString("apiKey", apiKey).putString("secret", secret)
                    .putString("sk", session.getString("key")).putString("user", session.getString("name")).apply()
                _user.value = session.getString("name")
                flush()
                true
            } catch (e: LastFmError) {
                // 14: the token is not approved yet. 15: it has expired.
                if (e.code == 14) false else throw Exception(e.message)
            }
        }
    }

    private val insightPrefs = context.getSharedPreferences("lastfm_insight", Context.MODE_PRIVATE)

    /** An answer kept on the device: what Last.fm says of an artist hardly changes, so it is asked about once in two weeks. */
    private suspend fun cached(kind: String, name: String, ask: () -> List<String>): List<String>? {
        if (savedKey.isEmpty()) return null
        val key = kind + ":" + name.lowercase().trim()
        insightPrefs.getString(key, null)?.let { raw ->
            val at = raw.substringBefore('\t').toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - at < INSIGHT_TTL_MS) return raw.substringAfter('\t', "").split('\u0001').filter { it.isNotEmpty() }
        }
        return withContext(Dispatchers.IO) {
            try {
                ask().also { insightPrefs.edit().putString(key, "${System.currentTimeMillis()}\t${it.joinToString("\u0001")}").apply() }
            } catch (e: LastFmError) {
                // 6: no such artist. A definite answer, kept; anything else (network, limit) is not.
                if (e.code == 6) { insightPrefs.edit().putString(key, "${System.currentTimeMillis()}\t").apply(); emptyList() } else null
            } catch (e: Exception) { null }
        }
    }

    private val noiseTags = setOf(
        "seen live", "male vocalists", "female vocalists", "vocalists", "favorites", "favourites", "favourite",
        "under 2000 listeners", "all", "spotify", "love", "awesome", "good", "cool", "my top artists",
    )

    override suspend fun tags(name: String): List<String>? = cached("tags_v2", com.ikko.aura.reco.ArtistNames.askName(name)) {
        val arr = call(savedKey, savedSecret, mapOf("method" to "artist.getTopTags", "artist" to com.ikko.aura.reco.ArtistNames.askName(name), "autocorrect" to "0"))
            .optJSONObject("toptags")?.optJSONArray("tag") ?: JSONArray()
        val self = com.ikko.aura.reco.ArtistNames.askName(name).trim().lowercase()
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optInt("count", 0) >= 25 }
            .map { it.getString("name").trim() }
            .filter { t -> t.isNotEmpty() && t.lowercase() != self && t.lowercase() !in noiseTags }
            .take(5)
    }

    /** What listeners called this very song ("chill", "dance", "night"): kept two weeks like the artist's. */
    override suspend fun trackTags(title: String, artist: String): List<String>? =
        cached("trktags_v1", com.ikko.aura.reco.ArtistNames.askName(artist) + " - " + title.trim()) {
            val arr = call(
                savedKey, savedSecret,
                mapOf("method" to "track.getTopTags", "artist" to com.ikko.aura.reco.ArtistNames.askName(artist), "track" to title.trim(), "autocorrect" to "1"),
            ).optJSONObject("toptags")?.optJSONArray("tag") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optInt("count", 0) >= 15 }
                .map { it.getString("name").trim().lowercase() }
                .filter { it.isNotEmpty() && it !in noiseTags }
                .take(10)
        }

    override suspend fun similar(name: String): List<String>? = cached("similar", com.ikko.aura.reco.ArtistNames.askName(name)) {
        val arr = call(savedKey, savedSecret, mapOf("method" to "artist.getSimilar", "artist" to com.ikko.aura.reco.ArtistNames.askName(name), "limit" to "40", "autocorrect" to "0"))
            .optJSONObject("similarartists")?.optJSONArray("artist") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    }

    /** The artist's most played songs on Last.fm, by their titles (kept 14 days): what a photo's owner is checked against. */
    override suspend fun topTracks(name: String): List<String>? = cached("toptracks", com.ikko.aura.reco.ArtistNames.askName(name)) {
        val arr = call(savedKey, savedSecret, mapOf("method" to "artist.getTopTracks", "artist" to com.ikko.aura.reco.ArtistNames.askName(name), "limit" to "40", "autocorrect" to "0"))
            .optJSONObject("toptracks")?.optJSONArray("track") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
            .filterNot { com.ikko.aura.reco.authority.CompilationTitles.matches(it) }
    }

    override suspend fun mbid(name: String): String? {
        if (savedKey.isEmpty()) return null
        return cached("mbid", com.ikko.aura.reco.ArtistNames.askName(name)) {
            val artistObj = call(savedKey, savedSecret, mapOf("method" to "artist.getInfo", "artist" to com.ikko.aura.reco.ArtistNames.askName(name), "autocorrect" to "1"))
                .optJSONObject("artist")
            val id = artistObj?.optString("mbid").orEmpty().trim()
            if (id.isNotEmpty()) listOf(id) else emptyList()
        }?.firstOrNull()
    }

    /** Artists close to [name] with how close, 0..1: kept with the same care as [similar]. */
    suspend fun similarScored(name: String): List<Pair<String, Double>>? = cached("simscore", com.ikko.aura.reco.ArtistNames.askName(name)) {
        val arr = call(savedKey, savedSecret, mapOf("method" to "artist.getSimilar", "artist" to com.ikko.aura.reco.ArtistNames.askName(name), "limit" to "60", "autocorrect" to "0"))
            .optJSONObject("similarartists")?.optJSONArray("artist") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { it.getString("name") + "\u0002" + it.optString("match", "0") }
    }?.mapNotNull { row -> row.split('\u0002').let { p -> p.getOrNull(1)?.toDoubleOrNull()?.let { p[0] to it } } }

    /** The account's own listening, for the first fill of the profile: null when not connected or when Last.fm does not answer. */
    class ScrobbleProfile(
        val topArtists: List<Pair<String, Int>>,
        val topTracks: List<Triple<String, String, Int>>,
        val lovedTracks: List<Pair<String, String>>,
    )

    suspend fun scrobbleProfile(): ScrobbleProfile? {
        val who = prefs.getString("user", null) ?: return null
        if (savedKey.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                fun get(method: String, root: String, item: String, limit: Int, extra: Map<String, String> = emptyMap()): List<JSONObject> {
                    val arr = call(savedKey, savedSecret, mapOf("method" to method, "user" to who, "limit" to limit.toString()) + extra)
                        .optJSONObject(root)?.optJSONArray(item) ?: JSONArray()
                    return (0 until arr.length()).map { arr.getJSONObject(it) }
                }
                ScrobbleProfile(
                    topArtists = get("user.getTopArtists", "topartists", "artist", 100, mapOf("period" to "overall"))
                        .map { it.getString("name") to it.optString("playcount", "0").toInt() },
                    topTracks = get("user.getTopTracks", "toptracks", "track", 300, mapOf("period" to "overall"))
                        .map { Triple(it.getString("name"), it.getJSONObject("artist").getString("name"), it.optString("playcount", "0").toInt()) },
                    lovedTracks = get("user.getLovedTracks", "lovedtracks", "track", 300)
                        .map { it.getString("name") to it.getJSONObject("artist").getString("name") },
                )
            }.getOrNull()
        }
    }

    /** What the connected account plays most (the last six months), kept a day: a signal of taste the app did not hear itself. */
    override suspend fun listenerTopArtists(): List<String> {
        val who = prefs.getString("user", null) ?: return emptyList()
        // The last six months first (what is played now), then the whole of the scrobbles (what is loved).
        fun top(period: String, limit: Int): List<String> {
            val arr = call(savedKey, savedSecret, mapOf("method" to "user.getTopArtists", "user" to who, "period" to period, "limit" to limit.toString()))
                .optJSONObject("topartists")?.optJSONArray("artist") ?: JSONArray()
            return (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
        }
        return cached("top2", who) { (top("6month", 40) + top("overall", 25)).distinct() }.orEmpty()
    }

    fun logout() {
        prefs.edit().remove("sk").remove("user").remove("pending").apply()
        _user.value = null
        _pending.value = 0
    }

    /** The song that has just begun. */
    fun nowPlaying(track: Track) {
        val sk = prefs.getString("sk", null) ?: return
        scope.launch {
            runCatching {
                call(savedKey, savedSecret, mapOf(
                    "method" to "track.updateNowPlaying", "artist" to track.artist, "track" to track.title,
                    "album" to track.album, "duration" to (track.durationMs / 1000).toString(), "sk" to sk,
                ))
            }
        }
    }

    /** A song has been left after [playedMs]: it is a scrobble if it was heard enough. */
    fun songLeft(track: Track, playedMs: Long, durationMs: Long, startedAtMs: Long) {
        if (prefs.getString("sk", null) == null) return
        val duration = if (durationMs > 0) durationMs else track.durationMs
        if (!LastFmRules.counts(playedMs, duration) || track.artist.isBlank() || track.title.isBlank()) return
        scope.launch {
            sending.withLock {
                val q = queue()
                q.put(JSONObject().put("artist", track.artist).put("track", track.title).put("album", track.album)
                    .put("duration", duration / 1000).put("timestamp", startedAtMs / 1000))
                save(q)
            }
            flush()
        }
    }

    private suspend fun flush() = sending.withLock {
        val sk = prefs.getString("sk", null) ?: return@withLock
        while (true) {
            val q = queue()
            if (q.length() == 0) return@withLock
            val n = minOf(q.length(), 50)
            val params = mutableMapOf("method" to "track.scrobble", "sk" to sk)
            for (i in 0 until n) {
                val item = q.getJSONObject(i)
                params["artist[$i]"] = item.getString("artist")
                params["track[$i]"] = item.getString("track")
                params["timestamp[$i]"] = item.getLong("timestamp").toString()
                if (item.getString("album").isNotEmpty()) params["album[$i]"] = item.getString("album")
                params["duration[$i]"] = item.getLong("duration").toString()
            }
            val sent = runCatching { call(savedKey, savedSecret, params) }
            val error = sent.exceptionOrNull() as? LastFmError
            when {
                sent.isSuccess -> Unit
                // No network, the service is down or busy: what is kept goes with the next one.
                error == null || error.code in TEMPORARY -> return@withLock
                // The session is no longer valid: the account has to be connected again.
                error.code == 9 -> { logout(); return@withLock }
                // Anything else is refused for good: dropped, so one bad listen does not stop the others.
            }
            val rest = JSONArray()
            for (i in n until q.length()) rest.put(q.get(i))
            save(rest)
        }
    }

    private fun queue(): JSONArray = runCatching { JSONArray(prefs.getString("pending", "[]")) }.getOrDefault(JSONArray())

    private fun save(q: JSONArray) {
        prefs.edit().putString("pending", q.toString()).apply()
        _pending.value = q.length()
    }

    private class LastFmError(val code: Int, message: String) : Exception(message)

    private fun call(apiKey: String, secret: String, params: Map<String, String>): JSONObject {
        val all = params + ("api_key" to apiKey)
        val body = (all + ("api_sig" to LastFmRules.sign(all, secret)) + ("format" to "json")).entries
            .joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val connection = URL("https://ws.audioscrobbler.com/2.0/").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val text = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(text)
            if (json.has("error")) throw LastFmError(json.getInt("error"), json.optString("message"))
            return json
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** Service offline, temporarily unavailable, rate limit. */
        val TEMPORARY = setOf(11, 16, 29)
        const val INSIGHT_TTL_MS = 14L * 24 * 3600 * 1000
    }
}
