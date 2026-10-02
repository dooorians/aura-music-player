package com.ikko.aura.source.jiosaavn

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Singleton
class JioSaavnClient @Inject constructor() {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val baseUrl = String(Base64.decode("aHR0cHM6Ly93d3cuamlvc2Fhdm4uY29tL2FwaS5waHA=", Base64.DEFAULT), Charsets.UTF_8)

    /** Opens the connection (lookup, handshake) ahead of the first real search. */
    suspend fun warmUp() { searchSongs("music") }

    /** [searchSongs], but a failure (no network, an error from the server) is thrown instead of reading as "no results". */
    suspend fun searchSongsOrThrow(query: String): List<RawSongItem> = withContext(Dispatchers.IO) {
        val urlString = "$baseUrl?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=android&p=1&n=10&q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val body = json.decodeFromString<RawSearchResponse>(executeRequest(urlString))
        body.results.sortedByDescending { it.isExplicit }
    }

    suspend fun searchSongs(query: String): List<RawSongItem> = withContext(Dispatchers.IO) {
        runCatching {
            val urlString = "$baseUrl?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=android&p=1&n=10&q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val response = executeRequest(urlString)
            val body = json.decodeFromString<RawSearchResponse>(response)
            body.results.sortedByDescending { it.isExplicit }
        }.getOrElse { emptyList() }
    }

    suspend fun getStreamUrl(saavnSongId: String): SaavnStream? = withContext(Dispatchers.IO) {
        runCatching {
            val urlString = "$baseUrl?__call=song.getDetails&_format=json&_marker=0&api_version=4&ctx=android&pids=$saavnSongId"
            val response = executeRequest(urlString)
            
            val root = json.parseToJsonElement(response) as? JsonObject ?: return@runCatching null
            val songElement = (root["songs"] as? JsonArray)?.firstOrNull()
                ?: root.values.firstOrNull { it is JsonObject }
                ?: return@runCatching null
                
            val rawSong = json.decodeFromJsonElement(RawSongItem.serializer(), songElement)
            bestStream(rawSong.moreInfo.encryptedMediaUrl, rawSong.moreInfo.supports320)
        }.getOrNull()
    }

    private fun executeRequest(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            connection.setRequestProperty("X-Forwarded-For", "49.36.0.1")
            connection.setRequestProperty("X-Real-IP", "49.36.0.1")
            connection.setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
            connection.setRequestProperty("Cookie", "explicit_content=1")
            
            if (connection.responseCode != 200) {
                throw Exception("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun bestStream(encryptedUrl: String, supports320: Boolean): SaavnStream? {
        val decryptedUrl = decryptUrl(encryptedUrl)
        if (decryptedUrl.isBlank()) return null
        
        val rendition = Regex(
            "_(48|96|160|320)\\.(mp4|aac|mp3)(?=[?#]|$)",
            RegexOption.IGNORE_CASE
        ).find(decryptedUrl) ?: return SaavnStream(decryptedUrl, null)
        
        val offered = rendition.groupValues[1].toInt()
        if (!supports320) return SaavnStream(decryptedUrl, offered)
        
        val extension = rendition.groupValues[2]
        val rewrittenUrl = decryptedUrl.replaceRange(rendition.range, "_320.\$extension")
        return SaavnStream(rewrittenUrl, 320)
    }

    private fun decryptUrl(encryptedUrl: String): String {
        if (encryptedUrl.isBlank()) return ""
        return try {
            val key = "38346591" // DES 8-byte key
            val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decodedBytes = Base64.decode(encryptedUrl, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            ""
        }
    }
}
