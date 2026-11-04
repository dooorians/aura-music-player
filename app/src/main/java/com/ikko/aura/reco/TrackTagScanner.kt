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
                    val done = dao.marks().filter { it.readVersion == TagScanVersion.CURRENT }.mapTo(HashSet()) { it.trackId }
                    val todo = tracks.filter { !YtmLibrary.isStreamed(it) && it.id !in done }
                    todo.chunked(BATCH).forEach { batch ->
                        dao.putTags(batch.map { t -> rowOf(t, runCatching { read(t) }.getOrDefault(ParsedTags())) })
                        delay(PAUSE_MS)
                    }
                }
            } finally { mutex.unlock() }
        }
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
        // UFID: the owner, a NUL, then the identifier.
        is BinaryFrame -> if (entry.id == "UFID") {
            val at = entry.data.indexOf(0)
            if (at > 0) listOf("UFID:" + String(entry.data, 0, at) to String(entry.data, at + 1, entry.data.size - at - 1)) else emptyList()
        } else emptyList()
        else -> emptyList()
    }

    private suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { c ->
        addListener({ try { c.resume(get()) } catch (e: Exception) { c.resumeWithException(e) } }, Runnable::run)
        c.invokeOnCancellation { cancel(false) }
    }

    private companion object {
        const val BATCH = 20
        const val PAUSE_MS = 150L
    }
}
