package com.ikko.aura.reco

import kotlin.math.exp
import kotlin.random.Random

/** A song of the library as the ranker sees it: no Android types, so the whole choice is tested on the JVM. */
data class SmartEntry(
    val trackId: String,
    val artistKey: String,
    val albumKey: String,
    val genres: Set<String>,
    val stats: TrackStatsEntity?,
    /** Tempo and mean energy from the Automix analysis, when the song has been analysed. */
    val bpm: Double? = null,
    val energy: Double? = null,
)

/** Everything a choice depends on, loaded in memory once. */
class SmartContext(
    val nowMs: Long,
    val dayPart: DayPart,
    val current: SmartEntry?,
    /** Artist key → affinity, from [ArtistMetaEntity]. */
    val artistAffinity: Map<String, Double>,
    val blockedArtists: Set<String>,
    /** Last.fm's graph for the current artist: target artist key → 0..1. */
    val similarToCurrent: Map<String, Double>,
    /** Co-occurrence with the current song in the local playlists: song id → raw count (shared + adjacent). */
    val cooccurrence: Map<String, Double>,
    val session: SessionContext,
    /** Keys of the artists close to a blocked one (the graph of the library's artists). */
    val blockedNeighbors: Set<String> = emptySet(),
    val config: RecommendationConfig = RecommendationConfig,
    /** Whether "now" is a Saturday or a Sunday. */
    val weekend: Boolean = false,
)

/** The four phases of the choice of the next song. Pure and deterministic for the same [Random]. */
object SmartRanker {

    // --- phase 1 ---

    /**
     * Songs that can never be played next (disliked, skipped for good, blocked) are out; then, in turn, what was played lately
     * (a day, the session, the last artists). When that leaves nothing, the recent-play rules give way one by one, never the first.
     */
    fun hardFilter(all: List<SmartEntry>, ctx: SmartContext): List<SmartEntry> {
        val never = all.filter { e ->
            val s = e.stats
            !(s?.isDisliked == true) && !exiled(s, ctx.nowMs) && e.artistKey !in ctx.blockedArtists
        }
        val recentIds = ctx.session.recentTrackIds()
        val lastArtists = ctx.session.lastArtists()
        val artistCount = all.map { it.artistKey }.distinct().size
        val useArtistRule = artistCount >= RecommendationConfig.MIN_ARTISTS_FOR_ARTIST_FILTER
        fun rested(e: SmartEntry) = !ctx.session.isCoolingDown(e.trackId, ctx.nowMs)
        fun notRecentlyPlayed(e: SmartEntry) = (e.stats?.lastPlayedTimestamp ?: 0L).let { it == 0L || ctx.nowMs - it > RecommendationConfig.RECENT_PLAY_MS }
        val strict = never.filter { it.trackId != ctx.current?.trackId && rested(it) && notRecentlyPlayed(it) && it.trackId !in recentIds && (!useArtistRule || it.artistKey !in lastArtists) }
        if (strict.isNotEmpty()) return strict
        val noArtistRule = never.filter { it.trackId != ctx.current?.trackId && rested(it) && notRecentlyPlayed(it) && it.trackId !in recentIds }
        if (noArtistRule.isNotEmpty()) return noArtistRule
        val noDayRule = never.filter { it.trackId != ctx.current?.trackId && rested(it) && it.trackId !in recentIds }
        if (noDayRule.isNotEmpty()) return noDayRule
        return never.filter { it.trackId != ctx.current?.trackId }
    }

    /** Skipped for good, but only for a while: after [RecommendationConfig.SKIP_EXILE_MS] without hearing it, the song gets another chance. */
    private fun exiled(s: TrackStatsEntity?, nowMs: Long): Boolean =
        s != null && s.consecutiveSkips >= RecommendationConfig.MAX_CONSECUTIVE_SKIPS &&
            (s.lastPlayedTimestamp == 0L || nowMs - s.lastPlayedTimestamp < RecommendationConfig.SKIP_EXILE_MS)

    /** Completed share of the plays, pulled toward a typical listener's with a few imaginary plays: 1 of 1 is not 100 %. */
    fun smoothedCompletion(s: TrackStatsEntity?): Double {
        val c = RecommendationConfig
        return ((s?.completedCount ?: 0) + c.COMPLETION_PRIOR * c.COMPLETION_PRIOR_PLAYS) / ((s?.playCount ?: 0) + c.COMPLETION_PRIOR_PLAYS)
    }

    /** How far a habit is above (or below) chance: [here] of [total] against the share [prior] expected, shrunk by [RecommendationConfig.SMOOTHING_PLAYS]. */
    fun habitLift(here: Int, total: Int, prior: Double): Double {
        if (total == 0) return 0.0
        val k = RecommendationConfig.SMOOTHING_PLAYS
        return (here + k * prior) / (total + k) - prior
    }

    // --- phase 2 ---

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        val x = a.filterNot { GenreNormalizer.isGeneric(it) }.map { it.lowercase() }.toSet()
        val y = b.filterNot { GenreNormalizer.isGeneric(it) }.map { it.lowercase() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    /** 0..1: how ready a song is to be heard again; a song never heard is fully available. */
    fun availability(lastPlayedMs: Long, nowMs: Long): Double {
        if (lastPlayedMs <= 0L) return 1.0
        val hours = ((nowMs - lastPlayedMs) / 3_600_000.0).coerceAtLeast(0.0)
        return 1.0 - exp(-hours / RecommendationConfig.AVAILABILITY_HOURS)
    }

    class Scored(val entry: SmartEntry, val score: Double, val similarity: Double, val cooc: Double)

    fun score(entries: List<SmartEntry>, ctx: SmartContext): List<Scored> {
        val maxCooc = ctx.cooccurrence.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val albumAffinity = entries.groupBy { it.albumKey }.mapValues { (_, g) -> g.map { it.stats?.affinityScore ?: 0.0 }.average() }
        val c = RecommendationConfig
        return entries.map { e ->
            val s = e.stats
            val playCount = s?.playCount ?: 0
            val completion = smoothedCompletion(s)
            val like = if (s?.isLiked == true) c.LIKE_BONUS else 0.0
            val trackPart = (((s?.affinityScore ?: 0.0) + like) * (0.5 + completion)) * c.W_TRACK
            val artistPart = ((ctx.artistAffinity[e.artistKey] ?: 0.0) * c.ARTIST_PART + (albumAffinity[e.albumKey] ?: 0.0) * c.ALBUM_PART) * c.W_ARTIST_ALBUM
            // Affinity is what the listener loves for good; availability is how long it has been since they heard it. A favourite
            // just heard keeps only part of its score until the hours bring it back, so the same few never loop.
            val availability = availability(s?.lastPlayedTimestamp ?: 0L, ctx.nowMs)
            val longTerm = (trackPart + artistPart).let { if (it > 0) it * (c.MIN_AVAILABILITY + (1 - c.MIN_AVAILABILITY) * availability) else it }

            val sim = when {
                ctx.current == null -> 0.0
                e.artistKey == ctx.current.artistKey -> 0.6
                else -> ctx.similarToCurrent[e.artistKey] ?: 0.0
            }
            val cooc = ((ctx.cooccurrence[e.trackId] ?: 0.0) / maxCooc).coerceIn(0.0, 1.0)
            val jac = ctx.current?.let { jaccard(it.genres, e.genres) } ?: 0.0
            val contextPart = (sim * c.CTX_LASTFM + cooc * c.CTX_PLAYLIST + jac * c.CTX_GENRE) * c.W_CONTEXT

            val total = (s?.playsMorning ?: 0) + (s?.playsAfternoon ?: 0) + (s?.playsEvening ?: 0) + (s?.playsNight ?: 0)
            val here = when (ctx.dayPart) {
                DayPart.MORNING -> s?.playsMorning; DayPart.AFTERNOON -> s?.playsAfternoon
                DayPart.EVENING -> s?.playsEvening; DayPart.NIGHT -> s?.playsNight
            } ?: 0
            val hourPart = habitLift(here, total, 0.25) * c.W_HOUR

            val weekdayPlays = s?.playsWeekday ?: 0
            val weekendPlays = s?.playsWeekend ?: 0
            val weekTotal = weekdayPlays + weekendPlays
            val weekHere = if (ctx.weekend) weekendPlays else weekdayPlays
            val weekPart = habitLift(weekHere, weekTotal, if (ctx.weekend) 1.0 - c.WEEKDAY_SHARE else c.WEEKDAY_SHARE) * c.W_WEEK

            val lastPlayed = s?.lastPlayedTimestamp ?: 0L
            var bonus = 0.0
            if ((s?.isLiked == true || completion > c.REDISCOVERY_COMPLETION) && lastPlayed > 0 && ctx.nowMs - lastPlayed > c.REDISCOVERY_AFTER_MS) bonus += c.REDISCOVERY_BONUS
            val added = s?.addedTimestamp ?: 0L
            if (playCount < c.NEW_MAX_PLAYS && added > 0 && ctx.nowMs - added < c.NEW_WITHIN_MS) bonus += c.NEW_BONUS

            var penalty = 0.0
            if (lastPlayed > 0) {
                val days = (ctx.nowMs - lastPlayed) / 86_400_000.0
                if (days < c.RECENT_PENALTY_DAYS) penalty += (c.RECENT_PENALTY_DAYS - days) * c.RECENT_PENALTY_PER_DAY
            }
            if (e.genres.any { it.lowercase() in ctx.session.penalizedGenres() }) penalty += c.SESSION_GENRE_PENALTY
            if (e.artistKey in ctx.blockedNeighbors) penalty += c.BLOCKED_NEIGHBOR_PENALTY
            penalty += ctx.session.artistCooldownPenalty(e.artistKey)
            penalty += ctx.session.fastSkipPenalty(e.artistKey, ctx.nowMs)
            penalty += ctx.session.coherencePenalty(e.bpm, e.energy)

            Scored(e, longTerm + contextPart + hourPart + weekPart + bonus - penalty, sim, cooc)
        }
    }

    // --- phase 3 & 4 ---

    /** The pools the 70 / 20 / 10 distribution draws from. */
    class Pools(val exploit: List<Scored>, val explore: List<Scored>, val gems: List<Scored>)

    fun pools(scored: List<Scored>, ctx: SmartContext): Pools {
        val c = RecommendationConfig
        val sorted = scored.sortedByDescending { it.score }
        val exploit = sorted.filter { it.entry.stats?.let(::lowEngagement) != true }.take(c.POOL_SIZE)
        // Close to what is playing (by Last.fm or by the playlists: the top fifth of those that are close at all), yet little played.
        val closeness = scored.map { maxOf(it.similarity, it.cooc) }.filter { it > 0.0 }.sortedDescending()
        val threshold = closeness.getOrNull(((closeness.size * c.EXPLORE_TOP_SHARE).toInt() - 1).coerceAtLeast(0))
        // No graph to say what is close (no Last.fm data, no playlists yet): the exploration still happens, among what is little played.
        val explore = if (threshold == null) sorted.filter { (it.entry.stats?.playCount ?: 0) < c.EXPLORE_MAX_PLAYS }.take(c.POOL_SIZE) else sorted.filter {
            maxOf(it.similarity, it.cooc) >= threshold && (it.entry.stats?.playCount ?: 0) < c.EXPLORE_MAX_PLAYS
        }.take(c.POOL_SIZE)
        val gems = sorted.filter {
            val s = it.entry.stats
            s != null && (s.isLiked || s.affinityScore >= c.GEM_MIN_AFFINITY) && s.lastPlayedTimestamp > 0 && ctx.nowMs - s.lastPlayedTimestamp > c.GEM_AFTER_MS
        }.take(c.POOL_SIZE)
        return Pools(exploit, explore, gems)
    }

    /** One song drawn from [pool], each weighted by the softmax of its score. */
    fun draw(pool: List<Scored>, rng: Random, temperature: Double = RecommendationConfig.TEMPERATURE): Scored? {
        val top = pool.take(RecommendationConfig.POOL_SIZE)
        if (top.isEmpty()) return null
        val best = top.maxOf { it.score }
        val weights = top.map { exp((it.score - best) / temperature) }
        var roll = rng.nextDouble() * weights.sum()
        for ((i, w) in weights.withIndex()) { roll -= w; if (roll <= 0) return top[i] }
        return top.last()
    }

    /** The next [count] songs, each by its own draw (the one before it is out of the next one's pool and counts as played). */
    fun next(all: List<SmartEntry>, ctx: SmartContext, count: Int, rng: Random = Random.Default): List<SmartEntry> {
        var candidates = hardFilter(all, ctx)
        val out = ArrayList<SmartEntry>()
        repeat(count) {
            if (candidates.isEmpty()) return out
            val scored = score(candidates, ctx)
            val pools = pools(scored, ctx)
            val roll = rng.nextDouble()
            val pool = when {
                roll < RecommendationConfig.SHARE_EXPLOIT -> pools.exploit
                roll < RecommendationConfig.SHARE_EXPLOIT + RecommendationConfig.SHARE_EXPLORE -> pools.explore
                else -> pools.gems
            }.ifEmpty { pools.exploit }
            val pick = draw(pool, rng)?.entry ?: return out
            out += pick
            // Not the same artist twice in a row either: what was just picked leaves the list with its artist if others remain.
            val rest = candidates.filter { it.trackId != pick.trackId }
            candidates = rest.filter { it.artistKey != pick.artistKey }.ifEmpty { rest }
            // One genre must not take the queue over: once it exceeds its share, it is set aside for the rest of the draw.
            candidates = capGenre(candidates, out)
        }
        return out
    }
}

/** Removes the most-played genre of [out] from [candidates] once it exceeds its allowed share; nothing when it would empty the pool. */
private fun capGenre(candidates: List<SmartEntry>, out: List<SmartEntry>): List<SmartEntry> {
    if (out.size < 3) return candidates
    val counts = HashMap<String, Int>()
    for (e in out) for (g in e.genres) if (g.isNotBlank()) counts.merge(g.lowercase(), 1, Int::plus)
    val dominant = counts.maxByOrNull { it.value } ?: return candidates
    if (dominant.value.toDouble() / out.size <= RecommendationConfig.MAX_GENRE_SHARE) return candidates
    val blocked = dominant.key
    return candidates.filter { e -> e.genres.none { it.lowercase() == blocked } }.ifEmpty { candidates }
}

/** A song played enough to judge but never finished, and not liked: its engagement says it is not what the listener wants. */
private fun lowEngagement(s: TrackStatsEntity): Boolean =
    !s.isLiked && s.playCount >= RecommendationConfig.LOW_ENGAGEMENT_PLAYS &&
        (s.completedCount.toDouble() / maxOf(1, s.playCount)) < RecommendationConfig.LOW_ENGAGEMENT_COMPLETION
