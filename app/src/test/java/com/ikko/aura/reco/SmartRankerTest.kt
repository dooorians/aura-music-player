package com.ikko.aura.reco

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRankerTest {
    private val now = 100L * 86_400_000L
    private fun e(id: String, artist: String, stats: TrackStatsEntity? = null, genres: Set<String> = emptySet(), album: String = "al$id") =
        SmartEntry(id, artist, album, genres, stats?.copy(trackId = id))

    private fun ctx(current: SmartEntry? = null, blocked: Set<String> = emptySet(), sim: Map<String, Double> = emptyMap(),
                    cooc: Map<String, Double> = emptyMap(), session: SessionContext = SessionContext(), artist: Map<String, Double> = emptyMap()) =
        SmartContext(now, DayPart.EVENING, current, artist, blocked, sim, cooc, session)

    private val pool = (1..8).map { e("t$it", "a$it") }

    @Test fun `disliked, skipped for good and blocked artists never come back, even when nothing else is left`() {
        val all = listOf(
            e("d", "a1", TrackStatsEntity("d", isDisliked = true)), e("s", "a2", TrackStatsEntity("s", consecutiveSkips = 5)),
            e("b", "a3"), e("ok", "a4"),
        )
        assertEquals(listOf("ok"), SmartRanker.hardFilter(all, ctx(blocked = setOf("a3"))).map { it.trackId })
        assertTrue(SmartRanker.hardFilter(all.take(3), ctx(blocked = setOf("a3"))).isEmpty())
    }

    @Test fun `what was played within a day, in the session or by the last artists is out, but the rules give way when nothing remains`() {
        val played = e("p", "a1", TrackStatsEntity("p", lastPlayedTimestamp = now - 3_600_000L))
        val all = pool + played
        assertFalse("p" in SmartRanker.hardFilter(all, ctx()).map { it.trackId })
        val session = SessionContext().also { s -> pool.take(3).forEach { s.recordPlay(it.trackId, it.artistKey) } }
        val kept = SmartRanker.hardFilter(all, ctx(session = session)).map { it.trackId }
        assertTrue(kept.none { it in setOf("t1", "t2", "t3", "p") })
        // A library of two songs both just played still gets a next song.
        val tiny = listOf(e("x", "a1", TrackStatsEntity("x", lastPlayedTimestamp = now - 1_000)), e("y", "a2", TrackStatsEntity("y", lastPlayedTimestamp = now - 1_000)))
        assertEquals(2, SmartRanker.hardFilter(tiny, ctx()).size)
    }

    @Test fun `a liked song of a close artist scores above an unknown one, and a recent play is penalised`() {
        val current = e("cur", "cur", genres = setOf("k-pop"))
        val liked = e("l", "close", TrackStatsEntity("l", isLiked = true, affinityScore = 15.0, playCount = 4, completedCount = 4, lastPlayedTimestamp = now - 40 * 86_400_000L), setOf("k-pop"))
        val stranger = e("s", "far")
        val recent = e("r", "close2", TrackStatsEntity("r", affinityScore = 15.0, playCount = 4, completedCount = 4, lastPlayedTimestamp = now - 86_400_000L / 2), setOf("k-pop"))
        val scored = SmartRanker.score(listOf(liked, stranger, recent), ctx(current, sim = mapOf("close" to 0.9, "close2" to 0.9))).associate { it.entry.trackId to it.score }
        assertTrue(scored.getValue("l") > scored.getValue("s"))
        assertTrue(scored.getValue("l") > scored.getValue("r"))
    }

    @Test fun `a genre the session keeps skipping costs 25`() {
        val session = SessionContext().also { it.recordQuickSkip(setOf("metal")); it.recordQuickSkip(setOf("metal")) }
        val a = e("a", "x", genres = setOf("metal")); val b = e("b", "y", genres = setOf("jazz"))
        val s = SmartRanker.score(listOf(a, b), ctx(session = session)).associate { it.entry.trackId to it.score }
        assertEquals(25.0, s.getValue("b") - s.getValue("a"), 1e-9)
    }

    @Test fun `the draw favours the best score but is not fixed`() {
        val scored = SmartRanker.score(
            listOf(e("hi", "a", TrackStatsEntity("x", affinityScore = 30.0, playCount = 5, completedCount = 5)), e("lo", "b")), ctx(),
        )
        val rng = Random(1)
        val wins = (1..400).count { SmartRanker.draw(scored, rng)?.entry?.trackId == "hi" }
        assertTrue(wins in 250..399)
    }

    @Test fun `next never repeats a song, nor an artist twice in a row, and fills the count`() {
        val out = SmartRanker.next(pool, ctx(), 5, Random(7))
        assertEquals(5, out.size)
        assertEquals(5, out.map { it.trackId }.distinct().size)
        assertTrue(out.zipWithNext().all { (a, b) -> a.artistKey != b.artistKey })
    }

    @Test fun `with an empty library there is no next song`() {
        assertTrue(SmartRanker.next(emptyList(), ctx(), 3).isEmpty())
    }

    @Test fun `an artist close to a blocked one costs thirty`() {
        val a = e("a", "near"); val b = e("b", "far")
        val ctx = SmartContext(now, DayPart.EVENING, null, emptyMap(), emptySet(), emptyMap(), emptyMap(), SessionContext(), blockedNeighbors = setOf("near"))
        val s = SmartRanker.score(listOf(a, b), ctx).associate { it.entry.trackId to it.score }
        assertEquals(30.0, s.getValue("b") - s.getValue("a"), 1e-9)
    }
}
