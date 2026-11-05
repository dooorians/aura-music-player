package com.ikko.aura.source.ytm

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.ContextCompat
import com.ikko.aura.catalog.DownloadDao
import com.ikko.aura.catalog.YtmDownloadEntity
import com.ikko.aura.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

enum class DownloadState { QUEUED, DOWNLOADING, DONE, FAILED }

/** What the screens show of a song's download: its state, and how far a running one has got (0..1). */
data class DownloadEntry(val state: DownloadState, val progress: Float = 0f)

/**
 * Songs kept as files. A download is the same best audio-only stream the player would play, written whole into the
 * app's own music folder as it came (Opus in WebM, or AAC in MP4): no conversion, no tags — the catalogue holds the
 * title, artist and cover. A downloaded song plays from its file whatever the network, and the source being switched
 * off, and is a local file as far as Automix is concerned.
 *
 * A [DownloadService] keeps the process alive while the queue runs; this class holds the queue (in Room, so it
 * survives a kill) and does the work.
 */
@Singleton
class YtmDownloads @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val client: YtmClient,
    private val library: YtmLibrary,
    private val remote: com.ikko.aura.source.remote.RemoteSources,
) {
    private val running = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Every song with a download row, with its state; a running one carries its progress. */
    val entries: Flow<Map<String, DownloadEntry>> = combine(dao.observeAll(), running) { rows, progress ->
        rows.associate { row ->
            row.key to DownloadEntry(
                state = runCatching { DownloadState.valueOf(row.state) }.getOrDefault(DownloadState.FAILED),
                progress = progress[row.key] ?: if (row.state == "DONE") 1f else 0f,
            )
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default) // Rebuilt off the main thread, whoever is listening.

    val doneBytes: Flow<Long> = dao.observeAll().map { rows -> rows.filter { it.state == "DONE" }.sumOf { it.bytes } }
    val doneCount: Flow<Int> = dao.observeAll().map { rows -> rows.count { it.state == "DONE" } }

    private val directory: File
        get() = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "ytm").apply { mkdirs() }

    /** The file of a finished download, or null: not downloaded, or its file has gone. */
    suspend fun fileFor(key: String): Pair<File, String>? {
        val row = dao.get(key)?.takeIf { it.state == "DONE" } ?: return null
        val file = File(row.filePath)
        if (!file.exists() || file.length() == 0L) {
            dao.delete(listOf(key))
            return null
        }
        return file to row.mime
    }

    /** Queues these songs (those already queued or done are left as they are) and starts the service. */
    suspend fun enqueue(tracks: List<Track>) {
        val streamed = tracks.filter(YtmLibrary::isStreamed).distinctBy { it.id }
        if (streamed.isEmpty()) return
        // A downloaded song is a kept one, and one the app must be able to name.
        library.remember(streamed)
        library.keep(streamed)
        val now = System.currentTimeMillis()
        dao.insertIfAbsent(streamed.map { YtmDownloadEntity(it.id, DownloadState.QUEUED.name, "", "", 0, 0, now) })
        dao.requeueFailed(streamed.map { it.id }, now)
        start()
    }

    /** Deletes these downloads and their files; a running one is stopped. */
    suspend fun remove(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val rows = dao.all().filter { it.key in keys }
        dao.delete(keys)
        rows.forEach { row -> if (row.filePath.isNotEmpty()) File(row.filePath).delete() }
        keys.forEach { File(directory, "${stem(it)}.part").delete() }
    }

    suspend fun removeAll() = remove(dao.all().map { it.key })

    /** Picks the queue up again after the process was killed in the middle of it. */
    suspend fun resume() {
        dao.requeueInterrupted()
        if (dao.pendingCount() > 0) start()
    }

    /**
     * Refused when the app is no longer in the foreground (Android 12+): the rows stay pending, and [resume] starts
     * the queue again the next time the app is opened instead of the refusal crashing the process.
     */
    private fun start() {
        com.ikko.aura.util.ForegroundStart.attempt {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }

    suspend fun pending(): Int = dao.pendingCount()

    /** Works through the queue, one song at a time, until it is empty. [onStep] is told how many are done of how many. */
    suspend fun runQueue(onStep: (done: Int, total: Int, title: String) -> Unit) {
        var done = 0
        while (true) {
            val row = dao.nextPending() ?: return
            val total = done + dao.pendingCount()
            onStep(done, total, titleOf(row.key))
            val finished = try {
                download(row)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            // Removed while it ran: no row any more, nothing to record.
            if (dao.get(row.key) != null && !finished) {
                dao.upsert(row.copy(state = DownloadState.FAILED.name, updatedAt = System.currentTimeMillis()))
            }
            running.value = running.value - row.key
            done++
        }
    }

    private suspend fun titleOf(key: String): String = library.known().firstOrNull { it.id == key }?.title.orEmpty()

    /** Where to fetch [key] from, and its file type if the source already says it. */
    private fun resolve(key: String): Pair<String, String?> =
        if (key.startsWith("${YTM.value}:")) client.stream(key.substringAfter(':')).let { it.url to it.mimeType }
        else remote.streamFor(key) to null

    /** A name for the song's file that no source's id can break. */
    private fun stem(key: String) = key.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun extensionFor(mime: String): String = when (mime.substringBefore(';').trim().lowercase()) {
        "audio/webm" -> "webm"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4a", "audio/aac" -> "m4a"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/wav", "audio/x-wav" -> "wav"
        else -> "audio"
    }

    /** The song's stream, in `Range` chunks, appended to a `.part` file that is renamed once whole. */
    private suspend fun download(row: YtmDownloadEntity): Boolean {
        val key = row.key
        var attempts = 0
        while (true) {
            val (url, knownMime) = resolve(key)
            val part = File(directory, "${stem(key)}.part")
            dao.upsert(row.copy(state = DownloadState.DOWNLOADING.name, updatedAt = System.currentTimeMillis()))
            try {
                val fetched = fetch(url, part, key)
                if (dao.get(key) == null) { part.delete(); return true } // removed meanwhile
                val mime = knownMime ?: fetched.contentType ?: "audio/mpeg"
                val target = File(directory, "${stem(key)}.${extensionFor(mime)}")
                if (!part.renameTo(target)) throw IOException("cannot rename")
                dao.upsert(row.copy(state = DownloadState.DONE.name, filePath = target.absolutePath, mime = mime, bytes = target.length(), totalBytes = fetched.total, updatedAt = System.currentTimeMillis()))
                return true
            } catch (e: ExpiredAddress) {
                // The address was refused (403/410): asked again, twice at most.
                if (key.startsWith("${YTM.value}:")) client.invalidate(key.substringAfter(':'))
                part.delete()
                if (++attempts > 2) return false
            }
        }
    }

    private class Fetched(val total: Long, val contentType: String?)

    private class ExpiredAddress : IOException()

    private suspend fun fetch(url: String, part: File, key: String): Fetched {
        var contentType: String? = null
        var position = if (part.exists()) part.length() else 0L
        var total = -1L
        val gate = DownloadProgressGate()
        fun publish(position: Long, force: Boolean) {
            if (total > 0 && gate.shouldPublish(System.currentTimeMillis(), force)) {
                running.update { it + (key to (position.toFloat() / total).coerceIn(0f, 1f)) }
            }
        }
        RandomAccessFile(part, "rw").use { out ->
            out.seek(position)
            while (total < 0 || position < total) {
                if (dao.get(key) == null) return Fetched(total, contentType) // removed: stop
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 20_000
                    connection.setRequestProperty("User-Agent", USER_AGENT)
                    connection.setRequestProperty("Range", "bytes=$position-${position + CHUNK - 1}")
                    val code = connection.responseCode
                    if (code == 403 || code == 410) throw ExpiredAddress()
                    if (code == 416) break // past the end: it was whole
                    if (code != 200 && code != 206) throw IOException("HTTP $code")
                    contentType = contentType ?: connection.contentType
                    connection.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { total = it }
                    if (code == 200) { // The server ignored the range: it sends everything; start over with it.
                        out.setLength(0); out.seek(0); position = 0
                        total = connection.contentLengthLong
                    }
                    val buffer = ByteArray(64 * 1024)
                    connection.inputStream.use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            position += read
                            publish(position, force = false)
                        }
                    }
                    publish(position, force = true)
                    if (code == 200) break
                } finally {
                    connection.disconnect()
                }
            }
        }
        return Fetched(if (total > 0) total else position, contentType)
    }

    private companion object {
        const val CHUNK = 1024 * 1024L
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
