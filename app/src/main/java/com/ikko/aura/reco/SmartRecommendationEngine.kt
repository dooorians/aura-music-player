package com.ikko.aura.reco

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.withTransaction
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.AuraDatabase
import com.ikko.aura.reco.RecoItemTracks.toTrack
import com.ikko.aura.source.ytm.YtmLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * The next songs of a queue, from what the listener plays: the library first, scored on their own taste, on what Last.fm says of
 * the artists and on the playlists they made, and a measured share of streamed songs when the network is there.
 *
 * Everything it reads is loaded in one database transaction and the choice itself is [SmartRanker], in memory, on the default
 * dispatcher: nothing here touches the main thread.
 */
@Singleton
class SmartRecommendationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AuraDatabase,
    private val dao: SmartDao,
    private val library: MusicRepository,
    private val tracker: SmartTracker,
    private val localGenres: LocalGenres,
    private val recommendations: RecommendationEngine,
    private val acoustics: AcousticBridge,
) {
    /** The library's artist keys, rebuilt only when the library itself changes. */
    private class Indexed(val source: List<Track>, val artistKeys: Map<String, String>)
    @Volatile private var indexed: Indexed? = null

    /** The key every table is written under ([SmartTracker], [LastFmEnricher]): reading under another one would miss what was learned. */
    private fun artistKeysOf(tracks: List<Track>): Map<String, String> {
        indexed?.takeIf { it.source === tracks }?.let { return it.artistKeys }
        val keys = tracks.associate { it.id to ArtistNames.primaryKey(it.artist) }
        indexed = Indexed(tracks, keys)
        return keys
    }

    private fun online(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /** [count] songs to follow [current]; the library only when offline or when [allowStreaming] is off. */
    suspend fun nextTracks(current: Track?, count: Int, allowStreaming: Boolean, rng: Random = Random.Default): List<Track> =
        withContext(Dispatchers.Default) {
            // No library to wait for forever: an empty one (first start, no permission) leaves the queue to the streamed songs.
            val tracks = withTimeoutOrNull(LIBRARY_WAIT_MS) { library.tracks.first { it.isNotEmpty() } }.orEmpty()
            val canStream = allowStreaming && online()
            val streamCount = when {
                !canStream -> 0
                tracks.isEmpty() -> count
                else -> Math.round(count * RecommendationConfig.STREAM_SHARE).toInt()
            }
            val local = pickLocal(tracks, current, count - streamCount, rng)
            val streamed = if (streamCount > 0) pickStreamed(tracks, streamCount, rng, exclude = local.map { it.id }.toSet()) else emptyList()
            // A stream that found nothing leaves its place to the library.
            val missing = count - local.size - streamed.size
            val more = if (missing > 0) pickLocal(tracks, current, missing, rng, exclude = local.map { it.id }.toSet()) else emptyList()
            (local + more + streamed).shuffled(rng)
        }

    private suspend fun pickLocal(tracks: List<Track>, current: Track?, count: Int, rng: Random, exclude: Set<String> = emptySet()): List<Track> {
        if (count <= 0 || tracks.isEmpty()) return emptyList()
        val keys = artistKeysOf(tracks)
        val localGenre = localGenres.byTrackId()
        val now = System.currentTimeMillis()
        val currentKey = current?.let { keys[it.id] ?: ArtistNames.primaryKey(it.artist) }
        class Snapshot(
            val stats: Map<String, TrackStatsEntity>, val artists: Map<String, ArtistMetaEntity>, val similar: List<ArtistSimilarityEntity>,
            val cooc: List<Cooccurrence>, val nearBlocked: Set<String>,
        )
        // One transaction: the same moment for every table.
        val snap = database.withTransaction {
            Snapshot(
                dao.allStats().associateBy { it.trackId }, dao.allArtists().associateBy { it.artistKey },
                currentKey?.let { dao.similarTo(it) }.orEmpty(), current?.let { dao.cooccurrence(it.id) }.orEmpty(),
                // The library's artists Last.fm lists as close to a blocked one.
                dao.allArtists().filter { it.isBlocked }.flatMap { dao.similarTo(it.artistKey) }.filter { it.similarityScore >= 0.3 }.mapTo(HashSet()) { it.targetArtistKey },
            )
        }
        fun genresOf(t: Track, artistKey: String): Set<String> {
            val fromArtist = snap.artists[artistKey]?.genresJson?.let { runCatching { JSONArray(it) }.getOrNull() }
                ?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val own = localGenre[t.id]?.let(GenreNormalizer::normalize)
            return (fromArtist + listOfNotNull(own)).toSet()
        }
        // Whatever the source (YouTube Music, JioSaavn, a server): the library's own files only, the rest comes from [pickStreamed].
        val refusedAlbums = recommendations.refusedAlbumKeys.first()
        val sound = acoustics.of(tracks.map { it.id })
        val entries = tracks.filter {
            it.id !in exclude && !YtmLibrary.isStreamed(it) && (refusedAlbums.isEmpty() || AlbumKeys.of(it.remoteAlbumId, it.album, it.artist).none { k -> k in refusedAlbums })
        }.map { t ->
            val k = keys[t.id].orEmpty()
            SmartEntry(t.id, k, t.albumId.toString(), genresOf(t, k), snap.stats[t.id], sound[t.id]?.bpm, sound[t.id]?.energy)
        }
        val currentEntry = current?.let { c -> entries.firstOrNull { it.trackId == c.id } ?: SmartEntry(c.id, currentKey.orEmpty(), c.albumId.toString(), genresOf(c, currentKey.orEmpty()), snap.stats[c.id]) }
        val ctx = SmartContext(
            nowMs = now, dayPart = DayPart.at(now), current = currentEntry,
            artistAffinity = snap.artists.mapValues { it.value.affinityScore },
            // Blocked under the name the listener gave ("Tyler, The Creator") and under the key the library's songs carry ("tyler").
            blockedArtists = snap.artists.filterValues { it.isBlocked }.let { b -> b.keys + b.values.map { ArtistNames.primaryKey(it.name) } },
            similarToCurrent = snap.similar.associate { it.targetArtistKey to it.similarityScore },
            cooccurrence = snap.cooc.associate { it.trackId to (it.shared + it.adjacent).toDouble() },
            session = tracker.session,
            blockedNeighbors = snap.nearBlocked,
            weekend = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).dayOfWeek.let {
                it == java.time.DayOfWeek.SATURDAY || it == java.time.DayOfWeek.SUNDAY
            },
        )
        val byId = tracks.associateBy { it.id }
        return SmartRanker.next(entries, ctx, count, rng).mapNotNull { byId[it.trackId] }
    }

    /** Songs the recommendations found (already filtered of blocked artists, homonyms and what the listener refused). */
    private suspend fun pickStreamed(library: List<Track>, count: Int, rng: Random, exclude: Set<String>): List<Track> {
        // Of artists the listener chose (the taste picker, the ones typed in) or has among the device's files, and of no one else: a song of
        // an artist they never asked for has nothing to do with them, whatever a source says of it.
        val close = HashSet<String>().apply {
            (recommendations.pickedArtists.first() + recommendations.seedArtists.first()).forEach { addAll(ArtistNames.split(it)); add(ArtistNames.primaryKey(it)) }
            library.filter { !YtmLibrary.isStreamed(it) }.forEach { addAll(ArtistNames.split(it.artist)); add(ArtistNames.primaryKey(it.artist)) }
            remove("")
        }
        val items = recommendations.items.first().values.flatten()
            .filter { it.trackId !in exclude && it.sourceId != "local" && it.credits.take(2).any { c -> c in close } }
        val recent = tracker.session.recentTrackIds()
        val owned = library.mapTo(HashSet()) { TrackIdentity.key(it.title, it.artist) }
        return items.filter { it.trackId !in recent && TrackIdentity.key(it.title, it.artist) !in owned }
            .distinctBy { it.trackId }.shuffled(rng).take(count).map { it.toTrack() }
    }

    private companion object {
        const val LIBRARY_WAIT_MS = 10_000L
    }
}
