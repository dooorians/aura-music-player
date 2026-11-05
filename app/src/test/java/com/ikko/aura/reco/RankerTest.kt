package com.ikko.aura.reco

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankerTest {

    /** A listener of a few artists, with enough history to not be a cold start. */
    private val profile = profileOf(
        *Array(14) { listen("Liked $it", "Liked Artist", ageDays = it + 1) },
        *Array(10) { listen("Other $it", "Other Artist", ageDays = it + 2) },
        *Array(8) { listen("Third $it", "Third Artist", ageDays = it + 3) },
    )

    private fun select(kind: RecoKind, pool: List<Candidate>, c: RankContext = ctx(profile), used: MutableSet<String> = HashSet()) =
        Ranker.select(kind, pool, c, used)

    private val related = listOf(Provenance(ProvKind.RELATED_TRACK, "Liked 1", 0.8))

    // What can be measured of a song: a calm one, a loud one, a slow night one, a soft one.
    private val CALM = SongFeatures(bpm = 68.0, gainDb = -16.0, trackTags = listOf("chill", "ambient"))
    private val LOUD = SongFeatures(bpm = 128.0, gainDb = -6.5, trackTags = listOf("dance", "party"))
    private val NIGHTLY = SongFeatures(bpm = 62.0, gainDb = -17.0, trackTags = listOf("night", "ambient"))
    private val SOFT = SongFeatures(bpm = 100.0, gainDb = -12.0, trackTags = listOf("chill"))
    private fun measured(vararg pairs: Pair<Candidate, SongFeatures>) = ctx(profile).copy(songFeatures = pairs.associate { it.first.identity to it.second })

    // --- exclusions ---

    @Test fun `a blocked artist is excluded from every playlist, collaborations included`() {
        val pool = listOf(
            candidate("Solo", "Blocked Artist"),
            candidate("Duo", "Liked Artist", extra = listOf("Blocked Artist")),
            candidate("Credit in title (feat. Blocked Artist)", "Liked Artist"),
            candidate("Fine", "Liked Artist"),
        )
        for (kind in RecoKind.entries) {
            val picks = select(kind, pool.map { it.copy(release = Release.ofDate(LocalDate.of(2026, 10, 1)), tags = listOf("chill acoustic night dance")) }, ctx(profile, blocked = setOf("blocked artist")))
            assertTrue("$kind", picks.none { "Blocked" in it.candidate.title || "Blocked" in it.candidate.artist || it.candidate.extraCredits.any { e -> "Blocked" in e } })
        }
    }

    @Test fun `a song already in the library is not recommended, under another id or spelling`() {
        val local = listOf(TrackIdentity.Entry("Hold The Girl", "Liked Artist", 244_000))
        val pool = listOf(
            candidate("Hold The Girl (Remastered)", "Liked Artist - Topic", id = "ytm:other", durationMs = 245_000),
            candidate("A New One", "Liked Artist"),
        )
        val picks = select(RecoKind.STYLE, pool, ctx(profile, local = local))
        assertEquals(listOf("A New One"), picks.map { it.candidate.title })
    }

    @Test fun `hidden and disliked songs never come back`() {
        val pool = listOf(candidate("Gone", "Liked Artist", id = "ytm:gone"), candidate("Stays", "Liked Artist"))
        val picks = select(RecoKind.STYLE, pool, ctx(profile, hidden = setOf("ytm:gone")))
        assertEquals(listOf("Stays"), picks.map { it.candidate.title })
    }

    @Test fun `junk is not offered`() {
        val pool = listOf(
            candidate("Song (Karaoke Version)", "Liked Artist"),
            candidate("Song - 8-Bit Emulation", "Liked Artist"),
            candidate("Long Mix", "Liked Artist", durationMs = 3_600_000),
            candidate("Good", "Liked Artist"),
        )
        assertEquals(listOf("Good"), select(RecoKind.STYLE, pool).map { it.candidate.title })
    }

    // --- the playlists differ ---

    @Test fun `discovery prefers unknown artists and style prefers the liked ones`() {
        val unknown = candidate("Unknown Gem", "Stranger", provenance = related)
        val liked = candidate("Liked New", "Liked Artist")
        val disc = select(RecoKind.DISCOVERY, listOf(unknown, liked))
        val style = select(RecoKind.STYLE, listOf(unknown, liked))
        assertEquals("Unknown Gem", disc.first().candidate.title)
        assertEquals("Liked New", style.first().candidate.title)
        // And the explanation names what was used.
        assertEquals(ReasonCode.EXPLORATION, disc.first().reason.code)
        assertEquals(ReasonCode.LISTENED_ARTIST, style.first().reason.code)
    }

    @Test fun `style needs a real tie to the listener`() {
        val stranger = candidate("Random", "Stranger", provenance = listOf(Provenance(ProvKind.GENRE, "Pop", 0.4)))
        assertTrue(select(RecoKind.STYLE, listOf(stranger)).isEmpty())
    }

    @Test fun `mood playlists only take songs with a cue for the mood`() {
        val calm = candidate("Soft Piano Lullaby", "Liked Artist", durationMs = 220_000)
        val loud = candidate("Club Bass Banger", "Liked Artist", durationMs = 190_000)
        val plain = candidate("Neutral Title", "Liked Artist")
        val pool = listOf(calm, loud, plain)
        val c = measured(calm to CALM, loud to LOUD)
        assertEquals(listOf("Soft Piano Lullaby"), select(RecoKind.CHILL, pool, c).map { it.candidate.title })
        assertEquals(listOf("Club Bass Banger"), select(RecoKind.ENERGY, pool, c).map { it.candidate.title })
        // Nothing is put in an ambience on a guess: "Neutral Title" is in none of the three.
        assertTrue(RecoKind.entries.filter { it in listOf(RecoKind.CHILL, RecoKind.NIGHT, RecoKind.ENERGY) }
            .all { k -> select(k, pool, c).none { it.candidate.title == "Neutral Title" } })
    }

    @Test fun `night and chill are told apart`() {
        val night = candidate("Midnight Nocturne", "Liked Artist", durationMs = 300_000)
        val chill = candidate("Sunday Acoustic Chill", "Liked Artist", durationMs = 200_000)
        val c = measured(night to NIGHTLY, chill to SOFT)
        val nightPicks = select(RecoKind.NIGHT, listOf(night, chill), c).map { it.candidate.title }
        val chillPicks = select(RecoKind.CHILL, listOf(night, chill), c).map { it.candidate.title }
        // Night is the stricter: a soft song is for Chill only; the nocturnal one is calm enough for both, and Night (drawn first) keeps it.
        assertEquals(listOf("Midnight Nocturne"), nightPicks)
        assertTrue("Sunday Acoustic Chill" in chillPicks)
        assertEquals(ReasonCode.NIGHT, select(RecoKind.NIGHT, listOf(night), c).first().reason.code)
        assertEquals(ReasonCode.CALM, select(RecoKind.CHILL, listOf(chill), c).first().reason.code)
    }

    @Test fun `a song found by a mood query is still judged on what it is`() {
        val byQuery = candidate("Plain Name", "Liked Artist", provenance = listOf(Provenance(ProvKind.MOOD_QUERY, "chill", 0.0, Mood.CHILL), Provenance(ProvKind.ARTIST, "Liked Artist", 0.8)))
        val againstIt = candidate("Hype Club Banger", "Liked Artist", provenance = byQuery.provenance)
        val picks = select(RecoKind.CHILL, listOf(byQuery, againstIt), measured(byQuery to CALM, againstIt to LOUD)).map { it.candidate.title }
        assertEquals(listOf("Plain Name"), picks)
    }

    @Test fun `the seven playlists are not copies of each other`() {
        val pool = listOf(
            candidate("Soft Piano Lullaby", "Liked Artist", durationMs = 220_000, release = Release.ofDate(LocalDate.of(2026, 9, 20))),
            candidate("Midnight Nocturne", "Other Artist", durationMs = 300_000),
            candidate("Club Bass Banger", "Third Artist", durationMs = 190_000),
        ) + (1..12).map { candidate("Style $it", "Liked Artist$it", provenance = listOf(Provenance(ProvKind.RELATED_TRACK, "Liked 1", 0.9))) } +
            (1..12).map { candidate("Plain $it", "Other Artist$it", provenance = listOf(Provenance(ProvKind.RELATED_TRACK, "Other 1", 0.6))) }
        val used = HashSet<String>()
        val c = ctx(profile).copy(songFeatures = mapOf(pool[0].identity to CALM, pool[1].identity to NIGHTLY, pool[2].identity to LOUD))
        val lists = listOf(RecoKind.NEW, RecoKind.ENERGY, RecoKind.NIGHT, RecoKind.CHILL, RecoKind.STYLE, RecoKind.DISCOVERY, RecoKind.DAILY)
            .associateWith { Ranker.select(it, pool, c, used).map { p -> p.candidate.identity } }
        // No song is in two playlists of the same renewal.
        val all = lists.values.flatten()
        assertEquals(all.size, all.toSet().size)
        assertNotEquals(lists[RecoKind.CHILL], lists[RecoKind.NIGHT])
        assertNotEquals(lists[RecoKind.STYLE], lists[RecoKind.DAILY])
    }

    // --- new releases ---

    private fun newOf(release: Release, provenance: List<Provenance> = listOf(Provenance(ProvKind.ARTIST, "Liked Artist", 0.8))) =
        select(RecoKind.NEW, listOf(candidate("Fresh", "Liked Artist", release = release, provenance = provenance)))

    @Test fun `only a trusted recent date makes a new release`() {
        assertEquals(1, newOf(Release.ofDate(LocalDate.of(2026, 9, 12))).size)
        // Released years ago, however recently it was added to a catalogue.
        assertTrue(newOf(Release.ofDate(LocalDate.of(2019, 5, 3))).isEmpty())
        // No date, or only a year that is not this one.
        assertTrue(newOf(Release.Unknown).isEmpty())
        assertTrue(newOf(Release.ofYear(2021)).isEmpty())
        // Only a year, even this year's: it cannot tell a new song from an old one on a reissue dated this year.
        assertTrue(newOf(Release.ofYear(2026)).isEmpty())
        // A date in the future is not a release the listener can play.
        assertTrue(newOf(Release.ofDate(LocalDate.of(2026, 12, 25))).isEmpty())
    }

    @Test fun `a new release by an artist the listener plays says so`() {
        val pick = newOf(Release.ofDate(LocalDate.of(2026, 10, 3))).single()
        assertEquals(ReasonCode.NEW_FROM_ARTIST, pick.reason.code)
        assertEquals("Liked Artist", pick.reason.subject)
    }

    // --- variety ---

    @Test fun `one artist cannot take over a playlist`() {
        val pool = (1..20).map { candidate("Track $it", "Liked Artist") } + (1..6).map { candidate("Other $it", "Other Artist$it", provenance = related) }
        val picks = select(RecoKind.STYLE, pool)
        assertTrue(picks.count { it.candidate.artist == "Liked Artist" } <= Ranker.specOf(RecoKind.STYLE).maxPerArtist)
        val daily = select(RecoKind.DAILY, pool)
        assertTrue(daily.count { it.candidate.artist == "Liked Artist" } <= 1)
    }

    @Test fun `what was proposed lately comes back last`() {
        val a = candidate("Seen Lately", "Liked Artist", provenance = listOf(Provenance(ProvKind.ARTIST, "Liked Artist", 0.8)))
        val b = candidate("Fresh One", "Third Artist", provenance = listOf(Provenance(ProvKind.ARTIST, "Third Artist", 0.8)))
        val history = mapOf(a.identity to NOW - 3 * DAY)
        val picks = select(RecoKind.STYLE, listOf(a, b), ctx(profile, history = history))
        assertEquals("Fresh One", picks.first().candidate.title)
    }

    @Test fun `a small pool is not padded`() {
        val picks = select(RecoKind.STYLE, listOf(candidate("Only one", "Liked Artist")))
        assertEquals(1, picks.size)
        assertTrue(picks.size < Ranker.specOf(RecoKind.STYLE).minimum)
    }

    @Test fun `a disliked artist is nudged down in the playlist it was skipped in, not excluded`() {
        val skipped = profileOf(
            *Array(8) { listen("L$it", "Liked Artist", ageDays = it + 1) },
            *Array(8) { listen("L$it", "Meh Artist", ageDays = it + 1) },
            *Array(4) { listen("Skip$it", "Meh Artist", Outcome.SKIPPED_EARLY, ageDays = 1, context = "night") },
        )
        val a = candidate("From Liked", "Liked Artist")
        val b = candidate("From Meh", "Meh Artist")
        val night = Ranker.select(RecoKind.STYLE, listOf(b, a), ctx(skipped), HashSet()).map { it.candidate.title }
        assertEquals("From Liked", night.first())
        assertTrue("From Meh" in night)
    }

    @Test fun `content farms and fan uploads are not music recommendations`() {
        val pool = listOf(
            candidate("On the Floor", "Dance Hits 2014"),
            candidate("Lofi Chill", "LO-FI BEATS"),
            candidate("Ambient Night", "Deep Sleep Music Collective"),
            candidate("bloodstream (Lyrics)", "Amazing Lyrics"),
            candidate("After Hours (Music Video)", "Music Drenalin"),
            candidate("Real Song", "Liked Artist"),
        )
        assertEquals(listOf("Real Song"), select(RecoKind.STYLE, pool).map { it.candidate.title })
    }

    @Test fun `a remix of a song the listener has is not new to them, except in the energetic playlist`() {
        val known = profileOf(
            *Array(14) { listen("Liked $it", "Liked Artist", ageDays = it + 1) },
            listen("Alterlife", "Liked Artist"),
        )
        val remix = candidate("Alterlife (Seiho Remix)", "Liked Artist", id = "ytm:remix")
        val fresh = candidate("Brand New", "Liked Artist", id = "ytm:fresh")
        val c = ctx(known)
        assertEquals(listOf("Brand New"), Ranker.select(RecoKind.STYLE, listOf(remix, fresh), c, HashSet()).map { it.candidate.title })
        assertEquals(listOf("Brand New"), Ranker.select(RecoKind.DAILY, listOf(remix, fresh), c, HashSet()).map { it.candidate.title })
        val energetic = candidate("Alterlife (Club Remix)", "Liked Artist", id = "ytm:club")
        val measuredCtx = c.copy(songFeatures = mapOf(energetic.identity to LOUD))
        assertEquals(listOf("Alterlife (Club Remix)"), Ranker.select(RecoKind.ENERGY, listOf(energetic), measuredCtx, HashSet()).map { it.candidate.title })
    }

    @Test fun `a discovery is mostly unknown artists`() {
        val known = (1..20).map { candidate("Known Song $it", "Liked Artist $it", provenance = listOf(Provenance(ProvKind.ARTIST, "Liked Artist", 0.95))) }
        val unknown = (1..20).map { candidate("Stranger Song $it", "Stranger $it", provenance = related) }
        // The first ten "known" artists are artists the listener has files of.
        val local = (1..10).map { TrackIdentity.Entry("Their File $it", "Liked Artist $it", 200_000) }
        val picks = select(RecoKind.DISCOVERY, known + unknown, ctx(profile, local = local))
        val knownCount = picks.count { it.candidate.artist.startsWith("Liked Artist") && it.candidate.artist.removePrefix("Liked Artist ").toInt() <= 10 }
        assertTrue("known artists: $knownCount of ${picks.size}", knownCount <= picks.size * 3 / 10 + 1)
        assertTrue(picks.count { it.candidate.artist.startsWith("Stranger") } >= picks.size / 2)
    }

    @Test fun `an ambience query alone is not enough - the song needs a tie to the listener`() {
        val farmed = candidate(
            "Soft Piano Lullaby", "Someone Else",
            provenance = listOf(Provenance(ProvKind.MOOD_QUERY, "chill", 0.0, Mood.CHILL)),
        )
        assertTrue(select(RecoKind.CHILL, listOf(farmed)).isEmpty())
    }

    @Test fun `video clips are not songs`() {
        val pool = listOf(
            candidate("Hold The Girl (Official Music Video)", "Liked Artist"),
            candidate("Je t'accuse (Clip officiel)", "Liked Artist"),
            candidate("Song [MV]", "Liked Artist"),
            candidate("Song (Dance Practice)", "Liked Artist"),
            candidate("Song (Live Performance Video)", "Liked Artist"),
            candidate("Real Song", "Liked Artist"),
        )
        assertEquals(listOf("Real Song"), select(RecoKind.STYLE, pool).map { it.candidate.title })
    }
}
