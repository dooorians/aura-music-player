package com.ikko.aura.reco

import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore(
    var inputs: ProfileInputs,
    var local: List<TrackIdentity.Entry> = emptyList(),
    var blocked: Set<String> = emptySet(),
    var hidden: Set<String> = emptySet(),
) : RecoStore {
    val generated = HashMap<RecoKind, Long>()
    /** The playlists as generated: what a renewal replaces. */
    val playlists = HashMap<RecoKind, List<Pick>>()
    val kept = HashMap<RecoKind, String>()
    /** The queue being played: a renewal must never change it. */
    val playerQueue = mutableListOf("local:1", "ytm:playing", "ytm:next")
    val shown = HashMap<String, Long>()

    override suspend fun profileInputs(nowMs: Long) = inputs.copy(nowMs = nowMs)
    override suspend fun localIndex() = TrackIdentity.Index(local)
    override suspend fun blockedArtists() = blocked
    override suspend fun hidden() = hidden
    override suspend fun history() = shown
    override suspend fun generatedAt() = generated
    override suspend fun sourceOrder() = listOf("local", "ytm", "jiosaavn")
    override suspend fun commit(kind: RecoKind, picks: List<Pick>, nowMs: Long) {
        playlists[kind] = picks
        generated[kind] = nowMs
        picks.forEach { shown[it.candidate.identity] = nowMs }
    }
    override suspend fun keep(kind: RecoKind, status: String, nowMs: Long) { kept[kind] = status }
}

private class FakeSource(
    override val id: String,
    private val up: Boolean = true,
    private val failing: Boolean = false,
    private val catalogue: (String) -> List<Candidate> = { emptyList() },
    private val verdict: (Candidate) -> Verification = { Verification(true) },
) : CandidateSource {
    override val name = id
    var searches = 0
    override suspend fun available() = up
    override suspend fun search(query: String, provenance: Provenance): List<Candidate> {
        searches++
        if (failing) throw IOException("down")
        return catalogue(query).map { it.copy(sourceId = id, provenance = listOf(provenance)) }
    }
    override suspend fun related(title: String, artist: String, provenance: Provenance): List<Candidate> {
        if (failing) throw IOException("down")
        return catalogue("related:$artist:$title").map { it.copy(sourceId = id, provenance = listOf(provenance)) }
    }
    override suspend fun verify(candidate: Candidate) = verdict(candidate)
}

class RecoPipelineTest {

    private val history = List(14) { listen("Liked $it", "Liked Artist", ageDays = it + 1) } +
        List(8) { listen("Other $it", "Other Artist", ageDays = it + 1) }

    private fun store(vararg more: ListenRecord) = FakeStore(ProfileInputs(listens = history + more, nowMs = NOW))

    private fun songs(artist: String, count: Int, prefix: String = "New") =
        (1..count).map { candidate("$prefix $artist $it", artist, id = "ytm:$prefix-$artist-$it", release = Release.ofYear(2020)) }

    private fun pipeline(store: RecoStore, vararg sources: CandidateSource, now: Long = NOW) =
        RecoPipeline(store, { sources.toList() }, { now }, ZONE)

    @Test fun `nothing is done when nothing is due`() = runBlocking {
        val s = store()
        RecoKind.entries.forEach { s.generated[it] = NOW - 1 * 3_600_000L }
        val source = FakeSource("ytm", catalogue = { songs("Liked Artist", 5) })
        val result = pipeline(s, source).refresh()
        assertEquals(RunStatus.NOTHING_DUE, result.status)
        assertEquals(0, source.searches)
    }

    /** What a source answers: songs by distinct artists next to each song the listener plays. */
    private fun relatedCatalogue(q: String): List<Candidate> =
        if (q.startsWith("related:")) (1..4).map { i -> candidate("Rel ${q.hashCode() and 0xffff} $i", "Rel Artist ${q.hashCode() and 0xffff} $i", id = "ytm:r${q.hashCode() and 0xffff}-$i") }
        else emptyList()

    @Test fun `only the daily mix is renewed the next day`() = runBlocking {
        val s = store()
        val yesterday = NOW - 20 * 3_600_000L // the day before, in UTC
        RecoKind.entries.forEach { s.generated[it] = yesterday }
        val source = FakeSource("ytm", catalogue = ::relatedCatalogue)
        val result = pipeline(s, source).refresh()
        assertEquals(RunStatus.UPDATED, result.status)
        assertTrue(result.updated.all { it == RecoKind.DAILY })
        assertTrue(RecoKind.DAILY in s.playlists)
        assertFalse(RecoKind.STYLE in s.playlists)
    }

    @Test fun `weekly playlists are renewed after seven days`() = runBlocking {
        val s = store()
        RecoKind.entries.filter { it != RecoKind.DAILY }.forEach { s.generated[it] = NOW - 8 * DAY }
        s.generated[RecoKind.DAILY] = NOW - 1_000
        val source = FakeSource("ytm", catalogue = { (1..12).flatMap { i -> songs("Liked Artist $i", 1) } })
        val result = pipeline(s, source).refresh()
        assertTrue(RecoKind.DAILY !in result.updated)
        assertTrue(RecoKind.STYLE in result.updated || RecoKind.STYLE in result.kept)
    }

    @Test fun `with every source down the previous playlists stay`() = runBlocking {
        val s = store()
        val previous = listOf(Pick(candidate("Old", "Liked Artist"), Reason(ReasonCode.NONE), 1.0))
        s.playlists[RecoKind.STYLE] = previous
        val down = FakeSource("ytm", up = false)
        val result = pipeline(s, down).refresh(force = true)
        assertEquals(RunStatus.NO_SOURCE, result.status)
        assertEquals(previous, s.playlists[RecoKind.STYLE])
        assertEquals("kept", s.kept[RecoKind.STYLE])
    }

    @Test fun `sources that all fail keep what was there`() = runBlocking {
        val s = store()
        val previous = listOf(Pick(candidate("Old", "Liked Artist"), Reason(ReasonCode.NONE), 1.0))
        s.playlists[RecoKind.DAILY] = previous
        val result = pipeline(s, FakeSource("ytm", failing = true)).refresh(force = true)
        assertEquals(RunStatus.SOURCES_FAILED, result.status)
        assertEquals(previous, s.playlists[RecoKind.DAILY])
    }

    @Test fun `a source that fails does not stop the others`() = runBlocking {
        val s = store()
        val good = FakeSource("jiosaavn", catalogue = { (1..15).map { i -> candidate("Song $i", "Liked Artist $i", id = "jiosaavn:$i") } })
        val result = pipeline(s, FakeSource("ytm", failing = true), good).refresh(force = true)
        assertEquals(RunStatus.UPDATED, result.status)
        assertTrue(s.playlists.values.flatten().all { it.candidate.sourceId == "jiosaavn" })
    }

    @Test fun `a blocked artist is not in any playlist, in a collaboration either`() = runBlocking {
        val s = store()
        s.blocked = setOf("blocked artist")
        val source = FakeSource("ytm", catalogue = {
            (1..10).map { candidate("Fine $it", "Liked Artist $it", id = "ytm:f$it") } +
                candidate("Solo", "Blocked Artist", id = "ytm:b1") +
                candidate("Duet (feat. Blocked Artist)", "Liked Artist", id = "ytm:b2") +
                candidate("Duo", "Liked Artist", id = "ytm:b3", extra = listOf("Blocked Artist"))
        })
        pipeline(s, source).refresh(force = true)
        val ids = s.playlists.values.flatten().map { it.candidate.id }
        assertTrue(ids.isNotEmpty())
        assertTrue(ids.none { it in setOf("ytm:b1", "ytm:b2", "ytm:b3") })
    }

    @Test fun `songs the listener already has are not proposed`() = runBlocking {
        val s = store()
        s.local = listOf(TrackIdentity.Entry("Have It", "Liked Artist 1", 200_000))
        val source = FakeSource("ytm", catalogue = {
            listOf(candidate("Have It (Remastered 2020)", "Liked Artist 1 - Topic", id = "ytm:dup")) +
                (2..12).map { candidate("Song $it", "Liked Artist $it", id = "ytm:s$it") }
        })
        pipeline(s, source).refresh(force = true)
        assertTrue(s.playlists.values.flatten().none { it.candidate.id == "ytm:dup" })
    }

    @Test fun `without history or library nothing is invented`() = runBlocking {
        val s = FakeStore(ProfileInputs(nowMs = NOW))
        val source = FakeSource("ytm", catalogue = { songs("Anyone", 10) })
        val result = pipeline(s, source).refresh(force = true)
        assertEquals(RunStatus.NO_PROFILE, result.status)
        assertEquals(0, source.searches)
        assertTrue(s.playlists.isEmpty())
    }

    @Test fun `typed-in artists are enough to start, and one artist alone does not fill a playlist`() = runBlocking {
        val artists = listOf("Typed One", "Typed Two", "Typed Three", "Typed Four", "Typed Five", "Typed Six")
        val s = FakeStore(ProfileInputs(seedArtists = artists, nowMs = NOW))
        val source = FakeSource("ytm", catalogue = { q ->
            if (q in artists) (1..4).map { candidate("$q Song $it", q, id = "ytm:$q-$it") } else emptyList()
        })
        val result = pipeline(s, source).refresh(force = true)
        assertEquals(RunStatus.UPDATED, result.status)
        assertTrue(s.playlists.isNotEmpty())

        // A single artist is not a profile to fill seven playlists from: nothing is padded.
        val one = FakeStore(ProfileInputs(seedArtists = listOf("Typed One"), nowMs = NOW))
        val alone = pipeline(one, source).refresh(force = true)
        assertTrue(alone.updated.isEmpty())
        assertTrue(one.playlists.isEmpty())
    }

    @Test fun `a song a source refuses is replaced by the next one`() = runBlocking {
        val s = store()
        val source = FakeSource(
            "ytm",
            catalogue = { (1..14).map { candidate("Song $it", "Liked Artist $it", id = "ytm:s$it") } },
            verdict = { c -> if (c.id == "ytm:s1" || c.id == "ytm:s2") Verification(false) else Verification(true) },
        )
        pipeline(s, source).refresh(force = true)
        val ids = s.playlists.values.flatten().map { it.candidate.id }
        assertTrue(ids.isNotEmpty())
        assertTrue(ids.none { it == "ytm:s1" || it == "ytm:s2" })
    }

    @Test fun `new releases need a date the source stated`() = runBlocking {
        val s = store()
        val source = FakeSource(
            "ytm",
            catalogue = {
                (1..6).map { candidate("Fresh $it", "Fresh Artist $it", id = "ytm:fresh$it") } +
                    (1..6).map { candidate("Reupload $it", "Old Artist $it", id = "ytm:old$it") }
            },
            // The page of a fresh song says "Released on" a recent day; a re-upload of an old one says nothing.
            verdict = { c ->
                if (c.id.startsWith("ytm:fresh")) Verification(true, Release.ofDate(LocalDate.of(2026, 10, 2)))
                else Verification(true, Release.Unknown)
            },
        )
        pipeline(s, source).refresh(force = true)
        val new = s.playlists[RecoKind.NEW].orEmpty().map { it.candidate.id }
        assertTrue(new.isNotEmpty())
        assertTrue(new.all { it.startsWith("ytm:fresh") })
    }

    @Test fun `a renewal leaves the queue being played alone`() = runBlocking {
        val s = store()
        val before = s.playerQueue.toList()
        val source = FakeSource("ytm", catalogue = { (1..14).map { candidate("Song $it", "Liked Artist $it", id = "ytm:s$it") } })
        pipeline(s, source).refresh(force = true)
        assertEquals(before, s.playerQueue)
        // It only wrote playlists; and a failing renewal after it leaves them as they were.
        val written = s.playlists.toMap()
        pipeline(s, FakeSource("ytm", failing = true)).refresh(force = true)
        assertEquals(written, s.playlists)
        assertEquals(before, s.playerQueue)
    }

    @Test fun `the same songs are not proposed again at once`() = runBlocking {
        val s = store()
        val pool = (1..40).map { candidate("Song $it", "Liked Artist $it", id = "ytm:s$it") }
        val source = FakeSource("ytm", catalogue = { pool })
        pipeline(s, source).refresh(force = true)
        val first = s.playlists[RecoKind.STYLE].orEmpty().map { it.candidate.id }.toSet()
        // Next week the same pool is offered: what was proposed comes after what was not.
        val later = pipeline(s, source, now = NOW + 8 * DAY)
        later.refresh(force = true)
        val second = s.playlists[RecoKind.STYLE].orEmpty().map { it.candidate.id }.toSet()
        assertTrue(first.isNotEmpty() && second.isNotEmpty())
        assertTrue("overlap ${first.intersect(second).size} of ${first.size}", first.intersect(second).size < first.size)
    }

    @Test fun `a song of an album the listener has is dropped once its page names the album`() = runBlocking {
        val s = store()
        s.local = listOf(TrackIdentity.Entry("Anaheim Star", "Rebecca Black", 166_000, album = "Age of the Exhibitionist"))
        val source = FakeSource(
            "ytm",
            catalogue = { (1..12).map { candidate("Song $it", "Liked Artist $it", id = "ytm:s$it") } +
                listOf(candidate("Speakerphone", "rebecca", id = "ytm:album-track", provenance = listOf(Provenance(ProvKind.ARTIST, "Liked Artist", 0.99)))) },
            // A search result does not say which album a song is on; its page does.
            verdict = { c -> if (c.id == "ytm:album-track") Verification(true, album = "Age of the Exhibitionist") else Verification(true) },
        )
        pipeline(s, source).refresh(force = true)
        val ids = s.playlists.values.flatten().map { it.candidate.id }
        assertTrue(ids.isNotEmpty())
        assertFalse("ytm:album-track" in ids)
    }

    @Test fun `the same song as a local one under the channel's own name is not proposed`() = runBlocking {
        val s = store()
        s.local = listOf(TrackIdentity.Entry("Anaheim Star", "Rebecca Black", 166_000))
        val source = FakeSource("ytm", catalogue = {
            (1..12).map { candidate("Song $it", "Liked Artist $it", id = "ytm:s$it") } +
                candidate("Anaheim Star", "rebecca", id = "ytm:alias", durationMs = 166_000)
        })
        pipeline(s, source).refresh(force = true)
        assertTrue(s.playlists.values.flatten().none { it.candidate.id == "ytm:alias" })
    }

    @Test fun `only songs come out of YouTube Music, not videos`() = runBlocking {
        val s = store()
        val source = FakeSource(
            "ytm",
            catalogue = {
                (1..12).map { candidate("Song $it", "Liked Artist $it", id = "ytm:s$it") } +
                    // Listed beside a song by YouTube, not vouched for as a song: a talk, a clip.
                    candidate("Why that song works", "Some Channel", id = "ytm:video", provenance = listOf(Provenance(ProvKind.ARTIST, "Liked Artist", 0.99)))
                        .copy(confirmedSong = false) +
                    // Listed the same way but the page is an official release.
                    candidate("Real Song From Related", "Liked Artist 99", id = "ytm:related-song")
                        .copy(confirmedSong = false)
            },
            verdict = { c ->
                when (c.id) {
                    "ytm:video" -> Verification(true, isSong = false)
                    "ytm:related-song" -> Verification(true, isSong = true)
                    else -> Verification(true)
                }
            },
        )
        pipeline(s, source).refresh(force = true)
        val ids = s.playlists.values.flatten().map { it.candidate.id }
        assertFalse("ytm:video" in ids)
        assertTrue(ids.isNotEmpty())
    }

    @Test fun `an unconfirmed entry is dropped when its page cannot be read`() = runBlocking {
        val s = store()
        val source = FakeSource(
            "ytm",
            catalogue = {
                (1..12).map { candidate("Song $it", "Liked Artist $it", id = "ytm:s$it") } +
                    candidate("Maybe A Video", "Liked Artist", id = "ytm:maybe").copy(confirmedSong = false)
            },
            verdict = { c -> if (c.id == "ytm:maybe") Verification(null) else Verification(true) },
        )
        pipeline(s, source).refresh(force = true)
        assertTrue(s.playlists.values.flatten().none { it.candidate.id == "ytm:maybe" })
    }
}
