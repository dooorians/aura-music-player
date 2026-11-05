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
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
        val styles: StyleCache,
    ) {
        fun genresOf(artistKey: String, trackId: String?): Set<String> = styles.of(artistKey, trackId, artists, localGenre, tagGenres).genres
        fun stylesOf(artistKey: String, trackId: String?): TagFusion.GenreVector = styles.of(artistKey, trackId, artists, localGenre, tagGenres).vector
    }

    /**
     * The styles of each song, fused from its file's genre, the genres the tag scanner read and its artist's tags ([TagFusion]), remembered between two
     * queues. An entry is keyed by what it was built from (the artist's genre list as stored, the file's genre, the scanned genres), so a change to any
     * of them is a miss and the entry is rebuilt: nothing stale is ever read, and nothing is parsed twice while nothing changed.
     */
    private class StyleCache {
        class Styles(val source: String, val genres: Set<String>, val vector: TagFusion.GenreVector)

        private val byTrack = java.util.concurrent.ConcurrentHashMap<String, Styles>()
        private val artistGenres = java.util.concurrent.ConcurrentHashMap<String, Pair<String, List<String>>>()

        private fun artistGenresOf(meta: ArtistMetaEntity?): List<String> {
            val json = meta?.genresJson ?: return emptyList()
            artistGenres[meta.artistKey]?.takeIf { it.first == json }?.let { return it.second }
            val parsed = runCatching { JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
            artistGenres[meta.artistKey] = json to parsed
            return parsed
        }

        fun of(artistKey: String, trackId: String?, artists: Map<String, ArtistMetaEntity>, local: Map<String, String>, tags: Map<String, List<String>>): Styles {
            val meta = artists[artistKey]
            val own = trackId?.let { local[it] }
            val scanned = trackId?.let { tags[it] }.orEmpty()
            val source = "$artistKey\u0001${meta?.genresJson.orEmpty()}\u0001${own.orEmpty()}\u0001${scanned.joinToString("\u0002")}"
            val key = trackId ?: "artist:$artistKey"
            byTrack[key]?.takeIf { it.source == source }?.let { return it }
            val fromArtist = artistGenresOf(meta)
            val normalizedOwn = own?.let(GenreNormalizer::normalize)
            val genres = (fromArtist + scanned + listOfNotNull(normalizedOwn)).toSet()
            val vector = TagFusion.fuse(
                listOf(listOfNotNull(own) to TagFusion.W_FILE, scanned to TagFusion.W_SCANNED, fromArtist to TagFusion.W_ARTIST),
            )
            if (byTrack.size > MAX_ENTRIES) byTrack.clear()
            return Styles(source, genres, vector).also { byTrack[key] = it }
        }

        private companion object {
            const val MAX_ENTRIES = 50_000
        }
    }

    private val styleCache = StyleCache()

    /** [count] songs to follow [current]; the library only when offline or when [allowStreaming] is off. */
    suspend fun nextTracks(current: Track?, count: Int, allowStreaming: Boolean, rng: Random = Random.Default): List<Track> =
        withContext(Dispatchers.Default) {
            val job = coroutineContext[Job]
            // The choice is in memory and can take a while on a large library: a queue nobody waits for any more stops where it is.
            val cancel: () -> Unit = { job?.ensureActive() }
            // No library to wait for forever: an empty one (first start, no permission) leaves the queue to the streamed songs.
            val tracks = withTimeoutOrNull(LIBRARY_WAIT_MS) { library.tracks.first { it.isNotEmpty() } }.orEmpty()
            val canStream = allowStreaming && online()
            val streamCount = when {
                !canStream -> 0
                tracks.isEmpty() -> count
                else -> Math.round(count * RecommendationConfig.STREAM_SHARE).toInt()
            }
            val loaded = load(tracks, current, cancel)
            val local = pickLocal(loaded, tracks, count - streamCount, rng)
            val streamed = if (streamCount > 0) pickStreamed(loaded, tracks, streamCount, rng, exclude = local.map { it.id }.toSet()) else emptyList()
            // A stream that found nothing leaves its place to the library.
            val missing = count - local.size - streamed.size
            val more = if (missing > 0) pickLocal(loaded, tracks, missing, rng, exclude = local.map { it.id }.toSet()) else emptyList()
            // The ranker's order is kept (its best is next); the streamed songs come at even intervals among them.
            Interleave.spread(local + more, streamed)
        }

    private suspend fun load(tracks: List<Track>, current: Track?, cancel: () -> Unit): Loaded {
        val index = indexOf(tracks)
        val localGenre = localGenres.byTrackId()
        val now = System.currentTimeMillis()
        val currentKey = current?.let { index.artistKeys[it.id] ?: ArtistNames.primaryKey(it.artist) }
        class Snapshot(
            val stats: List<TrackStatsEntity>, val artists: Map<String, ArtistMetaEntity>, val similar: List<ArtistSimilarityEntity>,
            val cooc: List<Cooccurrence>, val nearBlocked: Set<String>, val tags: List<TrackTagsEntity>,
        )
        // One transaction: the same moment for every table, each table read once.
        val snap = withContext(Dispatchers.IO) {
            database.withTransaction {
                val artists = dao.allArtists()
                Snapshot(
                    dao.allStats(), artists.associateBy { it.artistKey },
                    currentKey?.let { dao.similarTo(it) }.orEmpty(), current?.let { dao.cooccurrence(it.id) }.orEmpty(),
                    // The library's artists Last.fm lists as close to a blocked one.
                    artists.filter { it.isBlocked }.flatMap { dao.similarTo(it.artistKey) }.filter { it.similarityScore >= 0.3 }.mapTo(HashSet()) { it.targetArtistKey },
                    meta.allTags(),
                )
            }
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
                cancel = cancel,
            ),
            stats = stats, artists = snap.artists, localGenre = localGenre, tagGenres = tagGenres, sound = sound, index = index, styles = styleCache,
        )
        val currentEntry = current?.let { c ->
            SmartEntry(
                c.id, currentKey.orEmpty(), c.albumId.toString(), partial.genresOf(currentKey.orEmpty(), c.id),
                stats.of(c.id, index.canon[c.id] ?: TrackIdentity.canonicalKey(c.artist, c.title)), sound[c.id]?.bpm, sound[c.id]?.energy,
                partial.stylesOf(currentKey.orEmpty(), c.id),
            )
        }
        return Loaded(
            ctx = SmartContext(
                partial.ctx.nowMs, partial.ctx.dayPart, currentEntry, partial.ctx.artistAffinity, partial.ctx.blockedArtists,
                partial.ctx.similarToCurrent, partial.ctx.cooccurrence, partial.ctx.session,
                blockedNeighbors = partial.ctx.blockedNeighbors, weekend = partial.ctx.weekend, cancel = cancel,
            ),
            stats = stats, artists = snap.artists, localGenre = localGenre, tagGenres = tagGenres, sound = sound, index = index, styles = styleCache,
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
            loaded.ctx.cancel()
            SmartEntry(
                t.id, k, t.albumId.toString(), loaded.genresOf(k, t.id), loaded.stats.of(t.id), loaded.sound[t.id]?.bpm, loaded.sound[t.id]?.energy,
                loaded.stylesOf(k, t.id),
            )
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
            SmartEntry(
                item.trackId, k, album, loaded.genresOf(k, null), loaded.stats.of(item.trackId, TrackIdentity.canonicalKey(item.artist, item.title)),
                genreVector = loaded.stylesOf(k, null),
            )
        }
        val byId = pool.associateBy { it.trackId }
        return SmartRanker.next(entries, loaded.ctx, count, rng).mapNotNull { byId[it.trackId]?.toTrack() }
    }

    private companion object {
        const val LIBRARY_WAIT_MS = 10_000L
    }
}
