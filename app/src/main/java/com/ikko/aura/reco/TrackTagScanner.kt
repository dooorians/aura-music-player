package com.ikko.aura.reco

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.inspector.MetadataRetriever
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.google.common.util.concurrent.ListenableFuture
import com.ikko.aura.data.model.Track
import com.ikko.aura.source.ytm.YtmLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import com.ikko.aura.util.mapBounded
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads the tags of the device's files once, in the background and a few at a time: identifiers (MusicBrainz, ISRC), ReplayGain, a tagged
 * tempo, every genre, the song's first year. A file that cannot be read is recorded as empty so it is not tried at every launch; a newer
 * [TagScanVersion] reads everything again. Nothing here is on the playing path, and no file is ever written.
 */
@Singleton
class TrackTagScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: TrackMetaDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun scan(tracks: List<Track>) {
        scope.launch {
            if (!mutex.tryLock()) return@launch
            try {
                runCatching {
                    val marks = dao.marks()
                    val local = tracks.filter { !YtmLibrary.isStreamed(it) }
                    // The rows of files that are gone: nothing deleted them before, so the table only grew. Never on an
                    // empty scan, which is a MediaStore hiccup or a missing permission far more often than an empty library.
                    if (local.isNotEmpty()) {
                        val present = local.mapTo(HashSet(local.size)) { it.id }
                        marks.map { it.trackId }.filter { it !in present }.chunked(SQL_IN_CHUNK).forEach { dao.forgetTags(it) }
                    }
                    val done = marks.filter { it.readVersion == TagScanVersion.CURRENT }.mapTo(HashSet()) { it.trackId }
                    val todo = local.filter { it.id !in done }
                    todo.chunked(BATCH).forEach { batch ->
                        // A few files at a time: each read waits on the disk far more than on the CPU.
                        val rows = batch.mapBounded(PARALLEL_READS) { t -> rowOf(t, readSafely(t)) }
                        dao.putTags(rows)
                        delay(PAUSE_MS)
                    }
                }
            } finally { mutex.unlock() }
        }
    }

    /**
     * The file's tags, or nothing: a file that is corrupt, unreadable, gone, or that keeps the parser busy past
     * [READ_TIMEOUT_MS] is recorded as having none, so it is not tried again at every launch.
     */
    private suspend fun readSafely(t: Track): ParsedTags = try {
        withTimeoutOrNull(READ_TIMEOUT_MS) { read(t) } ?: ParsedTags()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        // OutOfMemoryError on a huge embedded picture included: one bad file must not end the scan.
        ParsedTags()
    }

    private fun rowOf(t: Track, p: ParsedTags) = TrackTagsEntity(
        trackId = t.id, readVersion = TagScanVersion.CURRENT, mbid = p.mbid, isrc = p.isrc, replayGainDb = p.replayGainDb,
        bpm = p.bpm, genres = p.genres.joinToString("\u0000"), originalYear = p.originalYear,
    )

    private suspend fun read(t: Track): ParsedTags {
        val groups = MetadataRetriever.Builder(context, MediaItem.fromUri(t.contentUri)).build().use { it.retrieveTrackGroups().awaitResult() }
        val pairs = ArrayList<Pair<String, String>>()
        for (g in 0 until groups.length) {
            val group = groups.get(g)
            for (f in 0 until group.length) {
                val metadata = group.getFormat(f).metadata ?: continue
                for (i in 0 until metadata.length()) pairs += pairsOf(metadata.get(i))
            }
        }
        return TagMap.parse(pairs)
    }

    private fun pairsOf(entry: Metadata.Entry): List<Pair<String, String>> = when (entry) {
        is TextInformationFrame -> entry.values.map { v -> (if (entry.id == "TXXX") entry.description.orEmpty() else entry.id) to v }
        is VorbisComment -> listOf(entry.key to entry.value)
        is InternalFrame -> listOf(entry.description to entry.text)
        is BinaryFrame -> if (entry.id == "UFID") listOfNotNull(ufidPair(entry.data)) else emptyList()
        else -> emptyList()
    }

    private suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { c ->
        addListener({ try { c.resume(get()) } catch (e: Exception) { c.resumeWithException(e) } }, Runnable::run)
        c.invokeOnCancellation { cancel(false) }
    }

    internal companion object {
        const val BATCH = 20
        const val PAUSE_MS = 150L
        const val PARALLEL_READS = 3
        const val READ_TIMEOUT_MS = 15_000L
        const val SQL_IN_CHUNK = 500

        /**
         * An ID3 UFID frame: the owner in ISO-8859-1, a NUL, then the identifier (bytes, in practice ASCII, at most 64).
         * Read with explicit charsets rather than the platform's, and with any padding NULs dropped.
         */
        fun ufidPair(data: ByteArray): Pair<String, String>? {
            val at = data.indexOf(0)
            if (at <= 0) return null
            val owner = String(data, 0, at, Charsets.ISO_8859_1).trim()
            val id = String(data, at + 1, data.size - at - 1, Charsets.ISO_8859_1).trim { it.isWhitespace() || it == '\u0000' }
            if (owner.isEmpty() || id.isEmpty()) return null
            return "UFID:$owner" to id
        }
    }
}
