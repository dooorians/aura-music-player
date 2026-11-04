package com.ikko.aura.reco

import com.ikko.aura.reco.authority.CompilationTitles
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickWinsTest {
    private val day = 86_400_000L
    private val now = 400L * day
    private fun e(id: String, artist: String, stats: TrackStatsEntity? = null, genres: Set<String> = emptySet()) =
        SmartEntry(id, artist, "al$id", genres, stats?.copy(trackId = id))
    private fun ctx(current: SmartEntry? = null, weekend: Boolean = false) =
        SmartContext(now, DayPart.EVENING, current, emptyMap(), emptySet(), emptyMap(), emptyMap(), SessionContext(), weekend = weekend)

    // --- smoothing ---

    @Test fun `one play proves little, a single morning listen is a small lift, many are a big one`() {
        val one = SmartRanker.habitLift(here = 1, total = 1, prior = 0.25)
        val many = SmartRanker.habitLift(here = 30, total = 30, prior = 0.25)
        assertTrue(one > 0.0 && many > one * 3)
        assertEquals(0.0, SmartRanker.habitLift(0, 0, 0.25), 0.0)
        // Played only at night, heard in the morning: below chance.
        assertTrue(SmartRanker.habitLift(here = 0, total = 10, prior = 0.25) < 0.0)
    }

    @Test fun `one finished play is not a hundred percent completion`() {
        val one = SmartRanker.smoothedCompletion(TrackStatsEntity("t", playCount = 1, completedCount = 1))
        val many = SmartRanker.smoothedCompletion(TrackStatsEntity("t", playCount = 20, completedCount = 20))
        val never = SmartRanker.smoothedCompletion(TrackStatsEntity("t", playCount = 20, completedCount = 0))
        assertTrue(one < 0.85 && many > 0.9 && never < 0.2)
        assertEquals(RecommendationConfig.COMPLETION_PRIOR, SmartRanker.smoothedCompletion(null), 1e-9)
    }

    @Test fun `a lone play at this hour no longer outweighs a long habit`() {
        fun score(s: TrackStatsEntity) = SmartRanker.score(listOf(e("x", "a", s)), ctx()).single().score
        val lone = TrackStatsEntity("x", playCount = 1, completedCount = 1, playsEvening = 1, lastPlayedTimestamp = now - 40 * day)
        val habit = TrackStatsEntity("x", playCount = 30, completedCount = 28, playsEvening = 30, lastPlayedTimestamp = now - 40 * day)
        assertTrue(score(habit) > score(lone))
    }

    // --- exile ---

    @Test fun `five skips in a row take a song out, for sixty days`() {
        val skipped = { ago: Long -> e("s", "a1", TrackStatsEntity("s", consecutiveSkips = 5, lastPlayedTimestamp = now - ago)) }
        val rest = (1..6).map { e("t$it", "b$it") }
        assertFalse("s" in SmartRanker.hardFilter(rest + skipped(10 * day), ctx()).map { it.trackId })
        assertTrue("s" in SmartRanker.hardFilter(rest + skipped(61 * day), ctx()).map { it.trackId })
        // Never heard but flagged: still out.
        val ghost = e("g", "a2", TrackStatsEntity("g", consecutiveSkips = 5, lastPlayedTimestamp = 0L))
        assertFalse("g" in SmartRanker.hardFilter(rest + ghost, ctx()).map { it.trackId })
    }

    // --- explore fallback ---

    @Test fun `with no graph the explore pool is the little played, not nothing`() {
        val all = (1..10).map { e("t$it", "a$it", if (it <= 5) TrackStatsEntity("t$it", playCount = 40) else null) }
        val pools = SmartRanker.pools(SmartRanker.score(all, ctx()), ctx())
        assertTrue(pools.explore.isNotEmpty())
        assertTrue(pools.explore.all { (it.entry.stats?.playCount ?: 0) < RecommendationConfig.EXPLORE_MAX_PLAYS })
        assertEquals(3, SmartRanker.next(all, ctx(), 3, Random(1)).size)
    }

    // --- junk filters ---

    private fun artistJunk(name: String) = Eligibility.excluded(candidate("A Song", name), rankCtx()) == Exclusion.JUNK
    private fun titleJunk(title: String, confirmed: Boolean = true) = Eligibility.excluded(candidate(title, "Some Artist", confirmed), rankCtx()) == Exclusion.JUNK

    private fun candidate(title: String, artist: String, confirmed: Boolean = true) = Candidate(
        id = "ytm:x", sourceId = "ytm", sourceName = "YouTube Music", title = title, artist = artist, album = "",
        durationMs = 200_000L, artworkUrl = null, release = Release.Unknown, provenance = emptyList(), confirmedSong = confirmed,
    )

    private fun rankCtx() = RankContext(TasteProfile.Empty, TrackIdentity.Index(emptyList()), emptySet(), emptySet(), emptyMap(), now)

    @Test fun `bands with a generic word in their name are not channels`() {
        listOf("Sleep Token", "Beats Antique", "The Study", "Spa", "Relaxer", "Focus").forEach { assertFalse(it, artistJunk(it)) }
    }

    @Test fun `channels that describe their content still are`() {
        listOf("Sleep Music Zone", "Study Beats", "Relaxing Music", "Chill Beats", "Lofi Girl", "Workout Music Hits", "Karaoke Hits").forEach { assertTrue(it, artistJunk(it)) }
    }

    @Test fun `songs whose title is a video word stay, videos do not`() {
        listOf("Breakdown", "Routine", "Reaction", "Clip", "Hair Clip", "Interview").forEach { assertFalse(it, titleJunk(it)) }
        listOf("My Morning Skincare Routine", "Reacting To Taylor Swift Interview", "Song Name - Lyrics", "Song Name lyrics video", "Song (Official Lyrics)").forEach { assertTrue(it, titleJunk(it)) }
        assertFalse(titleJunk("Lyrics Of A Dreamer's Night"))
    }

    @Test fun `a real album called Greatest Hits resolves, a tribute to it does not`() {
        assertFalse(CompilationTitles.matches("Greatest Hits", "Greatest Hits"))
        assertTrue(CompilationTitles.matches("Greatest Hits (Tribute)", "Greatest Hits"))
        assertTrue(CompilationTitles.matches("Greatest Hits", "Divide"))
    }

    // --- origin in the taste profile ---

    private val t0 = 1_000L * day
    private fun listen(origin: PlayOrigin, n: Int = 1) = (1..n).map {
        ListenRecord("t$it", "Song $it", "Band", "", 0, 200_000L, 200_000L, Outcome.COMPLETED, t0 - it * 1000L, null, origin = origin)
    }

    private fun affinityAfter(origin: PlayOrigin): Double {
        val seeds = listOf("Other")
        val p = TasteProfileBuilder.build(ProfileInputs(listens = listen(origin, 4), seedArtists = seeds, nowMs = t0))
        return p.affinity(ArtistNames.key("Band"))
    }

    @Test fun `what the automix queued weighs less in the profile than what was chosen`() {
        val auto = affinityAfter(PlayOrigin.AUTOPLAY)
        val queue = affinityAfter(PlayOrigin.QUEUE)
        val chosen = affinityAfter(PlayOrigin.MANUAL)
        assertTrue("$auto $queue $chosen", auto < queue && queue < chosen)
    }
}
