package com.ikko.aura.source.ytm

import com.ikko.aura.data.SettingsStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** A song as YouTube Music lists it. [videoId] is the source's item id. */
data class YtmSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
)

/** An audio-only stream address and when it stops working. */
data class YtmStream(val url: String, val mimeType: String, val expiresAtMs: Long)

/**
 * The one door to YouTube Music: unofficial, anonymous (no account, no cookies), audio only, off unless the user turned
 * it on. Everything in `source/ytm/` can go without touching any other file but the few that register it.
 *
 * All calls block: they run on the player's loader threads or on the caller's own IO dispatcher.
 */
@Singleton
class YtmClient @Inject constructor(private val settings: SettingsStore) {

    private val streams = ConcurrentHashMap<String, YtmStream>()
    private var initialised = false

    /** Read where it is needed, on the loader thread: the DataStore answers from memory once it has been read. */
    fun isEnabled(): Boolean = runBlocking { settings.ytmEnabled.first() }

    @Synchronized
    private fun ensureInit() {
        if (initialised) return
        NewPipe.init(Http, Localization.DEFAULT)
        initialised = true
    }

    /** Songs only, never videos or clips. */
    fun search(query: String): List<YtmSong> {
        check(isEnabled()) { "YouTube Music is off" }
        ensureInit()
        val extractor = ServiceList.YouTube.getSearchExtractor(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
            "",
        )
        extractor.fetchPage()
        return extractor.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
            val id = videoIdOf(item.url) ?: return@mapNotNull null
            YtmSong(
                videoId = id,
                title = item.name,
                artist = item.uploaderName.orEmpty().removeSuffix(" - Topic"),
                durationMs = item.duration.coerceAtLeast(0) * 1000,
                thumbnailUrl = item.thumbnails.maxByOrNull { it.width }?.url ?: item.thumbnails.firstOrNull()?.url,
            )
        }
    }

    /**
     * The address of the best audio-only stream for [videoId], kept until shortly before it expires (about six hours).
     * Only audio formats are ever considered, so no video byte can reach the player.
     */
    fun stream(videoId: String): YtmStream {
        check(isEnabled()) { "YouTube Music is off" }
        val now = System.currentTimeMillis()
        streams[videoId]?.takeIf { it.expiresAtMs - EXPIRY_MARGIN_MS > now }?.let { return it }
        ensureInit()
        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
        val audio = info.audioStreams
            .filter { it.deliveryMethod.name == "PROGRESSIVE_HTTP" && it.format?.mimeType?.startsWith("audio/") == true }
        // Opus first (the better codec at a given rate), then whatever is left by bit rate.
        val pick = audio.filter { it.format?.mimeType == "audio/webm" }.maxByOrNull { it.averageBitrate }
            ?: audio.maxByOrNull { it.averageBitrate }
            ?: throw IOException("no audio stream for $videoId")
        val url = pick.content
        val expiry = EXPIRE.find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000) ?: (now + FALLBACK_TTL_MS)
        return YtmStream(url, pick.format!!.mimeType, expiry).also { streams[videoId] = it }
    }

    /** The address stopped working before its time: forget it so the next [stream] asks again. */
    fun invalidate(videoId: String) {
        streams.remove(videoId)
    }

    private fun videoIdOf(url: String): String? = VIDEO_ID.find(url)?.groupValues?.get(1)

    private object Http : Downloader() {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

        override fun execute(request: Request): Response {
            val connection = URL(request.url()).openConnection() as HttpURLConnection
            connection.requestMethod = request.httpMethod()
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            request.headers().forEach { (name, values) -> values.forEach { connection.addRequestProperty(name, it) } }
            request.dataToSend()?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code == 429) throw ReCaptchaException("rate limited", request.url())
            val body = (if (code < 400) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val headers = connection.headerFields.filterKeys { it != null }
            return Response(code, connection.responseMessage, headers, body, connection.url.toString())
        }
    }

    private companion object {
        val EXPIRE = Regex("[?&]expire=(\\d+)")
        val VIDEO_ID = Regex("[?&]v=([\\w-]{11})")
        const val EXPIRY_MARGIN_MS = 10 * 60_000L
        const val FALLBACK_TTL_MS = 2 * 60 * 60_000L
    }
}
