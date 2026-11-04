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
 * the artists and on the playlists they made, and a measured share of streamed songs when the network is there. The streamed ones are
 * ranked by the same [SmartRanker] (cooldown, rhythm, taste), and what a listener did with a recording counts under whichever id it
 * has, a file or a stream.
 *
 * Everything it reads is loaded in one database transaction and the choice itself is [SmartRanker], in memory, on the default
 * dispatcher: nothing here touches the main thread.
 */
@Singleton
class SmartRecommendationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AuraDatabase,
    private val dao: SmartDao,
    private val meta: TrackMetaDao,
    private val library: MusicRepository,
    private val tracker: SmartTracker,
    private val localGenres: LocalGenres,
    private val recommendations: RecommendationEngine,
    private val acoustics: AcousticBridge,
) {
    /** The library's artist keys and canonical song keys, rebuilt only when the library itself changes. */
    private class Indexed(val source: List<Track>, val artistKeys: Map<String, String>, val canon: Map<String, String>)
    @Volatile private var indexed: Indexed? = null

    /** The key every table is written under ([SmartTracker], [LastFmEnricher]): reading under another one would miss what was learned. */
    private fun indexOf(tracks: List<Track>): Indexed {
        indexed?.takeIf { it.source === tracks }?.let { return it }
        val fresh = Indexed(
            tracks,
            tracks.associate { it.id to ArtistNames.primaryKey(it.artist) },
            tracks.associate { it.id to TrackIdentity.canonicalKey(it.artist, it.title) },
        )
        indexed = fresh
        return fresh
    }

    private fun online(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /** Everything a choice depends on, read once for both the library and the streamed songs. */
    private class Loaded(
        val ctx: SmartContext,
        val stats: StatsIndex,
        val artists: Map<String, ArtistMetaEntity>,
        val localGenre: Map<String, String>,
        val tagGenres: Map<String, List<String>>,
        val sound: Map<String, Acoustic>,
        val index: Indexed,
    ) {
        fun genresOf(artistKey: String, trackId: String?): Set<String> {
            val fromArtist = artists[artistKey]?.genresJson?.let { runCatching { JSONArray(it) }.getOrNull() }
                ?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val own = trackId?.let { localGenre[it]?.let(GenreNormalizer::normalize) }
            val tagged = trackId?.let { tagGenres[it] }.orEmpty()
            return (fromArtist + tagged + listOfNotNull(own)).toSet()
        }
    }

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
            val loaded = load(tracks, current)
            val local = pickLocal(loaded, tracks, count - streamCount, rng)
            val streamed = if (streamCount > 0) pickStreamed(loaded, tracks, streamCount, rng, exclude = local.map { it.id }.toSet()) else emptyList()
            // A stream that found nothing leaves its place to the library.
            val missing = count - local.size - streamed.size
            val more = if (missing > 0) pickLocal(loaded, tracks, missing, rng, exclude = local.map { it.id }.toSet()) else emptyList()
            // The ranker's order is kept (its best is next); the streamed songs come at even intervals among them.
            Interleave.spread(local + more, streamed)
        }

    private suspend fun load(tracks: List<Track>, current: Track?): Loaded {
        val index = indexOf(tracks)
        val localGenre = localGenres.byTrackId()
        val now = System.currentTimeMillis()
        val currentKey = current?.let { index.artistKeys[it.id] ?: ArtistNames.primaryKey(it.artist) }
        class Snapshot(
            val stats: List<TrackStatsEntity>, val artists: Map<String, ArtistMetaEntity>, val similar: List<ArtistSimilarityEntity>,
            val cooc: List<Cooccurrence>, val nearBlocked: Set<String>, val tags: List<TrackTagsEntity>,
        )
        // One transaction: the same moment for every table.
        val snap = database.withTransaction {
            Snapshot(
                dao.allStats(), dao.allArtists().associateBy { it.artistKey },
                currentKey?.let { dao.similarTo(it) }.orEmpty(), current?.let { dao.cooccurrence(it.id) }.orEmpty(),
                // The library's artists Last.fm lists as close to a blocked one.
                dao.allArtists().filter { it.isBlocked }.flatMap { dao.similarTo(it.artistKey) }.filter { it.similarityScore >= 0.3 }.mapTo(HashSet()) { it.targetArtistKey },
                meta.allTags(),
            )
        }
        val stats = StatsIndex(snap.stats, index.canon)
        val tagGenres = snap.tags.filter { it.genres.isNotEmpty() }.associate { it.trackId to it.genres.split('\u0000').filter(String::isNotEmpty) }
        val sound = acoustics.of(tracks.map { it.id })
        val partial = Loaded(
            ctx = SmartContext(
                nowMs = now, dayPart = DayPart.at(now), current = null,
                // The score an artist has today: what it earned, faded by the time since it was last fed.
                artistAffinity = snap.artists.mapValues { AffinityDecay.of(it.value.affinityScore, it.value.affinityUpdatedAt, now) },
                // Blocked under the name the listener gave ("Tyler, The Creator") and under the key the library's songs carry ("tyler").
                blockedArtists = snap.artists.filterValues { it.isBlocked }.let { b -> b.keys + b.values.map { ArtistNames.primaryKey(it.name) } },
                similarToCurrent = snap.similar.associate { it.targetArtistKey to it.similarityScore },
                cooccurrence = snap.cooc.associate { it.trackId to (it.shared + it.adjacent).toDouble() },
                session = tracker.session,
                blockedNeighbors = snap.nearBlocked,
                weekend = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).dayOfWeek.let {
                    it == java.time.DayOfWeek.SATURDAY || it == java.time.DayOfWeek.SUNDAY
                },
            ),
            stats = stats, artists = snap.artists, localGenre = localGenre, tagGenres = tagGenres, sound = sound, index = index,
        )
        val currentEntry = current?.let { c ->
            SmartEntry(
                c.id, currentKey.orEmpty(), c.albumId.toString(), partial.genresOf(currentKey.orEmpty(), c.id),
                stats.of(c.id, index.canon[c.id] ?: TrackIdentity.canonicalKey(c.artist, c.title)), sound[c.id]?.bpm, sound[c.id]?.energy,
            )
        }
        return Loaded(
            ctx = SmartContext(
                partial.ctx.nowMs, partial.ctx.dayPart, currentEntry, partial.ctx.artistAffinity, partial.ctx.blockedArtists,
                partial.ctx.similarToCurrent, partial.ctx.cooccurrence, partial.ctx.session,
                blockedNeighbors = partial.ctx.blockedNeighbors, weekend = partial.ctx.weekend,
            ),
            stats = stats, artists = snap.artists, localGenre = localGenre, tagGenres = tagGenres, sound = sound, index = index,
        )
    }

    private suspend fun pickLocal(loaded: Loaded, tracks: List<Track>, count: Int, rng: Random, exclude: Set<String> = emptySet()): List<Track> {
        if (count <= 0 || tracks.isEmpty()) return emptyList()
        // Whatever the source (YouTube Music, JioSaavn, a server): the library's own files only, the rest comes from [pickStreamed].
        val refusedAlbums = recommendations.refusedAlbumKeys.first()
        val entries = tracks.filter {
            it.id !in exclude && !YtmLibrary.isStreamed(it) && (refusedAlbums.isEmpty() || AlbumKeys.of(it.remoteAlbumId, it.album, it.artist).none { k -> k in refusedAlbums })
        }.map { t ->
            val k = loaded.index.artistKeys[t.id].orEmpty()
            SmartEntry(t.id, k, t.albumId.toString(), loaded.genresOf(k, t.id), loaded.stats.of(t.id), loaded.sound[t.id]?.bpm, loaded.sound[t.id]?.energy)
        }
        val byId = tracks.associateBy { it.id }
        return SmartRanker.next(entries, loaded.ctx, count, rng).mapNotNull { byId[it.trackId] }
    }

    /** Songs the recommendations found (already filtered of blocked artists, homonyms and what the listener refused), ranked like the library's. */
    private suspend fun pickStreamed(loaded: Loaded, library: List<Track>, count: Int, rng: Random, exclude: Set<String>): List<Track> {
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
        val pool = items.filter { it.trackId !in recent && TrackIdentity.key(it.title, it.artist) !in owned }.distinctBy { it.trackId }
        if (pool.isEmpty()) return emptyList()
        val entries = pool.map { item ->
            val k = ArtistNames.primaryKey(item.artist)
            val album = item.albumRef.takeIf { it.isNotBlank() }?.let(AlbumKeys::ofRef) ?: AlbumKeys.ofName(item.album, item.artist)
            SmartEntry(item.trackId, k, album, loaded.genresOf(k, null), loaded.stats.of(item.trackId, TrackIdentity.canonicalKey(item.artist, item.title)))
        }
        val byId = pool.associateBy { it.trackId }
        return SmartRanker.next(entries, loaded.ctx, count, rng).mapNotNull { byId[it.trackId]?.toTrack() }
    }

    private companion object {
        const val LIBRARY_WAIT_MS = 10_000L
    }
}
