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
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
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
    /** The album page it came from, when it came from one. */
    val albumId: String? = null,
    val albumTitle: String? = null,
)

/** An album as a search lists it: [id] is the YouTube Music playlist behind it. */
data class YtmAlbumRef(val id: String, val title: String, val artist: String, val thumbnailUrl: String?)

data class YtmArtistRef(val name: String, val thumbnailUrl: String?)

/** An album and its songs, in order; every song carries the album's cover and identity. */
data class YtmAlbum(val ref: YtmAlbumRef, val songs: List<YtmSong>)

/** One page of search results, and the key to the next one if there is one. */
data class YtmPage(val songs: List<YtmSong>, val next: Page?)

/** An audio-only stream address and when it stops working. */
data class YtmStream(val url: String, val mimeType: String, val expiresAtMs: Long)

/**
 * The one door to YouTube Music: unofficial, anonymous (no account, no cookies), audio only, off unless the user turned
 * it on. Everything in `source/ytm/` can go without touching any other file but the few that register it.
 *
 * All calls block: they run on the player's loader threads or on the caller's own IO dispatcher.
 */
@Singleton
class YtmClient @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val settings: SettingsStore,
) {

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

    /** A recent answer kept for a few minutes: typing the same thing again, or coming back to a page, costs nothing. */
    private class Memo<V> {
        private class Entry<V>(val at: Long, val value: V)
        private val map = object : java.util.LinkedHashMap<String, Entry<V>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>) = size > MEMO_SIZE
        }
        @Synchronized fun get(key: String): V? =
            map[key]?.takeIf { System.currentTimeMillis() - it.at < MEMO_TTL_MS }?.value
        @Synchronized fun put(key: String, value: V): V { map[key] = Entry(System.currentTimeMillis(), value); return value }
    }

    private val songMemo = Memo<YtmPage>()
    private val albumMemo = Memo<List<YtmAlbumRef>>()
    private val artistMemo = Memo<List<YtmArtistRef>>()
    private val albumPageMemo = Memo<YtmAlbum>()

    /** Initialises the extractor and opens the connection, so the first real search does not pay for either. */
    fun warmUp() {
        if (!isEnabled()) return
        ensureInit()
        search("music")
    }

    fun searchAlbums(query: String): List<YtmAlbumRef> {
        check(isEnabled()) { "YouTube Music is off" }
        albumMemo.get(query.lowercase())?.let { return it }
        ensureInit()
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS), "")
        extractor.fetchPage()
        return albumMemo.put(query.lowercase(), extractor.initialPage.items.filterIsInstance<PlaylistInfoItem>().mapNotNull { item ->
            val id = PLAYLIST_ID.find(item.url)?.groupValues?.get(1) ?: return@mapNotNull null
            YtmAlbumRef(id, item.name.removePrefix("Album – ").removePrefix("Album - "), item.uploaderName.orEmpty(), YtmImages.sized(item.thumbnails.maxByOrNull { it.width }?.url))
        })
    }

    fun searchArtists(query: String): List<YtmArtistRef> {
        check(isEnabled()) { "YouTube Music is off" }
        artistMemo.get(query.lowercase())?.let { return it }
        ensureInit()
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS), "")
        extractor.fetchPage()
        return artistMemo.put(query.lowercase(), extractor.initialPage.items.filterIsInstance<ChannelInfoItem>().map { item ->
            YtmArtistRef(item.name.removeSuffix(" - Topic"), YtmImages.sized(item.thumbnails.maxByOrNull { it.width }?.url))
        })
    }

    /** An album's songs. Albums are short, so its first pages are all there is. */
    fun album(albumId: String): YtmAlbum {
        check(isEnabled()) { "YouTube Music is off" }
        albumPageMemo.get(albumId)?.let { return it }
        ensureInit()
        val extractor = ServiceList.YouTube.getPlaylistExtractor("https://music.youtube.com/playlist?list=$albumId")
        extractor.fetchPage()
        val title = extractor.name.removePrefix("Album – ").removePrefix("Album - ")
        val pageCover = runCatching { extractor.thumbnails.maxByOrNull { it.width }?.url }.getOrNull()
        var page: org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage<StreamInfoItem> = extractor.initialPage
        val items = ArrayList<StreamInfoItem>(page.items)
        var pages = 1
        while (page.hasNextPage() && pages < MAX_ALBUM_PAGES) {
            page = extractor.getPage(page.nextPage)
            items += page.items
            pages++
        }
        val artist = items.firstOrNull()?.uploaderName.orEmpty().removeSuffix(" - Topic")
        // The album's own page only carries a small widescreen picture; the search lists the square cover, which can
        // be asked for at any size. Found by the album's id, so a namesake cannot stand in for it.
        val cover = runCatching { searchAlbums("$title $artist").firstOrNull { it.id == albumId }?.thumbnailUrl }.getOrNull()
            ?: YtmImages.sized(pageCover)
        val songs = items.mapNotNull { item ->
            val id = videoIdOf(item.url) ?: return@mapNotNull null
            YtmSong(id, item.name, item.uploaderName.orEmpty().removeSuffix(" - Topic"), item.duration.coerceAtLeast(0) * 1000, cover, albumId, title)
        }
        return albumPageMemo.put(albumId, YtmAlbum(YtmAlbumRef(albumId, title, artist, cover), songs))
    }

    /** What an artist has, by name: their songs (the search's results whose artist is that name) and their albums. */
    fun artistSongs(name: String): List<YtmSong> {
        val key = name.lowercase()
        val first = search(name)
        val more = first.next?.let { runCatching { searchMore(name, it) }.getOrNull() }
        return (first.songs + more?.songs.orEmpty()).filter { it.artist.lowercase().contains(key) || key.contains(it.artist.lowercase()) }
    }

    fun artistAlbums(name: String): List<YtmAlbumRef> {
        val key = name.lowercase()
        return searchAlbums(name).filter { it.artist.removeSuffix(" - Topic").lowercase().let { a -> a.contains(key) || key.contains(a) } }
    }

    /** Songs only, never videos or clips. [YtmPage.next] opens the following page of the same search. */
    fun search(query: String): YtmPage {
        check(isEnabled()) { "YouTube Music is off" }
        songMemo.get(query.lowercase())?.let { return it }
        ensureInit()
        val extractor = searchExtractor(query)
        extractor.fetchPage()
        return songMemo.put(query.lowercase(), pageOf(extractor.initialPage))
    }

    fun searchMore(query: String, page: Page): YtmPage {
        check(isEnabled()) { "YouTube Music is off" }
        ensureInit()
        val extractor = searchExtractor(query)
        extractor.fetchPage()
        return pageOf(extractor.getPage(page))
    }

    private fun searchExtractor(query: String) = ServiceList.YouTube.getSearchExtractor(
        query,
        listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
        "",
    )

    private fun pageOf(page: org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage<org.schabi.newpipe.extractor.InfoItem>): YtmPage {
        val songs = page.items.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
            val id = videoIdOf(item.url) ?: return@mapNotNull null
            YtmSong(
                videoId = id,
                title = item.name,
                artist = item.uploaderName.orEmpty().removeSuffix(" - Topic"),
                durationMs = item.duration.coerceAtLeast(0) * 1000,
                thumbnailUrl = YtmImages.sized(item.thumbnails.maxByOrNull { it.width }?.url ?: item.thumbnails.firstOrNull()?.url),
            )
        }
        return YtmPage(songs, page.nextPage.takeIf { page.hasNextPage() })
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
        // Debug builds only: a file dropped in the cache directory makes the next address resolved a refused one, to
        // check the song still plays (see YtmDataSource).
        val damaged = com.ikko.aura.BuildConfig.DEBUG && java.io.File(context.cacheDir, "damage_next_address").delete()
        val served = if (damaged) url.replace(Regex("([?&](?:sig|lsig)=)"), "$1BAD") else url
        if (damaged) android.util.Log.i("YtmClient", "test: damaged address served for $videoId")
        return YtmStream(served, pick.format!!.mimeType, expiry).also { streams[videoId] = it }
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
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
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
        val PLAYLIST_ID = Regex("[?&]list=([\\w-]+)")
        const val MAX_ALBUM_PAGES = 3
        const val MEMO_SIZE = 24
        const val MEMO_TTL_MS = 5 * 60_000L
        val VIDEO_ID = Regex("[?&]v=([\\w-]{11})")
        const val EXPIRY_MARGIN_MS = 10 * 60_000L
        const val FALLBACK_TTL_MS = 2 * 60 * 60_000L
    }
}
