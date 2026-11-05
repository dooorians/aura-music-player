package com.ikko.aura.reco

import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.model.Track
import com.ikko.aura.lastfm.LastFm
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/** The pure part of the first fill from Last.fm: which of the library's songs the account's scrobbles name, and with what score. */
object ScrobbleMatcher {
    class Seed(val trackId: String, val affinity: Double, val liked: Boolean)

    /** A song played [plays] times starts with log-scaled affinity (1 play ≈ 1, 100 plays ≈ 14), capped, and a loved one starts liked. */
    fun trackAffinity(plays: Int): Double = min(20.0, 3.0 * ln(1.0 + plays.coerceAtLeast(0)) + 1.0)

    fun artistAffinity(plays: Int): Double = min(40.0, 4.0 * ln(1.0 + plays.coerceAtLeast(0) / 5.0))

    fun match(library: List<Track>, topTracks: List<Triple<String, String, Int>>, loved: List<Pair<String, String>>): List<Seed> {
        val byKey = HashMap<String, MutableList<Track>>()
        for (t in library) byKey.getOrPut(TrackIdentity.key(t.title, t.artist)) { ArrayList() } += t
        val out = HashMap<String, Seed>()
        for ((title, artist, plays) in topTracks) {
            byKey[TrackIdentity.key(title, artist)]?.forEach { t -> out[t.id] = Seed(t.id, trackAffinity(plays), false) }
        }
        for ((title, artist) in loved) {
            byKey[TrackIdentity.key(title, artist)]?.forEach { t ->
                val before = out[t.id]
                out[t.id] = Seed(t.id, before?.affinity ?: trackAffinity(1), true)
            }
        }
        return out.values.toList()
    }
}

/**
 * Last.fm's knowledge of the library's artists, kept on the device: the tags that say what an artist sounds like, the graph of
 * which artists of the library are close to which, and a first fill of the listener's scores from their scrobbles. All lazy and in
 * the background; nothing here is on the playing path.
 */
@Singleton
class LastFmEnricher @Inject constructor(
    private val dao: SmartDao,
    private val lastFm: LastFm,
    private val library: MusicRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val inFlight = HashSet<String>()
    @Volatile private var bootstrapped = false
    private companion object { const val LIBRARY_ARTISTS = 80 }

    /** The library's artists under the key everything else is written under (see [ArtistNames.primaryKey]), with a name to ask Last.fm about. */
    private fun libraryArtistKeys(): Map<String, String> =
        library.tracks.value.associate { ArtistNames.primaryKey(it.artist) to ArtistNames.primary(it.artist) }

    /** Asks Last.fm about an artist heard just now, when what is kept of it is older than 30 days. */
    fun listened(artistName: String) {
        val key = ArtistNames.primaryKey(artistName)
        if (key.isEmpty()) return
        scope.launch {
            runCatching {
                if (!synchronized(inFlight) { inFlight.add(key) }) return@launch
                try {
                    val meta = dao.artist(key)
                    if (meta != null && System.currentTimeMillis() - meta.lastFmFetchedTimestamp < RecommendationConfig.LASTFM_TTL_MS) return@launch
                    enrich(key, meta?.name ?: artistName)
                } finally { synchronized(inFlight) { inFlight.remove(key) } }
            }
        }
    }

    /** Tags cleaned of what says nothing ("seen live") and of what is no genre, then the graph restricted to the library. */
    private suspend fun enrich(key: String, name: String) {
        // A credit line ("A & B") is asked about as written first, then by its first artist: Last.fm knows the one and not the other.
        val alt = ArtistNames.primary(name, beforeAmpersand = true).takeIf { it != name && it.isNotBlank() }
        val tags = lastFm.tags(name) ?: alt?.let { lastFm.tags(it) } ?: return
        val genres = tags.mapNotNull { GenreNormalizer.normalize(it) }.filterNot { it.lowercase() in TasteGate.NOISE }.distinct().take(8)
        val similar = lastFm.similarScored(name).orEmpty().ifEmpty { alt?.let { lastFm.similarScored(it) }.orEmpty() }
        val local = libraryArtistKeys()
        val rows = similar.mapNotNull { (n, score) ->
            val target = ArtistNames.key(n)
            if (target != key && target in local) ArtistSimilarityEntity(key, target, score.coerceIn(0.0, 1.0)) else null
        }
        mutex.withLock {
            val before = dao.artist(key) ?: ArtistMetaEntity(key, name)
            dao.replaceArtistGraph(before.copy(genresJson = JSONArray(genres).toString(), lastFmFetchedTimestamp = System.currentTimeMillis()), rows)
        }
    }

    /** What Last.fm says of an artist's sound, as lowercase tags (kept on the device); empty when it does not know. */
    suspend fun tagsOf(name: String): Set<String> =
        lastFm.tags(name).orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    @Volatile private var libraryDone = false

    /**
     * Fetches, for the library's artists that have nothing kept yet (or nothing recent), what Last.fm says of their sound: a few at
     * a time and without hurry, once per launch. The style rows of Home are drawn from these tags.
     */
    fun enrichLibrary() {
        if (libraryDone) return
        libraryDone = true
        scope.launch {
            runCatching {
                val tracks = library.tracks.first { it.isNotEmpty() }
                val names = tracks.groupingBy { ArtistNames.primary(it.artist) }.eachCount()
                    .entries.sortedByDescending { it.value }.map { it.key }.filter { !it.startsWith("Artiste inconnu") }.take(LIBRARY_ARTISTS)
                for (name in names) {
                    val key = ArtistNames.primaryKey(name)
                    val meta = dao.artist(key)
                    val stale = meta == null || System.currentTimeMillis() - meta.lastFmFetchedTimestamp > RecommendationConfig.LASTFM_TTL_MS
                    if (stale && synchronized(inFlight) { inFlight.add(key) }) {
                        try { enrich(key, name) } catch (e: Exception) { /* a missing answer is tried at the next launch */ }
                        finally { synchronized(inFlight) { inFlight.remove(key) } }
                        kotlinx.coroutines.delay(300)
                    }
                }
            }
        }
    }

    /**
     * The first fill, once the account is connected: the account's top artists and tracks set a starting score on what the library
     * has of them, and its loved songs start liked. Only ever raises a score (a second run changes nothing), and a song the listener
     * has since disliked stays disliked.
     */
    fun bootstrap() {
        if (bootstrapped) return
        scope.launch {
            runCatching {
                val profile = lastFm.scrobbleProfile() ?: return@launch
                bootstrapped = true
                val tracks = library.tracks.value
                if (tracks.isEmpty()) { bootstrapped = false; return@launch }
                val seeds = ScrobbleMatcher.match(tracks, profile.topTracks, profile.lovedTracks).associateBy { it.trackId }
                val byId = tracks.associateBy { it.id }
                val now = System.currentTimeMillis()
                mutex.withLock {
                    val stats = dao.allStats().associateBy { it.trackId }
                    dao.putAllStats(seeds.values.mapNotNull { seed ->
                        val t = byId[seed.trackId] ?: return@mapNotNull null
                        val old = stats[t.id] ?: TrackStatsEntity(
                            trackId = t.id, addedTimestamp = if (t.addedAtSec > 0) t.addedAtSec * 1000 else now,
                            artistKey = ArtistNames.primaryKey(t.artist), albumKey = t.albumId.toString(),
                        )
                        if (old.isDisliked) return@mapNotNull null
                        old.copy(
                            affinityScore = maxOf(old.affinityScore, seed.affinity + if (seed.liked) RecommendationConfig.LIKE else 0.0),
                            isLiked = old.isLiked || seed.liked,
                        )
                    })
                    val local = libraryArtistKeys()
                    val metas = dao.allArtists().associateBy { it.artistKey }
                    dao.putArtists(profile.topArtists.mapNotNull { (n, plays) ->
                        val k = ArtistNames.key(n)
                        if (k !in local) return@mapNotNull null
                        val old = metas[k] ?: ArtistMetaEntity(k, local[k] ?: n)
                        old.copy(affinityScore = maxOf(old.affinityScore, ScrobbleMatcher.artistAffinity(plays)))
                    })
                }
                // The artists the account plays most get their tags and graph now, a few at a time.
                profile.topArtists.take(25).forEach { (n, _) -> listened(n) }
            }
        }
    }

    /** The genres Last.fm gives an artist, when the files' own tags are missing or too wide. */
    suspend fun genresOf(artistKey: String): List<String> =
        dao.artist(artistKey)?.genresJson?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
}
