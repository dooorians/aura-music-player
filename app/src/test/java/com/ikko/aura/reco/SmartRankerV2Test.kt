package com.ikko.aura.reco

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The factors the queue's ranker gained: skip rate, cold start by style, serendipity, transitions, and its behaviour at the limits. */
class SmartRankerV2Test {
    private val now = 400L * 86_400_000L
    private val day = 86_400_000L

    private fun e(id: String, artist: String = id, stats: TrackStatsEntity? = null, genres: Set<String> = emptySet(), bpm: Double? = null, energy: Double? = null) =
        SmartEntry(id, artist, "al$id", genres, stats?.copy(trackId = id), bpm, energy)

    private fun ctx(current: SmartEntry? = null, session: SessionContext = SessionContext(), cooc: Map<String, Double> = emptyMap(), cancel: () -> Unit = {}) =
        SmartContext(now, DayPart.EVENING, current, emptyMap(), emptySet(), emptyMap(), cooc, session, cancel = cancel)

    private fun scores(entries: List<SmartEntry>, c: SmartContext = ctx()) = SmartRanker.score(entries, c).associate { it.entry.trackId to it.score }

    // --- skips ---

    @Test fun `a song left early most of the time is set back, one skip in ten is not`() {
        val base = TrackStatsEntity("x", playCount = 10, completedCount = 5, affinityScore = 5.0, lastPlayedTimestamp = now - 10 * day)
        val s = scores(listOf(e("often", stats = base.copy(earlySkipCount = 8)), e("rarely", stats = base.copy(earlySkipCount = 1)), e("never", stats = base)))
        assertTrue(s.getValue("rarely") > s.getValue("often"))
        // One skip in ten is below the prior: it costs nothing.
        assertEquals(s.getValue("never"), s.getValue("rarely"), 1e-9)
        assertTrue(SmartRanker.smoothedSkipRate(TrackStatsEntity("y", playCount = 1, earlySkipCount = 1)) < 0.5)
    }

    // --- cold start ---

    @Test fun `a song never heard inherits what its style earned`() {
        val loved = (1..6).map { e("k$it", genres = setOf("K-Pop"), stats = TrackStatsEntity("k", playCount = 6, completedCount = 6, affinityScore = 20.0, lastPlayedTimestamp = now - 20 * day)) }
        val hated = (1..6).map { e("m$it", genres = setOf("Death Metal"), stats = TrackStatsEntity("m", playCount = 6, earlySkipCount = 5, affinityScore = -8.0, lastPlayedTimestamp = now - 20 * day)) }
        val newKpop = e("newK", genres = setOf("K-Pop"))
        val newMetal = e("newM", genres = setOf("Death Metal"))
        val newNothing = e("newX")
        val s = scores(loved + hated + listOf(newKpop, newMetal, newNothing))
        assertTrue(s.getValue("newK") > s.getValue("newX"))
        assertTrue(s.getValue("newX") > s.getValue("newM"))
    }

    @Test fun `with no history there is no prior to inherit`() {
        val s = scores(listOf(e("a", genres = setOf("Jazz")), e("b", genres = setOf("Metal"))))
        assertEquals(s.getValue("a"), s.getValue("b"), 1e-9)
    }

    // --- serendipity ---

    @Test fun `what was rarely heard gets a bounded bonus for what a listen would teach`() {
        val heard = e("heard", stats = TrackStatsEntity("h", playCount = 60, completedCount = 45))
        val fresh = e("fresh", stats = TrackStatsEntity("f", playCount = 0))
        val s = scores(listOf(heard, fresh))
        // Nothing else tells them apart: the fresh one's whole score is its exploration bonus, and it is capped.
        assertTrue(s.getValue("fresh") > s.getValue("heard"))
        assertTrue(s.getValue("fresh") > 0.0 && s.getValue("fresh") <= RecommendationConfig.UCB_MAX + 1e-9)
    }

    // --- transitions ---

    @Test fun `the next song follows the one playing in tempo, double time included, unknown neutral`() {
        val current = e("cur", "c", bpm = 128.0)
        val c = ctx(current)
        val s = scores(listOf(e("same", bpm = 128.0), e("double", bpm = 64.0), e("far", bpm = 97.0), e("unknown"), e("zero", bpm = 0.0), e("nan", bpm = Double.NaN)), c)
        assertTrue(s.getValue("same") >= s.getValue("double"))
        assertTrue(s.getValue("double") > s.getValue("unknown"))
        assertTrue(s.getValue("unknown") > s.getValue("far"))
        // A tempo that is not one is unknown, not a bad match.
        assertEquals(s.getValue("unknown"), s.getValue("zero"), 1e-9)
        assertEquals(s.getValue("unknown"), s.getValue("nan"), 1e-9)
    }

    @Test fun `an unknown side of a transition adds exactly nothing`() {
        assertEquals(0.0, SmartRanker.transitionScore(null, null, 120.0, 0.5), 0.0)
        assertEquals(0.0, SmartRanker.transitionScore(120.0, null, null, 0.4), 0.0)
        assertTrue(SmartRanker.transitionScore(120.0, 0.5, 120.0, 0.5) > 0.0)
        assertTrue(SmartRanker.transitionScore(120.0, 0.9, 92.0, 0.1) < 0.0)
    }

    @Test fun `the queue keeps a tempo from song to song`() {
        var steady = 0
        var total = 0
        repeat(12) { seed ->
            val library = (1..60).map { i -> e("t$i", "a$i", bpm = if (i % 2 == 0) 120.0 + (i % 5) else 100.0 - (i % 5)) }
            val out = SmartRanker.next(library, ctx(e("cur", "c", bpm = 121.0)), 8, Random(seed))
            steady += out.count { it.bpm!! >= 110.0 }
            total += out.size
        }
        assertTrue("$steady of $total kept the tempo", steady.toDouble() / total > 0.7)
    }

    // --- limits ---

    @Test fun `empty, tiny and duplicated libraries`() {
        assertTrue(SmartRanker.next(emptyList(), ctx(), 5).isEmpty())
        assertTrue(SmartRanker.next(listOf(e("a")), ctx(), 0).isEmpty())
        assertTrue(SmartRanker.next(listOf(e("a")), ctx(), -3).isEmpty())
        assertEquals(listOf("a"), SmartRanker.next(listOf(e("a")), ctx(), 5).map { it.trackId })
        val dup = listOf(e("a"), e("a"), e("b", "x"), e("b", "x"))
        val out = SmartRanker.next(dup, ctx(), 4, Random(2)).map { it.trackId }
        assertEquals(out.distinct(), out)
    }

    @Test fun `corrupt numbers never make a score that is not a number`() {
        val bad = TrackStatsEntity(
            "z", playCount = -3, completedCount = 9, earlySkipCount = 99, affinityScore = Double.NaN, lastPlayedTimestamp = now + 5 * day,
            playsMorning = -1, playsEvening = 7,
        )
        val entries = listOf(
            e("nan", stats = bad, bpm = Double.NaN, energy = Double.POSITIVE_INFINITY),
            e("inf", stats = TrackStatsEntity("i", affinityScore = Double.POSITIVE_INFINITY, playCount = 2), bpm = 1e9),
            e("ok", stats = TrackStatsEntity("o", playCount = 3, completedCount = 3, affinityScore = 4.0)),
        )
        val current = e("cur", bpm = Double.NaN, energy = -1.0)
        val c = ctx(current, cooc = mapOf("ok" to Double.NaN, "nan" to Double.POSITIVE_INFINITY))
        SmartRanker.score(entries, c).forEach { assertTrue("${it.entry.trackId}: ${it.score}", it.score.isFinite()) }
        // The song "played" in the future counts as played just now: the others come first, and the queue still fills.
        assertTrue(SmartRanker.next(entries, c, 3, Random(1)).size >= 2)
        assertTrue(SmartRanker.smoothedCompletion(bad) in 0.0..1.0)
        assertTrue(SmartRanker.smoothedSkipRate(bad) in 0.0..1.0)
    }

    @Test fun `a cancelled choice stops`() {
        val library = (1..5_000).map { e("t$it", "a${it % 300}") }
        var calls = 0
        val c = ctx(cancel = { if (++calls > 3) throw java.util.concurrent.CancellationException("gone") })
        val thrown = runCatching { SmartRanker.next(library, c, 5) }.exceptionOrNull()
        assertTrue(thrown is java.util.concurrent.CancellationException)
    }

    // --- scale ---

    @Test fun `a large library is scored and drawn from quickly`() {
        val rng = Random(42)
        val genres = listOf("K-Pop", "Indie Rock", "Hip-Hop", "Jazz", "House", "Chanson", "Metal", "R&B")
        val library = (1..20_000).map { i ->
            val played = rng.nextInt(3) != 0
            e(
                "t$i", "a${rng.nextInt(2_000)}",
                stats = if (!played) null else TrackStatsEntity(
                    "t$i", playCount = rng.nextInt(40), completedCount = rng.nextInt(30), earlySkipCount = rng.nextInt(10),
                    affinityScore = rng.nextDouble() * 30 - 5, lastPlayedTimestamp = now - rng.nextLong(1, 400) * day, isLiked = rng.nextInt(20) == 0,
                    playsEvening = rng.nextInt(10), playsMorning = rng.nextInt(10),
                ),
                genres = setOf(genres[rng.nextInt(genres.size)], genres[rng.nextInt(genres.size)]),
                bpm = if (rng.nextInt(4) == 0) null else 60.0 + rng.nextDouble() * 120,
                energy = if (rng.nextInt(4) == 0) null else rng.nextDouble(),
            )
        }
        val c = ctx(e("cur", "zz", genres = setOf("K-Pop"), bpm = 120.0, energy = 0.5), cooc = library.take(300).associate { it.trackId to rng.nextDouble() * 5 })
        // Warm the JIT once, then measure.
        SmartRanker.next(library.take(2_000), c, 5, Random(1))
        val t0 = System.nanoTime()
        val out = SmartRanker.next(library, c, 10, Random(1))
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(10, out.size)
        assertEquals(10, out.map { it.trackId }.distinct().size)
        assertTrue(out.zipWithNext().all { (a, b) -> a.artistKey != b.artistKey })
        assertTrue("20 000 songs, 10 picks: $ms ms", ms < 4_000)
    }
}
