package com.ikko.aura.source.jiosaavn

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
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
            val trackId = uri.host ?: throw IOException("Missing JioSaavn track ID in URI: $uri")
            
            // Resolve the stream URL synchronously
            val resolvedUrl = runBlocking {
                val stream = client.getStreamUrl(trackId)
                stream?.url ?: throw IOException("Could not resolve JioSaavn stream for $trackId")
            }
            
            val newSpec = dataSpec.buildUpon().setUri(Uri.parse(resolvedUrl)).build()
            return upstream.open(newSpec)
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
