package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRhythmTest {
    private val now = 100L * 86_400_000L
    private fun entry(id: String, artist: String, stats: TrackStatsEntity? = null, bpm: Double? = null, energy: Double? = null) =
        SmartEntry(id, artist, "al$id", emptySet(), stats?.copy(trackId = id), bpm, energy)

    private fun ctx(session: SessionContext) = SmartContext(now, DayPart.EVENING, null, emptyMap(), emptySet(), emptyMap(), emptyMap(), session)

    private fun scoreOf(e: SmartEntry, session: SessionContext) = SmartRanker.score(listOf(e), ctx(session)).single().score

    // --- artist cooldown ---

    @Test fun `an artist played lately costs more with every play, exponentially`() {
        val s = SessionContext()
        val none = s.artistCooldownPenalty("a")
        s.recordPlay("t1", "a")
        val one = s.artistCooldownPenalty("a")
        s.recordPlay("t2", "a")
        val two = s.artistCooldownPenalty("a")
        s.recordPlay("t3", "a")
        val three = s.artistCooldownPenalty("a")
        assertEquals(0.0, none, 0.0)
        assertTrue(one > 0.0 && two > one && three > two)
        // Each extra play adds more than the one before it.
        assertTrue(three - two > two - one)
    }

    @Test fun `the cooldown is capped and leaves other artists alone`() {
        val s = SessionContext()
        repeat(15) { s.recordPlay("t$it", "a") }
        assertEquals(RecommendationConfig.ARTIST_COOLDOWN_MAX, s.artistCooldownPenalty("a"), 0.0)
        assertEquals(0.0, s.artistCooldownPenalty("b"), 0.0)
    }

    @Test fun `an artist is forgiven once it is out of the last twenty songs`() {
        val s = SessionContext()
        s.recordPlay("old", "a")
        repeat(RecommendationConfig.ARTIST_COOLDOWN_WINDOW) { s.recordPlay("t$it", "b") }
        assertEquals(0.0, s.artistCooldownPenalty("a"), 0.0)
    }

    @Test fun `the ranker prefers the artist that did not just play`() {
        val s = SessionContext().also { it.recordPlay("x", "a") }
        assertTrue(scoreOf(entry("1", "b"), s) > scoreOf(entry("2", "a"), s))
    }

    // --- fast skip ---

    @Test fun `a song left within seconds rests for half an hour and takes its artist back a little`() {
        val s = SessionContext()
        s.recordFastSkip("t1", "a", now)
        assertTrue(s.isCoolingDown("t1", now + 60_000L))
        assertFalse(s.isCoolingDown("t1", now + RecommendationConfig.FAST_SKIP_COOLDOWN_MS + 1))
        assertFalse(s.isCoolingDown("t2", now))
        assertEquals(RecommendationConfig.FAST_SKIP_ARTIST_PENALTY, s.fastSkipPenalty("a", now), 0.0)
        assertEquals(0.0, s.fastSkipPenalty("b", now), 0.0)
    }

    @Test fun `a song that is cooling down is out of the draw unless nothing else is left`() {
        val s = SessionContext().also { it.recordFastSkip("t1", "a1", now) }
        val all = (1..6).map { entry("t$it", "a$it") }
        assertFalse("t1" in SmartRanker.hardFilter(all, ctx(s)).map { it.trackId })
        val only = listOf(entry("t1", "a1"))
        assertEquals(1, SmartRanker.hardFilter(only, ctx(s)).size)
    }

    @Test fun `a fast skip is a skip under fifteen seconds`() {
        val event = ListenEvent(playedMs = 14_000L, durationMs = 200_000L, transition = Transition.USER_SKIP, manualStart = false, atMs = now)
        assertTrue(AffinityScorer.apply(TrackStatsEntity("t"), event).delta <= RecommendationConfig.QUICK_SKIP)
        val later = event.copy(playedMs = 16_000L)
        assertEquals(0.0, AffinityScorer.apply(TrackStatsEntity("t"), later).delta, 0.0)
    }

    // --- intent ---

    @Test fun `a song the listener chose and finished earns far more than one the automix queued`() {
        fun finished(manual: Boolean, autoplay: Boolean) = AffinityScorer.apply(
            TrackStatsEntity("t"),
            ListenEvent(200_000L, 200_000L, Transition.AUTO, manualStart = manual, atMs = now, autoplay = autoplay),
        ).delta
        val chosen = finished(manual = true, autoplay = false)
        val queue = finished(manual = false, autoplay = false)
        val auto = finished(manual = false, autoplay = true)
        assertTrue(chosen > queue && queue > auto)
        assertEquals(RecommendationConfig.COMPLETED_AUTOPLAY, auto, 0.0)
        assertTrue(auto > 0.0)
    }

    @Test fun `the origin follows who chose the song`() {
        assertEquals(PlayOrigin.MANUAL, PlayOrigin.of(manualStart = true, autoplay = true))
        assertEquals(PlayOrigin.AUTOPLAY, PlayOrigin.of(manualStart = false, autoplay = true))
        assertEquals(PlayOrigin.QUEUE, PlayOrigin.of(manualStart = false, autoplay = false))
    }

    // --- availability vs affinity ---

    @Test fun `a favourite just heard is less available than one left alone for days`() {
        val loved = TrackStatsEntity("x", affinityScore = 40.0, isLiked = true, playCount = 5, completedCount = 5)
        val justNow = entry("1", "a1", loved.copy(lastPlayedTimestamp = now - 60_000L))
        val longAgo = entry("2", "a2", loved.copy(lastPlayedTimestamp = now - 20L * 86_400_000L))
        val s = SessionContext()
        assertTrue(scoreOf(longAgo, s) > scoreOf(justNow, s))
        assertEquals(1.0, SmartRanker.availability(0L, now), 0.0)
        assertTrue(SmartRanker.availability(now - 3_600_000L, now) < SmartRanker.availability(now - 48 * 3_600_000L, now))
    }

    // --- acoustic bridge ---

    private fun sessionAt(bpm: Double, energy: Double = 0.5) = SessionContext().also { s -> repeat(4) { s.recordAcoustics(bpm, energy) } }

    @Test fun `no profile until enough songs were analysed`() {
        val s = SessionContext().also { it.recordAcoustics(120.0, 0.5); it.recordAcoustics(121.0, 0.5) }
        assertNull(s.acousticProfile())
        assertEquals(0.0, s.coherencePenalty(60.0, 0.1), 0.0)
    }

    @Test fun `a settled tempo costs the songs that break it, half and double time excepted`() {
        val s = sessionAt(120.0)
        assertEquals(0.0, s.coherencePenalty(124.0, null), 0.0)
        assertEquals(0.0, s.coherencePenalty(60.0, null), 0.0)
        assertEquals(0.0, s.coherencePenalty(240.0, null), 0.0)
        assertTrue(s.coherencePenalty(90.0, null) > 0.0)
        assertTrue(s.coherencePenalty(170.0, null) > s.coherencePenalty(145.0, null))
        assertTrue(s.coherencePenalty(10.0, null) <= RecommendationConfig.COHERENCE_MAX)
    }

    @Test fun `a tempo that was never settled asks nothing of the next song`() {
        val s = SessionContext().also { it.recordAcoustics(70.0, 0.5); it.recordAcoustics(130.0, 0.5); it.recordAcoustics(95.0, 0.5); it.recordAcoustics(160.0, 0.5) }
        assertEquals(0.0, s.coherencePenalty(100.0, null), 0.0)
    }

    @Test fun `a settled energy costs a song of a very different energy`() {
        val s = sessionAt(120.0, energy = 0.8)
        assertEquals(0.0, s.coherencePenalty(null, 0.75), 0.0)
        assertTrue(s.coherencePenalty(null, 0.1) > 0.0)
    }

    @Test fun `the ranker keeps the rhythm without closing the door on a song with no analysis`() {
        val s = sessionAt(120.0)
        val steady = scoreOf(entry("1", "a1", bpm = 122.0), s)
        val broken = scoreOf(entry("2", "a2", bpm = 80.0), s)
        val unknown = scoreOf(entry("3", "a3"), s)
        assertTrue(steady > broken)
        assertEquals(steady, unknown, 1e-9)
    }

    @Test fun `a rhythm penalty does not take the explore pool away`() {
        val s = sessionAt(120.0)
        val all = (1..12).map { entry("t$it", "a$it", bpm = 60.0 + it * 10) }
        val pools = SmartRanker.pools(SmartRanker.score(all, ctx(s)), ctx(s))
        assertTrue(pools.exploit.isNotEmpty())
    }
}
