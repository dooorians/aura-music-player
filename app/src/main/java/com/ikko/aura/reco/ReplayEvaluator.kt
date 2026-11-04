package com.ikko.aura.reco

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Judges the ranker against a listening history instead of by feel: it replays the history in order, letting the stats and the session
 * grow exactly as they do in the app, and before each song asks "what would have been proposed next?" and where the song really
 * played came in that list. A ranker that has learned the listener puts what they really play near the top; one that has not does no better
 * than chance or than "the most played so far", the two baselines reported beside it.
 *
 * Pure (no Android, no clock), so it runs in a unit test on a history exported from a device, or on a made-up listener.
 */
object ReplayEvaluator {
    class Track(val id: String, val artistKey: String, val albumKey: String = "", val genres: Set<String> = emptySet())

    data class Event(
        val trackId: String,
        val playedMs: Long,
        val durationMs: Long,
        val atMs: Long,
        val transition: Transition = Transition.AUTO,
        val manual: Boolean = false,
        val autoplay: Boolean = false,
    )

    /**
     * Shares are 0..1 over the [steps] judged: the plays the ranker was allowed to propose. A play the hard filter rules out by design
     * (a song heard within the day, an artist heard a moment ago) cannot be predicted by a ranker made not to repeat, so it is not judged
     * but counted in [blockedShare]: the price of the no-repeat rules, which tells whether they are too strict for this listener.
     */
    data class Report(
        val steps: Int,
        val blockedShare: Double,
        val hitAt1: Double,
        val hitAt5: Double,
        val hitAt10: Double,
        /** Mean of 1 / rank: 1.0 if the song was always first, near 0 if it was always far down. */
        val meanReciprocalRank: Double,
        /** Where "the most played so far" would have put it, and chance. */
        val popularityHitAt10: Double,
        val randomHitAt10: Double,
        /** Distinct songs that appeared in a top 10, over the library: a ranker that always proposes the same few has a low one. */
        val coverageAt10: Double,
    ) {
        /** How much better than the best of the two baselines the ranker does at 10; above 1 it has learned something. */
        val liftAt10: Double get() = if (maxOf(popularityHitAt10, randomHitAt10) == 0.0) 0.0 else hitAt10 / maxOf(popularityHitAt10, randomHitAt10)
    }

    fun run(library: List<Track>, history: List<Event>, warmup: Int = 20, zone: ZoneId = ZoneOffset.UTC): Report {
        val byId = library.associateBy { it.id }
        val stats = HashMap<String, TrackStatsEntity>()
        val artistAffinity = HashMap<String, Double>()
        val session = SessionContext()
        var previous: Track? = null
        var steps = 0
        var blocked = 0
        var chance = 0.0
        var h1 = 0; var h5 = 0; var h10 = 0; var pop10 = 0
        var rr = 0.0
        val shown = HashSet<String>()
        val firstAt = history.firstOrNull()?.atMs ?: 0L

        for ((i, e) in history.withIndex()) {
            val track = byId[e.trackId] ?: continue
            if (i >= warmup && library.size > 1) {
                val entries = library.map { SmartEntry(it.id, it.artistKey, it.albumKey, it.genres, stats[it.id]) }
                val current = previous?.let { p -> entries.first { it.trackId == p.id } }
                val weekend = Instant.ofEpochMilli(e.atMs).atZone(zone).dayOfWeek.value >= 6
                val ctx = SmartContext(
                    nowMs = e.atMs, dayPart = DayPart.at(e.atMs, zone), current = current, artistAffinity = HashMap(artistAffinity),
                    blockedArtists = emptySet(), similarToCurrent = emptyMap(), cooccurrence = emptyMap(), session = session, weekend = weekend,
                )
                val ranked = SmartRanker.score(SmartRanker.hardFilter(entries, ctx), ctx).sortedByDescending { it.score }.map { it.entry.trackId }
                val rank = ranked.indexOf(e.trackId).let { if (it < 0) null else it + 1 }
                if (rank == null) {
                    blocked++
                } else {
                    steps++
                    if (rank <= 1) h1++
                    if (rank <= 5) h5++
                    if (rank <= 10) h10++
                    rr += 1.0 / rank
                    shown += ranked.take(10)
                    // The same candidates, ordered by plays so far, and by chance.
                    val popular = ranked.sortedWith(compareByDescending<String> { stats[it]?.playCount ?: 0 }.thenBy { it }).take(10)
                    if (e.trackId in popular) pop10++
                    chance += minOf(10, ranked.size).toDouble() / ranked.size
                }
            }
            // What the app does when the song is left.
            val before = stats[track.id] ?: TrackStatsEntity(track.id, addedTimestamp = firstAt, artistKey = track.artistKey, albumKey = track.albumKey)
            val result = AffinityScorer.apply(before, ListenEvent(e.playedMs, e.durationMs, e.transition, e.manual, e.atMs, e.autoplay), zone)
            stats[track.id] = result.stats
            artistAffinity.merge(track.artistKey, result.delta * RecommendationConfig.ARTIST_ECHO, Double::plus)
            session.recordPlay(track.id, track.artistKey)
            if (result.delta <= RecommendationConfig.QUICK_SKIP) {
                session.recordQuickSkip(track.genres)
                session.recordFastSkip(track.id, track.artistKey, e.atMs)
            }
            previous = track
        }
        fun share(n: Int) = if (steps == 0) 0.0 else n.toDouble() / steps
        return Report(
            steps = steps, blockedShare = if (steps + blocked == 0) 0.0 else blocked.toDouble() / (steps + blocked), hitAt1 = share(h1), hitAt5 = share(h5), hitAt10 = share(h10),
            meanReciprocalRank = if (steps == 0) 0.0 else rr / steps,
            popularityHitAt10 = share(pop10),
            randomHitAt10 = if (steps == 0) 0.0 else chance / steps,
            coverageAt10 = if (library.isEmpty()) 0.0 else shown.size.toDouble() / library.size,
        )
    }
}
