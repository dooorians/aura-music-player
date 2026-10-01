package com.ikko.aura.source.ytm

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Opens `ytm://<videoId>` (and plain http) for the player, in bounded `Range` chunks.
 *
 * Chunks, because whole-file requests are throttled by the server to about 30 KB/s — measured — while 512 KB ranges
 * come back in a fraction of a second. The stream address is asked of [YtmClient] only here, when the player opens the
 * item; an address that was refused (403 or 410: it expired, or was revoked) is dropped and asked for again, once,
 * without the player ever hearing of it.
 */
@UnstableApi
class YtmDataSource(private val client: YtmClient, private val chunkBytes: Int = CHUNK_BYTES) : DataSource {

    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .createDataSource()

    private var spec: DataSpec? = null
    private var videoId: String? = null
    private var url: Uri = Uri.EMPTY
    private var pos = 0L
    private var end = C.LENGTH_UNSET.toLong() // exclusive, once known
    private var chunkLeft = 0L
    private var ended = false
    private var httpOpen = false

    override fun addTransferListener(transferListener: TransferListener) = http.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        videoId = dataSpec.uri.takeIf { it.scheme == YTM_SCHEME }?.schemeSpecificPart?.removePrefix("//")
        url = videoId?.let { Uri.parse(client.stream(it).url) } ?: dataSpec.uri
        pos = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) pos + dataSpec.length else C.LENGTH_UNSET.toLong()
        ended = false
        openChunk()
        val total = http.responseHeaders["Content-Range"]?.firstOrNull()?.substringAfter('/')?.toLongOrNull()
        if (total != null) end = if (end == C.LENGTH_UNSET.toLong()) total else minOf(end, total)
        return if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - pos
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (ended) return C.RESULT_END_OF_INPUT
        if (chunkLeft == 0L) {
            if (end != C.LENGTH_UNSET.toLong() && pos >= end) { ended = true; return C.RESULT_END_OF_INPUT }
            openChunk()
        }
        val read = http.read(buffer, offset, minOf(length.toLong(), chunkLeft).toInt())
        if (read == C.RESULT_END_OF_INPUT) { ended = true; return read }
        pos += read
        chunkLeft -= read
        return read
    }

    private fun openChunk() {
        val base = spec ?: throw IOException("not open")
        val wanted = if (end == C.LENGTH_UNSET.toLong()) chunkBytes.toLong() else minOf(chunkBytes.toLong(), end - pos)
        fun attempt(): Long {
            if (httpOpen) http.close()
            httpOpen = false
            val opened = http.open(
                base.buildUpon()
                    .setUri(url).setPosition(pos).setLength(wanted)
                    .setFlags(base.flags and DataSpec.FLAG_ALLOW_GZIP.inv())
                    .build(),
            )
            httpOpen = true
            return opened
        }
        val opened = try {
            attempt()
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            val id = videoId
            if (id == null || e.responseCode !in EXPIRED_CODES) throw e
            client.invalidate(id)
            url = Uri.parse(client.stream(id).url)
            attempt()
        }
        chunkLeft = if (opened == C.LENGTH_UNSET.toLong()) wanted else opened
    }

    override fun getUri(): Uri? = spec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = http.responseHeaders

    override fun close() {
        if (httpOpen) http.close()
        httpOpen = false
        spec = null
    }

    companion object {
        private const val CHUNK_BYTES = 512 * 1024
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
        private val EXPIRED_CODES = setOf(403, 410)
    }
}

/**
 * The kill switch, above the cache: with YouTube Music off, a `ytm://` item does not open at all — not even from songs
 * already cached, which would otherwise keep playing. Reported as an unreachable source, so the queue moves on.
 */
@UnstableApi
class YtmGate(private val client: YtmClient, private val upstream: DataSource) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme == YTM_SCHEME && !client.isEnabled()) {
            throw HttpDataSource.HttpDataSourceException(
                "YouTube Music is off",
                dataSpec,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()
}
