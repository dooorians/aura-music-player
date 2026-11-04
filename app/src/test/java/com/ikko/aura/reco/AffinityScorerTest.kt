package com.ikko.aura.reco

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AffinityScorerTest {
    private val zone = ZoneId.of("UTC")
    private val noon = 12L * 3_600_000L
    private fun stats() = TrackStatsEntity(trackId = "t")
    private fun ev(played: Long, dur: Long = 200_000, tr: Transition = Transition.USER_SKIP, manual: Boolean = false, at: Long = noon) =
        ListenEvent(played, dur, tr, manual, at)

    @Test fun `a complete listen adds one and clears the skips`() {
        val r = AffinityScorer.apply(stats().copy(consecutiveSkips = 3), ev(190_000, tr = Transition.AUTO), zone)
        assertEquals(1.0, r.delta, 1e-9)
        assertEquals(0, r.stats.consecutiveSkips)
        assertEquals(1, r.stats.completedCount)
        assertEquals(1, r.stats.playsAfternoon)
    }

    @Test fun `skips cost more the sooner they come`() {
        assertEquals(-2.5, AffinityScorer.apply(stats(), ev(3_000), zone).delta, 1e-9)
        assertEquals(-1.5, AffinityScorer.apply(stats(), ev(12_000), zone).delta, 1e-9)
        val r = AffinityScorer.apply(stats(), ev(3_000), zone)
        assertEquals(1, r.stats.earlySkipCount)
        assertEquals(1, r.stats.consecutiveSkips)
    }

    @Test fun `a partial listen adds a little`() {
        assertEquals(0.2, AffinityScorer.apply(stats(), ev(100_000), zone).delta, 1e-9)
    }

    @Test fun `a tap on a song adds three, a repeat two and a half, a like fifteen`() {
        assertEquals(1.0 + 3.0, AffinityScorer.apply(stats(), ev(190_000, tr = Transition.AUTO, manual = true), zone).delta, 1e-9)
        assertEquals(2.5, AffinityScorer.apply(stats(), ev(200_000, tr = Transition.REPEAT), zone).delta, 1e-9)
        val liked = AffinityScorer.liked(stats().copy(isDisliked = true))
        assertTrue(liked.stats.isLiked); assertFalse(liked.stats.isDisliked)
        assertEquals(15.0, liked.stats.affinityScore, 1e-9)
    }

    @Test fun `a song put aside for another queue costs nothing`() {
        assertEquals(0.0, AffinityScorer.apply(stats(), ev(4_000, tr = Transition.QUEUE_REPLACED), zone).delta, 1e-9)
    }

    @Test fun `night plays are those between 23 and 5`() {
        assertEquals(DayPart.NIGHT, DayPart.of(23)); assertEquals(DayPart.NIGHT, DayPart.of(4)); assertEquals(DayPart.MORNING, DayPart.of(5))
        assertEquals(DayPart.EVENING, DayPart.of(22)); assertEquals(DayPart.AFTERNOON, DayPart.of(17))
    }

    @Test fun `two quick skips of one style put it aside for the session`() {
        val s = SessionContext()
        s.recordQuickSkip(setOf("k-pop", "pop"))
        assertTrue(s.penalizedGenres().isEmpty())
        s.recordQuickSkip(setOf("k-pop"))
        assertEquals(setOf("k-pop"), s.penalizedGenres())
    }

    @Test fun `the session keeps fifty songs and three artists`() {
        val s = SessionContext()
        (1..60).forEach { s.recordPlay("t$it", "a$it") }
        assertEquals(50, s.recentTrackIds().size)
        assertEquals(setOf("a58", "a59", "a60"), s.lastArtists())
    }

    @Test fun `a weekend play counts as a weekend play`() {
        // 1970-01-03 was a Saturday.
        val saturday = 2L * 86_400_000L
        val r = AffinityScorer.apply(stats(), ev(190_000, tr = Transition.AUTO, at = saturday), zone)
        assertEquals(1, r.stats.playsWeekend)
        assertEquals(0, r.stats.playsWeekday)
    }

    @Test fun `a weekday play counts as a weekday play`() {
        // 1970-01-01 was a Thursday (the epoch).
        val r = AffinityScorer.apply(stats(), ev(190_000, tr = Transition.AUTO, at = noon), zone)
        assertEquals(1, r.stats.playsWeekday)
        assertEquals(0, r.stats.playsWeekend)
    }
}
