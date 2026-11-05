package com.ikko.aura.source.addon

import com.ikko.aura.source.addon.model.AddonManifest
import com.ikko.aura.source.addon.model.AddonSearchResponse
import com.ikko.aura.source.addon.model.AddonStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

enum class AudioQualityTier(val param: String) {
    LOSSLESS("lossless"),
    HIGH("high"),
    LOW("low")
}

@Singleton
class AddonClient @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun getManifest(rootUrl: String): AddonManifest = withContext(Dispatchers.IO) {
        val urlStr = if (rootUrl.endsWith("manifest.json")) rootUrl else "${rootUrl.trimEnd('/')}/manifest.json"
        val response = httpGet(urlStr)
        json.decodeFromString<AddonManifest>(response)
    }

    suspend fun search(rootUrl: String, query: String, quality: AudioQualityTier): AddonSearchResponse = withContext(Dispatchers.IO) {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val base = rootUrl.trimEnd('/')
        // Strip manifest.json if present
        val cleanBase = if (base.endsWith("/manifest.json")) base.removeSuffix("/manifest.json") else base
        val response = httpGet("$cleanBase/search?q=$encodedQuery&quality=${quality.param}")
        json.decodeFromString<AddonSearchResponse>(response)
    }

    suspend fun resolveStream(rootUrl: String, trackId: String, quality: AudioQualityTier): AddonStream = withContext(Dispatchers.IO) {
        val encodedId = java.net.URLEncoder.encode(trackId, "UTF-8")
        val base = rootUrl.trimEnd('/')
        val cleanBase = if (base.endsWith("/manifest.json")) base.removeSuffix("/manifest.json") else base
        val response = httpGet("$cleanBase/stream/$encodedId?quality=${quality.param}")
        json.decodeFromString<AddonStream>(response)
    }

    private fun httpGet(urlStr: String): String {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Aura/1.0 (local music player; https://github.com/ikko/aura)")

        try {
            if (connection.responseCode !in 200..299) {
                if (connection.responseCode == 404) {
                    throw IOException("Not Found (404)")
                }
                throw IOException("HTTP error: ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
