package com.ikko.aura.reco

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** A song of the library as the ranker sees it: no Android types, so the whole choice is tested on the JVM. */
data class SmartEntry(
    val trackId: String,
    val artistKey: String,
    val albumKey: String,
    val genres: Set<String>,
    val stats: TrackStatsEntity?,
    /** Tempo and mean energy from the Automix analysis, when the song has been analysed. Anything [Tempo.sanitize] rejects is "unknown". */
    val bpm: Double? = null,
    val energy: Double? = null,
    /** The song's styles fused from every source (see [TagFusion.fuse]); null to derive them from [genres] alone. */
    val genreVector: TagFusion.GenreVector? = null,
) {
    /** The styles the ranker compares: [genreVector] when given, else [genres] all weighing the same. Computed once per entry. */
    val styles: TagFusion.GenreVector by lazy { genreVector ?: TagFusion.uniform(genres) }

    /** [genres] lowercased, once: what the session's skipped genres and the genre cap compare against. */
    internal val lowerGenres: Set<String> by lazy { genres.mapNotNullTo(HashSet()) { g -> g.lowercase().takeIf { it.isNotBlank() } } }

    internal val tempo: Double? by lazy { Tempo.sanitize(bpm) }
    internal val loudness: Double? by lazy { energy?.takeIf { it.isFinite() && it > 0.0 } }
}

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
    /** Called every few hundred songs scored: a coroutine passes its job's `ensureActive`, so a choice nobody waits for stops. */
    val cancel: () -> Unit = {},
)

/**
 * The four phases of the choice of the next song. Pure and deterministic for the same [Random].
 *
 * Cost: [score] is one pass over the songs, everything that does not depend on the song (the session's profile, its skipped genres, the album
 * affinities, the taste of each genre) read once before it. [next] scores once and then, for each song it adds, only adjusts the part that depends on
 * the song before it (the transition), and draws from pools found by a bounded heap: O(n log k) per pick instead of a sort of the library.
 */
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
        val artistCount = all.mapTo(HashSet()) { it.artistKey }.size
        val useArtistRule = artistCount >= RecommendationConfig.MIN_ARTISTS_FOR_ARTIST_FILTER
        val currentId = ctx.current?.trackId
        fun rested(e: SmartEntry) = !ctx.session.isCoolingDown(e.trackId, ctx.nowMs)
        fun notRecentlyPlayed(e: SmartEntry) = (e.stats?.lastPlayedTimestamp ?: 0L).let { it == 0L || ctx.nowMs - it > RecommendationConfig.RECENT_PLAY_MS }
        val strict = never.filter { it.trackId != currentId && rested(it) && notRecentlyPlayed(it) && it.trackId !in recentIds && (!useArtistRule || it.artistKey !in lastArtists) }
        if (strict.isNotEmpty()) return strict
        val noArtistRule = never.filter { it.trackId != currentId && rested(it) && notRecentlyPlayed(it) && it.trackId !in recentIds }
        if (noArtistRule.isNotEmpty()) return noArtistRule
        val noDayRule = never.filter { it.trackId != currentId && rested(it) && it.trackId !in recentIds }
        if (noDayRule.isNotEmpty()) return noDayRule
        return never.filter { it.trackId != currentId }
    }

    /** Skipped for good, but only for a while: after [RecommendationConfig.SKIP_EXILE_MS] without hearing it, the song gets another chance. */
    private fun exiled(s: TrackStatsEntity?, nowMs: Long): Boolean =
        s != null && s.consecutiveSkips >= RecommendationConfig.MAX_CONSECUTIVE_SKIPS &&
            (s.lastPlayedTimestamp == 0L || nowMs - s.lastPlayedTimestamp < RecommendationConfig.SKIP_EXILE_MS)

    /** Completed share of the plays, pulled toward a typical listener's with a few imaginary plays: 1 of 1 is not 100 %. */
    fun smoothedCompletion(s: TrackStatsEntity?): Double {
        val c = RecommendationConfig
        val plays = (s?.playCount ?: 0).coerceAtLeast(0)
        val completed = (s?.completedCount ?: 0).coerceIn(0, maxOf(plays, s?.completedCount ?: 0))
        return ((completed + c.COMPLETION_PRIOR * c.COMPLETION_PRIOR_PLAYS) / (plays + c.COMPLETION_PRIOR_PLAYS)).coerceIn(0.0, 1.0)
    }

    /**
     * Share of the plays left within seconds, pulled toward [RecommendationConfig.SKIP_PRIOR] the same way: what one early skip says is little,
     * what four of five say is a lot.
     */
    fun smoothedSkipRate(s: TrackStatsEntity?): Double {
        val c = RecommendationConfig
        val plays = (s?.playCount ?: 0).coerceAtLeast(0)
        val early = (s?.earlySkipCount ?: 0).coerceIn(0, maxOf(plays, 0))
        return ((early + c.SKIP_PRIOR * c.SKIP_PRIOR_PLAYS) / (plays + c.SKIP_PRIOR_PLAYS)).coerceIn(0.0, 1.0)
    }

    /** How far a habit is above (or below) chance: [here] of [total] against the share [prior] expected, shrunk by [RecommendationConfig.SMOOTHING_PLAYS]. */
    fun habitLift(here: Int, total: Int, prior: Double): Double {
        if (total <= 0) return 0.0
        val k = RecommendationConfig.SMOOTHING_PLAYS
        return (here.coerceIn(0, total) + k * prior) / (total + k) - prior
    }

    /** A song's score as it stands today: what it earned, faded by the time since it was last played. */
    fun effectiveAffinity(s: TrackStatsEntity?, nowMs: Long): Double =
        if (s == null) 0.0 else finite(AffinityDecay.of(s.affinityScore, s.lastPlayedTimestamp, nowMs))

    // --- phase 2 ---

    /** 0..1: how ready a song is to be heard again; a song never heard is fully available. */
    fun availability(lastPlayedMs: Long, nowMs: Long): Double {
        if (lastPlayedMs <= 0L) return 1.0
        val hours = ((nowMs - lastPlayedMs) / 3_600_000.0).coerceAtLeast(0.0)
        return 1.0 - exp(-hours / RecommendationConfig.AVAILABILITY_HOURS)
    }

    /**
     * 0..1: how well a song at [nextBpm] / [nextEnergy] follows one at [prevBpm] / [prevEnergy], as a signed score contribution: positive for a
     * smooth step, negative for a jump, and exactly 0 for whatever side is unknown (a song with no analysis is never held back by it).
     */
    fun transitionScore(prevBpm: Double?, prevEnergy: Double?, nextBpm: Double?, nextEnergy: Double?): Double {
        val c = RecommendationConfig
        var t = 0.0
        Tempo.affinity(prevBpm, nextBpm)?.let { t += c.W_TRANSITION_TEMPO * (it - Tempo.NEUTRAL) }
        val a = prevEnergy?.takeIf { it.isFinite() && it > 0.0 }
        val b = nextEnergy?.takeIf { it.isFinite() && it > 0.0 }
        if (a != null && b != null) {
            val gap = abs(a - b) / maxOf(a, b)
            val z = gap / c.TRANSITION_ENERGY_SIGMA
            t += c.W_TRANSITION_ENERGY * (exp(-z * z) - 0.5)
        }
        return finite(t)
    }

    class Scored(
        val entry: SmartEntry,
        val score: Double,
        val similarity: Double,
        val cooc: Double,
        /** The part of [score] that depends on the song before it: what [next] replaces as the queue moves on. */
        val transition: Double = 0.0,
    )

    /** What does not depend on the song being scored, read once per [score]. */
    private class Frame(entries: List<SmartEntry>, val ctx: SmartContext) {
        val c = RecommendationConfig
        val maxCooc = ctx.cooccurrence.values.filter { it.isFinite() }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val albumAffinity: Map<String, Double> = HashMap<String, DoubleArray>().also { acc ->
            for (e in entries) acc.getOrPut(e.albumKey) { DoubleArray(2) }.let { it[0] += effectiveAffinity(e.stats, ctx.nowMs); it[1] += 1.0 }
        }.mapValues { (_, v) -> if (v[1] > 0) v[0] / v[1] else 0.0 }
        val penalizedGenres = ctx.session.penalizedGenres()
        val acoustic = ctx.session.acousticProfile()
        val currentStyles = ctx.current?.styles
        val totalPlays = entries.sumOf { (it.stats?.playCount ?: 0).coerceAtLeast(0).toLong() }
        val ucbNumerator = ln(1.0 + totalPlays)

        /**
         * Genre → what its songs earned on average (affinity points), shrunk toward 0 by [RecommendationConfig.COLD_GENRE_SHRINK] imaginary songs:
         * what a song never heard inherits from its styles. Built from the songs that were played, weighted by how much of each song is that genre.
         */
        val genreTaste: Map<String, Double> = run {
            val sum = HashMap<String, Double>()
            val weight = HashMap<String, Double>()
            for (e in entries) {
                val s = e.stats ?: continue
                if (s.playCount <= 0 && !s.isLiked) continue
                val earned = effectiveAffinity(s, ctx.nowMs) + if (s.isLiked) c.LIKE_BONUS else 0.0
                for ((g, w) in e.styles.weights) {
                    sum.merge(g, w * earned, Double::plus)
                    weight.merge(g, w, Double::plus)
                }
            }
            sum.mapValues { (g, s) -> s / ((weight[g] ?: 0.0) + c.COLD_GENRE_SHRINK) }
        }

        fun coldStart(e: SmartEntry): Double {
            if ((e.stats?.playCount ?: 0) > 0 || genreTaste.isEmpty()) return 0.0
            val v = e.styles
            if (v.isEmpty) return 0.0
            var num = 0.0
            var den = 0.0
            for ((g, w) in v.weights) { genreTaste[g]?.let { num += w * it }; den += w }
            if (den <= 0.0) return 0.0
            return c.W_COLD_START * (num / den).coerceIn(c.COLD_GENRE_MIN, c.COLD_GENRE_MAX) * v.confidence
        }

        /**
         * Rarely heard is uncertain only when nothing was said against it: a song played little *because* it is skipped is not unknown, it is
         * known and refused, and an exploration bonus would bring back exactly what the listener leaves. So the bonus goes to songs with no early
         * skip above the prior and no negative score, and of an artist whose standing is not negative.
         */
        fun exploration(e: SmartEntry): Double {
            if (totalPlays <= 0L) return 0.0
            val s = e.stats
            val plays = (s?.playCount ?: 0).coerceAtLeast(0)
            if (s != null && plays > 0) {
                if (smoothedSkipRate(s) > c.SKIP_PRIOR || effectiveAffinity(s, ctx.nowMs) < 0.0 || s.consecutiveSkips > 0) return 0.0
            }
            if ((ctx.artistAffinity[e.artistKey] ?: 0.0) < 0.0) return 0.0
            return minOf(c.UCB_MAX, c.W_UCB * sqrt(ucbNumerator / (1.0 + plays)))
        }

        fun score(e: SmartEntry): Scored {
            val s = e.stats
            val playCount = s?.playCount ?: 0
            val completion = smoothedCompletion(s)
            val like = if (s?.isLiked == true) c.LIKE_BONUS else 0.0
            val trackPart = ((effectiveAffinity(s, ctx.nowMs) + like) * (0.5 + completion)) * c.W_TRACK
            val artistPart = (finite(ctx.artistAffinity[e.artistKey] ?: 0.0) * c.ARTIST_PART + (albumAffinity[e.albumKey] ?: 0.0) * c.ALBUM_PART) * c.W_ARTIST_ALBUM
            // Affinity is what the listener loves for good; availability is how long it has been since they heard it. A favourite
            // just heard keeps only part of its score until the hours bring it back, so the same few never loop.
            val availability = availability(s?.lastPlayedTimestamp ?: 0L, ctx.nowMs)
            val longTerm = (trackPart + artistPart).let { if (it > 0) it * (c.MIN_AVAILABILITY + (1 - c.MIN_AVAILABILITY) * availability) else it }

            val current = ctx.current
            val sim = when {
                current == null -> 0.0
                e.artistKey == current.artistKey -> 0.6
                else -> finite(ctx.similarToCurrent[e.artistKey] ?: 0.0).coerceIn(0.0, 1.0)
            }
            val cooc = finite((ctx.cooccurrence[e.trackId] ?: 0.0) / maxCooc).coerceIn(0.0, 1.0)
            val style = currentStyles?.let { TagFusion.similarity(it, e.styles) } ?: 0.0
            val contextPart = (sim * c.CTX_LASTFM + cooc * c.CTX_PLAYLIST + style * c.CTX_GENRE) * c.W_CONTEXT

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
            bonus += coldStart(e)
            bonus += exploration(e)

            var penalty = 0.0
            if (lastPlayed > 0) {
                val days = (ctx.nowMs - lastPlayed) / 86_400_000.0
                if (days < c.RECENT_PENALTY_DAYS) penalty += (c.RECENT_PENALTY_DAYS - days.coerceAtLeast(0.0)) * c.RECENT_PENALTY_PER_DAY
            }
            if (s != null && s.playCount > 0) penalty += c.W_SKIP_RATE * (smoothedSkipRate(s) - c.SKIP_PRIOR).coerceAtLeast(0.0)
            if (penalizedGenres.isNotEmpty() && e.lowerGenres.any { it in penalizedGenres }) penalty += c.SESSION_GENRE_PENALTY
            if (e.artistKey in ctx.blockedNeighbors) penalty += c.BLOCKED_NEIGHBOR_PENALTY
            penalty += ctx.session.artistCooldownPenalty(e.artistKey)
            penalty += ctx.session.fastSkipPenalty(e.artistKey, ctx.nowMs)
            penalty += SessionContext.coherencePenaltyOf(acoustic, e.bpm, e.energy)

            val transition = if (current == null) 0.0 else transitionScore(current.tempo, current.loudness, e.tempo, e.loudness)
            val score = finite(longTerm) + finite(contextPart) + finite(hourPart) + finite(weekPart) + finite(bonus) - finite(penalty) + transition
            return Scored(e, score, sim, cooc, transition)
        }
    }

    fun score(entries: List<SmartEntry>, ctx: SmartContext): List<Scored> {
        if (entries.isEmpty()) return emptyList()
        val frame = Frame(entries, ctx)
        val out = ArrayList<Scored>(entries.size)
        for ((i, e) in entries.withIndex()) {
            if (i and CANCEL_EVERY_MASK == 0) ctx.cancel()
            out += frame.score(e)
        }
        return out
    }

    // --- phase 3 & 4 ---

    /** The pools the 70 / 20 / 10 distribution draws from. */
    class Pools(val exploit: List<Scored>, val explore: List<Scored>, val gems: List<Scored>)

    /** The closeness (Last.fm or playlists) a song must reach to be in the explore pool: the top fifth of those that are close at all. Null: no graph. */
    internal fun exploreThreshold(scored: List<Scored>): Double? {
        val closeness = scored.asSequence().map { maxOf(it.similarity, it.cooc) }.filter { it > 0.0 }.toMutableList()
        if (closeness.isEmpty()) return null
        closeness.sortDescending()
        return closeness[((closeness.size * RecommendationConfig.EXPLORE_TOP_SHARE).toInt() - 1).coerceIn(0, closeness.size - 1)]
    }

    fun pools(scored: List<Scored>, ctx: SmartContext): Pools = pools(scored, ctx, exploreThreshold(scored))

    internal fun pools(scored: List<Scored>, ctx: SmartContext, threshold: Double?): Pools {
        val c = RecommendationConfig
        val exploit = topK(scored, c.POOL_SIZE) { it.entry.stats?.let(::lowEngagement) != true }
        // Close to what is playing, yet little played. No graph to say what is close (no Last.fm data, no playlists yet): the exploration still
        // happens, among what is little played.
        val explore = topK(scored, c.POOL_SIZE) {
            (it.entry.stats?.playCount ?: 0) < c.EXPLORE_MAX_PLAYS && (threshold == null || maxOf(it.similarity, it.cooc) >= threshold)
        }
        val gems = topK(scored, c.POOL_SIZE) {
            val s = it.entry.stats
            s != null && (s.isLiked || s.affinityScore >= c.GEM_MIN_AFFINITY) && s.lastPlayedTimestamp > 0 && ctx.nowMs - s.lastPlayedTimestamp > c.GEM_AFTER_MS
        }
        return Pools(exploit, explore, gems)
    }

    /**
     * The [k] best of [list] that pass [keep], best first, ties in the order of [list]: a bounded heap, O(n log k), where a sort of everything
     * would be O(n log n) for fifteen songs.
     */
    private inline fun topK(list: List<Scored>, k: Int, keep: (Scored) -> Boolean): List<Scored> {
        if (k <= 0) return emptyList()
        // Worst on top: the lowest score, and among equal scores the latest in the list.
        val heap = PriorityQueue<IndexedValue<Scored>>(k + 1, compareBy<IndexedValue<Scored>> { it.value.score }.thenByDescending { it.index })
        for ((i, s) in list.withIndex()) {
            if (!keep(s)) continue
            if (heap.size < k) heap.add(IndexedValue(i, s))
            else {
                val worst = heap.peek()
                if (s.score > worst.value.score) { heap.poll(); heap.add(IndexedValue(i, s)) }
            }
        }
        return heap.sortedWith(compareByDescending<IndexedValue<Scored>> { it.value.score }.thenBy { it.index }).map { it.value }
    }

    /** One song drawn from [pool], each weighted by the softmax of its score. */
    fun draw(pool: List<Scored>, rng: Random, temperature: Double = RecommendationConfig.TEMPERATURE): Scored? {
        val top = pool.take(RecommendationConfig.POOL_SIZE)
        if (top.isEmpty()) return null
        val t = if (temperature.isFinite() && temperature > 0.0) temperature else RecommendationConfig.TEMPERATURE
        val best = top.maxOf { finite(it.score) }
        val weights = top.map { exp((finite(it.score) - best) / t).takeIf { w -> w.isFinite() } ?: 0.0 }
        val sum = weights.sum()
        if (!(sum > 0.0)) return top.first()
        var roll = rng.nextDouble() * sum
        for ((i, w) in weights.withIndex()) { roll -= w; if (roll <= 0) return top[i] }
        return top.last()
    }

    /**
     * The next [count] songs, each by its own draw (the one before it is out of the next one's pool and counts as played). The songs are scored once;
     * from the second pick on, only the transition is scored again, against the song just picked rather than the one playing, so the queue keeps
     * flowing from song to song in tempo and energy.
     */
    fun next(all: List<SmartEntry>, ctx: SmartContext, count: Int, rng: Random = Random.Default): List<SmartEntry> {
        if (count <= 0) return emptyList()
        val filtered = hardFilter(all, ctx)
        if (filtered.isEmpty()) return emptyList()
        val base = score(filtered, ctx)
        // Closeness to the song playing does not change as the queue grows: the explore threshold is found once.
        val threshold = exploreThreshold(base)
        var candidates: List<Scored> = base
        val out = ArrayList<SmartEntry>(count)
        val genreCounts = HashMap<String, Int>()
        repeat(count) {
            ctx.cancel()
            if (candidates.isEmpty()) return out
            val prev = out.lastOrNull()
            val current = if (prev == null) candidates else candidates.map { s ->
                val t = transitionScore(prev.tempo, prev.loudness, s.entry.tempo, s.entry.loudness)
                Scored(s.entry, s.score - s.transition + t, s.similarity, s.cooc, t)
            }
            val pools = pools(current, ctx, threshold)
            val roll = rng.nextDouble()
            val pool = when {
                roll < RecommendationConfig.SHARE_EXPLOIT -> pools.exploit
                roll < RecommendationConfig.SHARE_EXPLOIT + RecommendationConfig.SHARE_EXPLORE -> pools.explore
                else -> pools.gems
            }.ifEmpty { pools.exploit }.ifEmpty { pools.explore }
            val pick = draw(pool, rng)?.entry ?: return out
            out += pick
            for (g in pick.lowerGenres) genreCounts.merge(g, 1, Int::plus)
            // Not the same artist twice in a row either: what was just picked leaves the list with its artist if others remain.
            val rest = candidates.filter { it.entry.trackId != pick.trackId }
            candidates = rest.filter { it.entry.artistKey != pick.artistKey }.ifEmpty { rest }
            // One genre must not take the queue over: once it exceeds its share, it is set aside for the rest of the draw.
            candidates = capGenre(candidates, genreCounts, out.size)
        }
        return out
    }

    /** Every this many songs (a power of two, less one) [score] lets a cancelled caller stop. */
    private const val CANCEL_EVERY_MASK = 511

    private fun finite(x: Double): Double = if (x.isFinite()) x else 0.0
}

/** Removes the most-played genre of the picks from [candidates] once it exceeds its allowed share; nothing when it would empty the pool. */
private fun capGenre(candidates: List<SmartRanker.Scored>, counts: Map<String, Int>, picked: Int): List<SmartRanker.Scored> {
    if (picked < 3) return candidates
    val dominant = counts.maxByOrNull { it.value } ?: return candidates
    if (dominant.value.toDouble() / picked <= RecommendationConfig.MAX_GENRE_SHARE) return candidates
    val blocked = dominant.key
    return candidates.filter { s -> blocked !in s.entry.lowerGenres }.ifEmpty { candidates }
}

/** A song played enough to judge but never finished, and not liked: its engagement says it is not what the listener wants. */
private fun lowEngagement(s: TrackStatsEntity): Boolean =
    !s.isLiked && s.playCount >= RecommendationConfig.LOW_ENGAGEMENT_PLAYS &&
        (s.completedCount.toDouble() / maxOf(1, s.playCount)) < RecommendationConfig.LOW_ENGAGEMENT_COMPLETION
