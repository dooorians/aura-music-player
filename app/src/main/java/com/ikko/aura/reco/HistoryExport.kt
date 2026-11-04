package com.ikko.aura.reco

import android.content.Context
import android.net.Uri
import com.ikko.aura.data.MusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray

/** One song of the library as the evaluation needs it. */
@Serializable
data class ExportTrack(val id: String, val title: String, val artist: String, val album: String, val genres: List<String> = emptyList())

/** One listen, as it was recorded. */
@Serializable
data class ExportListen(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val playedMs: Long,
    val outcome: String,
    val endedAtMs: Long,
    val origin: String = PlayOrigin.QUEUE.name,
    val context: String? = null,
)

/**
 * The listening history and the library, in a file the listener keeps or hands over: what [ReplayEvaluator] needs to judge the ranker on
 * real data. Titles and artists are the listener's own; nothing leaves the device unless they send the file.
 */
@Serializable
data class HistoryExport(val version: Int = 1, val exportedAtMs: Long, val library: List<ExportTrack>, val listens: List<ExportListen>)

/** The file format and its reading back into a replay. Pure. */
object HistoryCodec {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun encode(export: HistoryExport): String = json.encodeToString(HistoryExport.serializer(), export)

    fun decode(text: String): HistoryExport = json.decodeFromString(HistoryExport.serializer(), text)

    /**
     * The library and the events of [export] as the evaluator takes them: events in the order they happened, and songs heard but no longer
     * in the library (streams, deleted files) added to it, so that they are not silently dropped from the replay.
     */
    fun toReplay(export: HistoryExport): Pair<List<ReplayEvaluator.Track>, List<ReplayEvaluator.Event>> {
        fun track(id: String, artist: String, album: String, genres: List<String>) =
            ReplayEvaluator.Track(id, ArtistNames.primaryKey(artist), AlbumKeys.ofName(album, artist), genres.toSet())
        val known = export.library.associate { it.id to track(it.id, it.artist, it.album, it.genres) }.toMutableMap()
        val events = export.listens.sortedBy { it.endedAtMs }.map { l ->
            known.getOrPut(l.trackId) { track(l.trackId, l.artist, l.album, emptyList()) }
            ReplayEvaluator.Event(
                trackId = l.trackId, playedMs = l.playedMs, durationMs = l.durationMs, atMs = l.endedAtMs,
                transition = when (l.outcome) {
                    Outcome.COMPLETED.name -> Transition.AUTO
                    Outcome.SKIPPED.name, Outcome.SKIPPED_EARLY.name -> Transition.USER_SKIP
                    Outcome.INTERRUPTED.name -> Transition.QUEUE_REPLACED
                    else -> Transition.UNKNOWN
                },
                manual = l.origin == PlayOrigin.MANUAL.name,
                autoplay = l.origin == PlayOrigin.AUTOPLAY.name,
            )
        }
        return known.values.toList() to events
    }
}

/** Reads what the app has recorded and writes it to a file the listener chose. */
@Singleton
class HistoryExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val recoDao: RecoDao,
    private val smart: SmartDao,
    private val meta: TrackMetaDao,
    private val library: MusicRepository,
    private val localGenres: LocalGenres,
) {
    suspend fun build(): HistoryExport = withContext(Dispatchers.IO) {
        val artistGenres = smart.allArtists().associate { it.artistKey to genresOf(it.genresJson) }
        val tagged = meta.allTags().associate { it.trackId to it.genres.split('\u0000').filter(String::isNotEmpty) }
        val local = localGenres.byTrackId()
        val tracks = library.tracks.value.map { t ->
            val own = listOfNotNull(local[t.id]?.let(GenreNormalizer::normalize))
            val genres = (artistGenres[ArtistNames.primaryKey(t.artist)].orEmpty() + tagged[t.id].orEmpty() + own).distinct()
            ExportTrack(t.id, t.title, t.artist, t.album, genres)
        }
        val listens = recoDao.listensSince(0L, Int.MAX_VALUE).map { e ->
            ExportListen(e.trackId, e.title, e.artist, e.album, e.durationMs, e.playedMs, e.outcome, e.endedAtMs, e.origin, e.context)
        }
        HistoryExport(exportedAtMs = System.currentTimeMillis(), library = tracks, listens = listens)
    }

    /** Writes the history to [uri] (a file the listener picked); the number of listens written, or null when the file could not be written. */
    suspend fun writeTo(uri: Uri): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val export = build()
            val text = HistoryCodec.encode(export)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: return@runCatching null
            export.listens.size
        }.getOrNull()
    }

    private fun genresOf(json: String): List<String> =
        runCatching { JSONArray(json) }.getOrNull()?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
}
