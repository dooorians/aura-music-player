package com.ikko.aura.source.jiosaavn

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.runBlocking

/**
 * Intercepts `jiosaavn://{trackId}` and resolves it to a real streaming URL
 * just-in-time on the ExoPlayer loading thread.
 */
@UnstableApi
class JioSaavnDataSource(
    private val client: JioSaavnClient,
    private val upstream: DataSource
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme == "jiosaavn") {
            val trackId = uri.authority?.takeIf { it.isNotBlank() }
                ?: uri.host?.takeIf { it.isNotBlank() }
                ?: uri.schemeSpecificPart?.removePrefix("//")?.trim('/')?.takeIf { it.isNotBlank() }
                ?: throw HttpDataSource.HttpDataSourceException(
                    "Missing JioSaavn track ID in URI: $uri",
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )

            val stream = runBlocking { client.getStreamUrl(trackId) }
                ?: throw HttpDataSource.HttpDataSourceException(
                    "Could not resolve JioSaavn stream for $trackId",
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )

            val primarySpec = dataSpec.buildUpon().setUri(Uri.parse(stream.url)).build()
            return try {
                upstream.open(primarySpec)
            } catch (e: Exception) {
                val fallback = stream.fallbackUrl?.takeIf { it.isNotBlank() } ?: throw e
                runCatching { upstream.close() }
                upstream.open(dataSpec.buildUpon().setUri(Uri.parse(fallback)).build())
            }
        }

        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        upstream.close()
    }
}
