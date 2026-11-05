package com.ikko.aura.ai.playlist

import com.ikko.aura.data.model.Track
import com.ikko.aura.reco.ArtistNames
import com.ikko.aura.reco.PlaylistSequencer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A library in memory, filtered by the query exactly as AiPlaylistDao's SQL filters it ([TrackQuery.admits]). */
class FakeTrackSource(
    private val tracks: List<Track>,
    private val rowsById: Map<String, AiTrackRow>,
    private val refused: Set<String> = emptySet(),
) : AiTrackSource {
    val queries = mutableListOf<TrackQuery>()
    override suspend fun library() = tracks
    override suspend fun rows(query: TrackQuery): List<AiTrackRow> {
        queries += query
        return rowsById.values.filter { it.trackId !in refused && query.admits(it.bpm) }.take(query.limit)
    }
    override suspend fun refused() = refused
    override suspend fun tagged() = rowsById.keys
}

class AiPlaylistGeneratorTest {

    private val uri = android.net.TestUri.INSTANCE

    private data class Song(val id: String, val artist: String, val bpm: Double = 0.0, val genres: List<String> = emptyList(), val gain: Double? = null, val ms: Long = 200_000, val album: String = "")

    private fun library(songs: List<Song>, refused: Set<String> = emptySet()): FakeTrackSource {
        val tracks = songs.map { s ->
            Track(id = s.id, title = s.id, artist = s.artist, album = s.album.ifEmpty { s.id }, artistId = 0, albumId = s.album.ifEmpty { s.id }.hashCode().toLong(), artworkUri = null, contentUri = uri, durationMs = s.ms)
        }
        val rows = songs.associate { s ->
            s.id to AiTrackRow(s.id, s.bpm, s.genres.joinToString("\u0000"), s.gain, "", "[]", 0.0, false, 0)
        }
        return FakeTrackSource(tracks, rows, refused)
    }

    /** 60 songs by 12 artists: house at 124, hip-hop at 90, ambient at 70, of rising loudness within each style. */
    private fun mixed(): FakeTrackSource = library(
        (0 until 60).map { i ->
            val style = i % 3
            Song(
                id = "t$i",
                artist = "artist${i % 12}",
                bpm = when (style) { 0 -> 122.0 + i % 5; 1 -> 88.0 + i % 4; else -> 68.0 + i % 3 },
                genres = listOf(when (style) { 0 -> "Deep House"; 1 -> "French Rap"; else -> "Ambient" }),
                gain = -2.0 - (i % 10),
            )
        },
    )

    private fun generate(source: AiTrackSource, intent: PlaylistIntent) = kotlinx.coroutines.runBlocking { AiPlaylistGenerator(source).generate(intent, "seed") }

    private fun ready(outcome: AiPlaylistOutcome) = outcome as AiPlaylistOutcome.Ready

    @Test fun `an empty library is said so`() = runTest {
        assertEquals(AiPlaylistOutcome.LibraryEmpty, AiPlaylistGenerator(library(emptyList())).generate(PlaylistIntent.EMPTY))
    }

    @Test fun `the tempo asked for goes to the database as its windows`() {
        val source = mixed()
        generate(source, PlaylistIntent(targetBpm = 124, bpmTolerance = 4))
        assertEquals(TrackQuery(false, 120.0, 128.0, 60.0, 64.0, 240.0, 256.0), source.queries.first())
    }

    @Test fun `only songs of the genre asked for, within the tempo, when there are enough`() {
        val r = ready(generate(mixed(), PlaylistIntent(targetBpm = 124, bpmTolerance = 4, genresIncluded = listOf("house"))))
        assertEquals(Relaxation.NONE, r.plan.relaxation)
        assertTrue(r.tracks.isNotEmpty())
        assertTrue(r.tracks.all { it.id.removePrefix("t").toInt() % 3 == 0 })
    }

    @Test fun `a refused genre never comes back, even when everything else is loosened`() {
        val r = ready(generate(mixed(), PlaylistIntent(genresIncluded = listOf("jazz"), genresExcluded = listOf("rap"))))
        assertEquals("no jazz in the library: any genre", Relaxation.ANY_GENRE, r.plan.relaxation)
        assertTrue(r.tracks.isNotEmpty())
        assertTrue("no rap", r.tracks.none { it.id.removePrefix("t").toInt() % 3 == 1 })
    }

    @Test fun `a tempo no song has is widened, then dropped`() {
        val r = ready(generate(mixed(), PlaylistIntent(targetBpm = 200, bpmTolerance = 3)))
        assertEquals(Relaxation.ANY_TEMPO, r.plan.relaxation)
        assertTrue(r.tracks.size >= AiPlaylistGenerator.MIN_TRACKS)
    }

    @Test fun `half-time songs answer a fast tempo`() {
        // Hip-hop tagged at 88-91 is felt at 176-182: a request for 180 finds it without loosening.
        val r = ready(generate(mixed(), PlaylistIntent(targetBpm = 180, bpmTolerance = 4)))
        assertEquals(Relaxation.NONE, r.plan.relaxation)
        assertTrue(r.tracks.all { it.id.removePrefix("t").toInt() % 3 == 1 })
    }

    @Test fun `when everything is refused, nothing matches`() {
        val outcome = generate(mixed(), PlaylistIntent(genresExcluded = listOf("house", "rap", "ambient")))
        assertTrue(outcome is AiPlaylistOutcome.NoMatch)
    }

    @Test fun `disliked songs and blocked artists are never proposed`() {
        val songs = (0 until 20).map { Song("t$it", "a${it % 5}", genres = listOf("Jazz")) }
        val r = ready(generate(library(songs, refused = setOf("t0", "t1", "t2")), PlaylistIntent(genresIncluded = listOf("jazz"))))
        assertTrue(r.tracks.none { it.id in setOf("t0", "t1", "t2") })
    }

    @Test fun `the length asked for is met, give or take a song`() {
        for (minutes in listOf(20, 45, 90)) {
            val r = ready(generate(mixed(), PlaylistIntent(targetDurationMinutes = minutes)))
            val target = minutes * 60_000L
            assertTrue("$minutes min: ${r.totalDurationMs}", r.totalDurationMs >= target || r.tracks.size == 60)
            assertTrue("$minutes min: ${r.totalDurationMs}", r.totalDurationMs < target + 200_000L)
        }
    }

    @Test fun `no artist follows itself, and none takes more than its share`() {
        val r = ready(generate(mixed(), PlaylistIntent(targetDurationMinutes = 120)))
        val artists = r.tracks.map { ArtistNames.primaryKey(it.artist) }
        assertTrue(artists.zipWithNext().none { (a, b) -> a == b })
        val cap = maxOf(AiPlaylistGenerator.MIN_PER_ARTIST, kotlin.math.ceil(r.tracks.size * 1.25 * AiPlaylistGenerator.MAX_ARTIST_SHARE).toInt() + 2)
        assertTrue(artists.groupingBy { it }.eachCount().values.all { it <= cap })
    }

    @Test fun `each song comes once`() {
        val r = ready(generate(mixed(), PlaylistIntent(targetDurationMinutes = 300)))
        assertEquals(r.tracks.size, r.tracks.map { it.id }.toSet().size)
    }

    @Test fun `a workout builds, the energy rises along the playlist`() {
        val songs = (0 until 40).map { i -> Song("t$i", "a${i % 10}", bpm = 150.0, genres = listOf("Electronic"), gain = -1.0 - (i * 7 % 40) / 3.5) }
        val r = ready(generate(library(songs), PlaylistIntent(targetBpm = 150, bpmTolerance = 15, energy = EnergyLevel.HIGH, mood = "workout", targetDurationMinutes = 60)))
        val gains = songs.associate { it.id to it.gain!! }
        val energies = r.tracks.map { AiPlaylistGenerator.energyOf(gains.getValue(it.id))!! }
        val half = energies.size / 2
        assertTrue("second half louder: $energies", energies.drop(half).average() > energies.take(half).average())
    }

    @Test fun `songs the scanner has not read yet still count, as unknown`() {
        val tracks = (0 until 10).map { Track(id = "u$it", title = "u$it", artist = "x$it", album = "", artistId = 0, albumId = it.toLong(), artworkUri = null, contentUri = uri, durationMs = 180_000) }
        val r = ready(generate(FakeTrackSource(tracks, emptyMap()), PlaylistIntent(targetBpm = 120)))
        assertTrue(r.tracks.isNotEmpty())
    }

    @Test fun `ReplayGain reads as energy, loud masters high, quiet ones low, none unknown`() {
        assertEquals(1.0, AiPlaylistGenerator.energyOf(-14.0)!!, 0.0)
        assertEquals(0.0, AiPlaylistGenerator.energyOf(2.0)!!, 0.0)
        assertTrue(AiPlaylistGenerator.energyOf(-9.0)!! > AiPlaylistGenerator.energyOf(-4.0)!!)
        assertEquals(null, AiPlaylistGenerator.energyOf(null))
        assertEquals(null, AiPlaylistGenerator.energyOf(Double.NaN))
    }

    @Test fun `the order is the sequencer's, what it measures of the result is what it guarantees`() {
        val r = ready(generate(mixed(), PlaylistIntent(targetDurationMinutes = 60)))
        val seq = r.tracks.map { com.ikko.aura.reco.SeqItem(it.id, it.id, ArtistNames.primaryKey(it.artist)) }
        assertTrue(PlaylistSequencer.minArtistGap(seq) >= 2)
    }
}
