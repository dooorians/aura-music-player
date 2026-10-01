package com.ikko.aura.source.addon

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * Intercepts `addon://<rootUrlBase64>/<trackId>` URIs and resolves them via [AddonClient].
 */
@UnstableApi
class AddonDataSource(
    private val client: AddonClient
) : DataSource {

    private val upstream = DefaultHttpDataSource.Factory()
        .setUserAgent("Aura/1.0 (local music player; https://github.com/ikko/aura)")
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .createDataSource()

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        var specToOpen = dataSpec
        if (dataSpec.uri.scheme == "addon") {
            // addon://encodedRootUrl/trackId
            val host = dataSpec.uri.host
            val path = dataSpec.uri.path?.removePrefix("/")
            if (host != null && path != null) {
                val rootUrl = String(android.util.Base64.decode(host, android.util.Base64.URL_SAFE))
                // For now, always request lossless or high in Phase 1
                try {
                    val streamInfo = runBlocking {
                        client.resolveStream(rootUrl, path, AudioQualityTier.LOSSLESS)
                    }
                    specToOpen = dataSpec.buildUpon().setUri(Uri.parse(streamInfo.url)).build()
                } catch (e: Exception) {
                    throw IOException("Failed to resolve addon stream", e)
                }
            }
        }
        return upstream.open(specToOpen)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()
}
