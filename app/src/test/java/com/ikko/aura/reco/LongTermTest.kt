package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTermTest {
    private val day = 86_400_000L
    private val now = 1_000L * day

    // --- decay ---

    @Test fun `a score loses half of itself after the half-life and nothing when fresh or never fed`() {
        val halfLife = (RecommendationConfig.AFFINITY_HALF_LIFE_DAYS * day).toLong()
        assertEquals(0.5, AffinityDecay.factor(now - halfLife, now), 1e-9)
        assertEquals(0.25, AffinityDecay.factor(now - 2 * halfLife, now), 1e-9)
        assertEquals(1.0, AffinityDecay.factor(now, now), 0.0)
        assertEquals(1.0, AffinityDecay.factor(0L, now), 0.0)
    }

    @Test fun `a new listen adds to what is left of an old score`() {
        val halfLife = (RecommendationConfig.AFFINITY_HALF_LIFE_DAYS * day).toLong()
        val before = TrackStatsEntity("t", affinityScore = 20.0, lastPlayedTimestamp = now - halfLife)
        val event = ListenEvent(200_000L, 200_000L, Transition.AUTO, manualStart = false, atMs = now)
        val after = AffinityScorer.apply(before, event).stats.affinityScore
        assertEquals(10.0 + RecommendationConfig.COMPLETED, after, 1e-9)
    }

    @Test fun `the ranker reads a score as it stands today`() {
        val halfLife = (RecommendationConfig.AFFINITY_HALF_LIFE_DAYS * day).toLong()
        val old = TrackStatsEntity("t", affinityScore = 40.0, lastPlayedTimestamp = now - 2 * halfLife)
        assertEquals(10.0, SmartRanker.effectiveAffinity(old, now), 1e-9)
        assertEquals(0.0, SmartRanker.effectiveAffinity(null, now), 0.0)
    }

    // --- one recording, two ids ---

    private fun row(id: String, canon: String = "", plays: Int = 0, liked: Boolean = false, affinity: Double = 0.0, last: Long = 0L, skips: Int = 0) =
        TrackStatsEntity(id, playCount = plays, completedCount = plays, isLiked = liked, affinityScore = affinity, lastPlayedTimestamp = last, consecutiveSkips = skips, canonicalKey = canon)

    @Test fun `a file and its stream share what was done with either`() {
        val key = TrackIdentity.canonicalKey("Charli XCX", "Von dutch")
        val index = StatsIndex(listOf(row("42", key, plays = 3, affinity = 6.0, last = now - day), row("ytm:abc", key, plays = 2, liked = true, affinity = 4.0, last = now)))
        val merged = index.of("42")!!
        assertEquals(5, merged.playCount)
        assertTrue(merged.isLiked)
        assertEquals(now, merged.lastPlayedTimestamp)
        assertTrue(merged.affinityScore > 9.0 && merged.affinityScore <= 10.0)
        // The same, asked from the stream's side, or by what a candidate is.
        assertEquals(5, index.of("ytm:abc")!!.playCount)
        assertEquals(5, index.of("ytm:new", key)!!.playCount)
    }

    @Test fun `rows that predate the stored key are joined through the library's`() {
        val key = TrackIdentity.canonicalKey("Queen", "Bohemian Rhapsody")
        val index = StatsIndex(listOf(row("7", "", plays = 1), row("ytm:q", key, plays = 1)), canonOf = mapOf("7" to key))
        assertEquals(2, index.of("7")!!.playCount)
    }

    @Test fun `unrelated songs stay apart and an unknown song has no stats`() {
        val index = StatsIndex(listOf(row("1", "a::x", plays = 1), row("2", "b::y", plays = 4)))
        assertEquals(1, index.of("1")!!.playCount)
        assertNull(index.of("3", "c::z"))
    }

    @Test fun `a no on any version is a no, and the latest skip streak is the one that counts`() {
        val disliked = row("1", "k", plays = 1).copy(isDisliked = true, lastPlayedTimestamp = 1L)
        val skipped = row("2", "k", plays = 1, last = now, skips = 3)
        val merged = StatsIndex(listOf(disliked, skipped)).of("1", "k")!!
        assertTrue(merged.isDisliked)
        assertEquals(3, merged.consecutiveSkips)
    }

    // --- interleave ---

    @Test fun `streamed songs come at even intervals and each list keeps its order`() {
        val out = Interleave.spread(listOf("a", "b", "c", "d"), listOf("X", "Y"))
        assertEquals(6, out.size)
        assertEquals(listOf("a", "b", "c", "d"), out.filter { it.first().isLowerCase() })
        assertEquals(listOf("X", "Y"), out.filter { it.first().isUpperCase() })
        // Not both at the head, not both at the tail.
        assertFalse(out.take(2).all { it.first().isUpperCase() })
        assertEquals("a", out.first())
    }

    @Test fun `nothing to spread leaves the other list as it is`() {
        assertEquals(listOf(1, 2), Interleave.spread(listOf(1, 2), emptyList()))
        assertEquals(listOf(9), Interleave.spread(emptyList(), listOf(9)))
    }

    // --- tags ---

    @Test fun `vorbis comments give the identifiers, the gain, the tempo, every genre and the first year`() {
        val p = TagMap.parse(
            listOf(
                "MUSICBRAINZ_TRACKID" to "B1A9C0E9-D987-4042-AE91-78D6A3267D69",
                "isrc" to "gbuum7100001",
                "REPLAYGAIN_TRACK_GAIN" to "-7.35 dB",
                "BPM" to "128",
                "GENRE" to "Indie Rock",
                "genre" to "Hip Hop",
                "ORIGINALDATE" to "1997-05-21",
            ),
        )
        assertEquals("b1a9c0e9-d987-4042-ae91-78d6a3267d69", p.mbid)
        assertEquals("GBUUM7100001", p.isrc)
        assertEquals(-7.35, p.replayGainDb!!, 1e-9)
        assertEquals(128.0, p.bpm, 0.0)
        assertEquals(listOf("Indie Rock", "Hip-Hop"), p.genres)
        assertEquals(1997, p.originalYear)
    }

    @Test fun `id3 frames are read by their frame ids and txxx descriptions`() {
        val p = TagMap.parse(
            listOf(
                "TSRC" to "FR-Z03-14-00001",
                "UFID:musicbrainz.org" to "b1a9c0e9-d987-4042-ae91-78d6a3267d69",
                "replaygain_album_gain" to "+2,10 dB",
                "TBPM" to "93,5",
                "TCON" to "(17)",
                "TDOR" to "1983",
            ),
        )
        assertEquals("FRZ031400001", p.isrc)
        assertNotNull(p.mbid.takeIf { it.isNotEmpty() })
        // The album's gain stands in when the track has none.
        assertEquals(2.1, p.replayGainDb!!, 1e-9)
        assertEquals(93.5, p.bpm, 0.0)
        assertEquals(listOf("Rock"), p.genres)
        assertEquals(1983, p.originalYear)
    }

    @Test fun `junk values are dropped, not guessed`() {
        val p = TagMap.parse(listOf("isrc" to "not an isrc", "bpm" to "5", "REPLAYGAIN_TRACK_GAIN" to "loud", "genre" to "Unknown", "originalyear" to "n/a", "musicbrainz_trackid" to "xyz"))
        assertEquals(ParsedTags(), p)
    }
}
