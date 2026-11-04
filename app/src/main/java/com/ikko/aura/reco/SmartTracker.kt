package com.ikko.aura.reco

import com.ikko.aura.data.model.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * Keeps [TrackStatsEntity] and [ArtistMetaEntity] up to date from what the listener does, and feeds the session's memory. Every
 * write is off the main thread and one at a time, and a failed write is dropped: the player never waits on it.
 */
@Singleton
class SmartTracker @Inject constructor(
    private val dao: SmartDao,
    private val enricher: dagger.Lazy<LastFmEnricher>,
    private val acoustics: AcousticBridge,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    val session = SessionContext()

    private fun fresh(track: Track, now: Long) = TrackStatsEntity(
        trackId = track.id,
        addedTimestamp = if (track.addedAtSec > 0) track.addedAtSec * 1000 else now,
        artistKey = ArtistNames.primaryKey(track.artist),
        albumKey = track.albumId.toString(),
    )

    private suspend fun statsOf(track: Track, now: Long) = dao.stats(track.id) ?: fresh(track, now)

    private suspend fun genresOf(artistKey: String): Set<String> =
        dao.artist(artistKey)?.genresJson?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()

    /** An artist's score moves with its songs', by [RecommendationConfig.ARTIST_ECHO]. */
    private suspend fun echo(track: Track, artistKey: String, delta: Double, blocked: Boolean? = null) {
        if (artistKey.isEmpty()) return
        val meta = dao.artist(artistKey) ?: ArtistMetaEntity(artistKey, ArtistNames.primary(track.artist))
        dao.putArtist(meta.copy(affinityScore = meta.affinityScore + delta * RecommendationConfig.ARTIST_ECHO, isBlocked = blocked ?: meta.isBlocked))
    }

    /**
     * [track] was left: how much of it was heard, how it was left, whether the listener had started it and whether the queue only
     * reached it by itself ([autoplay]: the automix, a radio).
     */
    fun listenEnded(track: Track, playedMs: Long, durationMs: Long, transition: Transition, manualStart: Boolean, autoplay: Boolean = false) {
        val now = System.currentTimeMillis()
        scope.launch {
            runCatching {
                mutex.withLock {
                    val result = AffinityScorer.apply(statsOf(track, now), ListenEvent(playedMs, if (durationMs > 0) durationMs else track.durationMs, transition, manualStart, now, autoplay))
                    dao.putStats(result.stats)
                    echo(track, result.stats.artistKey, result.delta)
                    session.recordPlay(track.id, result.stats.artistKey)
                    // What Last.fm says of this artist is fetched in the background, when it is old.
                    enricher.get().listened(track.artist)
                    if (result.delta <= RecommendationConfig.QUICK_SKIP) {
                        session.recordQuickSkip(genresOf(result.stats.artistKey))
                        // Left within seconds: this song and, a little, its artist rest for a while.
                        session.recordFastSkip(track.id, result.stats.artistKey, now)
                    }
                    // A song heard enough to say what the session sounds like.
                    if (playedMs >= RecommendationConfig.QUICK_SKIP_MS) acoustics.of(track.id)?.let { session.recordAcoustics(it.bpm, it.energy) }
                }
            }
        }
    }

    fun liked(track: Track) = write(track) { AffinityScorer.liked(it) }
    fun unliked(track: Track) = write(track) { AffinityScorer.unliked(it) }
    fun disliked(track: Track) = write(track) { AffinityScorer.disliked(it) }

    private fun write(track: Track, change: (TrackStatsEntity) -> AffinityScorer.Result) {
        val now = System.currentTimeMillis()
        scope.launch {
            runCatching {
                mutex.withLock {
                    val result = change(statsOf(track, now))
                    dao.putStats(result.stats)
                    echo(track, result.stats.artistKey, result.delta)
                }
            }
        }
    }

    /** An artist chosen in the taste picker: a strong start for its affinity, on top of what was learned. */
    fun artistPicked(name: String) {
        scope.launch {
            runCatching {
                mutex.withLock {
                    val key = ArtistNames.key(name)
                    val meta = dao.artist(key) ?: ArtistMetaEntity(key, name)
                    dao.putArtist(meta.copy(affinityScore = maxOf(meta.affinityScore, PICKED_AFFINITY), isBlocked = false))
                }
                enricher.get().listened(name)
            }
        }
    }

    /** An artist whose album the listener liked, with no song of it known: the artist weighs more for it. */
    fun artistLiked(name: String) {
        scope.launch {
            runCatching {
                mutex.withLock {
                    val key = ArtistNames.key(name)
                    val meta = dao.artist(key) ?: ArtistMetaEntity(key, name)
                    dao.putArtist(meta.copy(affinityScore = maxOf(meta.affinityScore, 0.0) + RecommendationConfig.LIKE, isBlocked = false))
                }
                enricher.get().listened(name)
            }
        }
    }

    /** An artist blocked (or let back) by the listener. */
    fun artistBlocked(name: String, blocked: Boolean) {
        scope.launch {
            runCatching {
                mutex.withLock {
                    val key = ArtistNames.key(name)
                    val meta = dao.artist(key) ?: ArtistMetaEntity(key, name)
                    dao.putArtist(meta.copy(isBlocked = blocked))
                }
                // What Last.fm lists as close to a blocked artist is learned now: it weighs against those artists too.
                if (blocked) enricher.get().listened(name)
            }
        }
    }

    private companion object { const val PICKED_AFFINITY = 30.0 }
}
