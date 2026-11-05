package com.ikko.aura.ai.playlist

import com.ikko.aura.data.model.Track
import com.ikko.aura.reco.ArtistNames
import com.ikko.aura.reco.Diversifier
import com.ikko.aura.reco.PlaylistSequencer
import com.ikko.aura.reco.SeqItem
import com.ikko.aura.reco.Tempo
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.tanh

/** Where the generator reads the library from: Room and the media index on a phone, lists in tests. */
interface AiTrackSource {
    /** The songs of the library. */
    suspend fun library(): List<Track>
    /** [AiPlaylistDao.candidates] for [query]. */
    suspend fun rows(query: TrackQuery): List<AiTrackRow>
    /** [AiPlaylistDao.refusedIds]. */
    suspend fun refused(): Set<String>
    /** [AiPlaylistDao.taggedIds]. */
    suspend fun tagged(): Set<String>
}

sealed interface AiPlaylistOutcome {
    data class Ready(val tracks: List<Track>, val totalDurationMs: Long, val plan: AiPlaylistPlan) : AiPlaylistOutcome
    data class NoMatch(val plan: AiPlaylistPlan) : AiPlaylistOutcome
    data object LibraryEmpty : AiPlaylistOutcome
}

/**
 * From an intent to a playlist of the listener's own songs, in four steps:
 *  1. the database is asked for the songs whose tempo fits ([AiPlaylistPlanner] decides the windows); refused genres are
 *     removed, and the asked-for genres kept, from what the files, the stats and the artists' tags say;
 *  2. too few songs (fewer than [MIN_TRACKS], or than the library has), and the constraints are loosened one step at a
 *     time ([AiPlaylistPlanner.relax]): a wider tempo, then any genre, then any tempo. Refused genres never come back;
 *  3. each song is weighed (genre, tempo, energy, the listener's taste) and the set is chosen by MMR ([Diversifier]):
 *     the best songs, but a second song of an artist or an album must beat a song of someone else. The set stops at the
 *     length asked for;
 *  4. the set is ordered by the app's sequencer ([PlaylistSequencer]): the energy follows the plan's arc, tempos follow
 *     each other (half and double time included), and an artist never comes back within three songs.
 */
class AiPlaylistGenerator(private val source: AiTrackSource) {

    private class Candidate(
        val track: Track,
        val bpm: Double?,
        val energy: Double?,
        val genres: List<String>,
        val affinity: Double,
        val liked: Boolean,
        val plays: Int,
    ) {
        val artistKey: String = ArtistNames.primaryKey(track.artist)
        val albumKey: String = if (track.album.isBlank()) "" else ArtistNames.key(track.album) + "|" + artistKey
        var relevance = 0.0
    }

    suspend fun generate(intent: PlaylistIntent, seed: String = ""): AiPlaylistOutcome {
        val job = currentCoroutineContext()[Job]
        val cancel = { job?.ensureActive(); Unit }
        val library = source.library()
        if (library.isEmpty()) return AiPlaylistOutcome.LibraryEmpty
        val byId = library.associateBy { it.id }
        val refused = source.refused()
        val tagged = source.tagged()
        val genreKeys = HashMap<String, String>()
        val artistGenres = HashMap<String, List<String>>()
        val needed = minOf(MIN_TRACKS, library.count { it.id !in refused }.coerceAtLeast(1))

        var plan = AiPlaylistPlanner.plan(intent)
        var pool: List<Candidate>
        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = source.rows(plan.query)
            val fromRows = rows.mapNotNull { r ->
                val t = byId[r.trackId] ?: return@mapNotNull null
                if (t.id in refused) return@mapNotNull null
                Candidate(
                    track = t,
                    bpm = Tempo.sanitize(r.bpm),
                    energy = energyOf(r.replayGainDb),
                    genres = genresOf(r, genreKeys, artistGenres),
                    affinity = r.affinity,
                    liked = r.liked,
                    plays = r.playCount,
                )
            }
            // Songs whose file has not been read yet: unknown tempo and genre, which no tempo window refuses.
            val unread = library.filter { it.id !in tagged && it.id !in refused }.map { Candidate(it, null, null, emptyList(), 0.0, false, 0) }
            pool = (fromRows + unread)
                .filterNot { c -> plan.exclude.any { ex -> c.genres.any { g -> AiPlaylistPlanner.genreMatches(ex, g) } } }
                .filter { c -> !plan.strictGenres || plan.include.any { want -> c.genres.any { g -> AiPlaylistPlanner.genreMatches(want, g) } } }
                .distinctBy { it.track.id }
            if (pool.size >= needed) break
            plan = AiPlaylistPlanner.relax(plan) ?: break
        }
        if (pool.isEmpty()) return AiPlaylistOutcome.NoMatch(plan)

        for (c in pool) c.relevance = relevance(c, plan, seed)
        val ranked = pool.sortedWith(compareByDescending<Candidate> { it.relevance }.thenBy { it.track.id })

        // How many to choose: enough songs of the pool's average length to fill the time asked for, with room to spare.
        val meanMs = ranked.map { it.track.durationMs }.filter { it > 0 }.average().takeIf { it.isFinite() && it > 0 } ?: DEFAULT_TRACK_MS
        val k = ceil(plan.targetDurationMs / meanMs * 1.25 + 2).toInt().coerceIn(1, MAX_TRACKS)
        val perArtist = maxOf(MIN_PER_ARTIST, ceil(k * MAX_ARTIST_SHARE).toInt())
        val artistCount = HashMap<String, Int>()
        val diverse = Diversifier.select(
            items = ranked,
            k = k,
            lambda = MMR_LAMBDA,
            relevance = { it.relevance },
            similarity = ::similarity,
            admit = { c ->
                val n = artistCount[c.artistKey] ?: 0
                (c.artistKey.isEmpty() || n < perArtist).also { ok -> if (ok) artistCount[c.artistKey] = n + 1 }
            },
            cancel = cancel,
        )

        // The length asked for: songs are taken in MMR order until it is reached (the last may run over by less than a song).
        val chosen = ArrayList<Candidate>()
        var total = 0L
        for (c in diverse) {
            if (total >= plan.targetDurationMs) break
            chosen += c
            total += c.track.durationMs.coerceAtLeast(0)
        }

        val ordered = PlaylistSequencer.order(
            chosen.map { c ->
                SeqItem(
                    item = c,
                    key = c.track.id,
                    artist = c.artistKey,
                    album = c.albumKey,
                    bpm = c.bpm,
                    energy = c.energy,
                    relevance = c.relevance,
                    familiar = c.liked || c.plays > 0 || c.affinity > 0,
                )
            },
            plan.arc,
            plan.weights,
            cancel,
        )
        val tracks = ordered.map { it.track }
        return AiPlaylistOutcome.Ready(tracks, tracks.sumOf { it.durationMs.coerceAtLeast(0) }, plan)
    }

    /**
     * 0..1: how well a song answers the plan. Each constraint the plan has weighs in; one it does not have does not
     * (a request with no tempo does not favour songs with one). The listener's taste always counts a little, and a
     * small jitter drawn from [seed] (the request) breaks ties differently for different requests, deterministically.
     */
    private fun relevance(c: Candidate, plan: AiPlaylistPlan, seed: String): Double {
        var sum = 0.0
        var weight = 0.0
        if (plan.include.isNotEmpty()) {
            val hit = plan.include.any { want -> c.genres.any { g -> AiPlaylistPlanner.genreMatches(want, g) } }
            sum += W_GENRE * (if (hit) 1.0 else 0.0)
            weight += W_GENRE
        }
        val target = plan.targetBpm
        val tolerance = plan.tolerance
        if (target != null && tolerance != null) {
            sum += W_TEMPO * tempoFit(c.bpm, target, tolerance)
            weight += W_TEMPO
        }
        plan.energyTarget?.let { e ->
            sum += W_ENERGY * (c.energy?.let { 1.0 - abs(it - e) } ?: NEUTRAL)
            weight += W_ENERGY
        }
        val taste = (if (c.liked) 0.6 else 0.0) + 0.4 * tanh(c.affinity.coerceAtLeast(0.0) / 3.0)
        sum += W_TASTE * taste
        weight += W_TASTE
        val jitter = ((c.track.id + "\u0000" + seed).hashCode().toUInt().toLong() % 10_000L) / 10_000.0 * JITTER
        return sum / weight + jitter
    }

    /** 1 on the tempo asked for, falling to ½ at the edge of the tolerance, ¼ beyond; an octave apart counts as close. */
    private fun tempoFit(bpm: Double?, target: Double, tolerance: Double): Double {
        if (bpm == null) return UNKNOWN_TEMPO
        val d = listOf(bpm, bpm * 2, bpm / 2).minOf { abs(it - target) * (if (it == bpm) 1.0 else 1.0 / Tempo.OCTAVE_FACTOR) }
        return if (d <= tolerance) 1.0 - 0.5 * d / tolerance else 0.25
    }

    private fun similarity(a: Candidate, b: Candidate): Double = when {
        a.artistKey.isNotEmpty() && a.artistKey == b.artistKey -> 1.0
        a.albumKey.isNotEmpty() && a.albumKey == b.albumKey -> 0.9
        a.genres.isNotEmpty() && b.genres.any { it in a.genres } -> 0.25
        else -> 0.0
    }

    private fun genresOf(r: AiTrackRow, keys: HashMap<String, String>, artistCache: HashMap<String, List<String>>): List<String> {
        val raw = ArrayList<String>()
        r.tagGenres.split('\u0000').filterTo(raw) { it.isNotBlank() }
        r.statGenres.split('\u0000', ',', ';').filterTo(raw) { it.isNotBlank() }
        raw += artistCache.getOrPut(r.artistGenres) { parseJsonList(r.artistGenres) }
        return raw.mapNotNull { g -> keys.getOrPut(g) { AiPlaylistPlanner.genreKey(g) }.takeIf { it.isNotEmpty() } }.distinct()
    }

    companion object {
        /** A playlist shorter than this is loosened before it is offered (or the library's size, if smaller). */
        const val MIN_TRACKS = 8
        const val MAX_TRACKS = 150
        const val MMR_LAMBDA = 0.72
        /** No artist takes more than this share of the playlist (and at least two songs are always allowed). */
        const val MAX_ARTIST_SHARE = 0.2
        const val MIN_PER_ARTIST = 2
        private const val DEFAULT_TRACK_MS = 210_000.0
        private const val W_GENRE = 0.4
        private const val W_TEMPO = 0.25
        private const val W_ENERGY = 0.2
        private const val W_TASTE = 0.15
        private const val NEUTRAL = 0.5
        private const val UNKNOWN_TEMPO = 0.45
        private const val JITTER = 0.04

        private val json = Json { isLenient = true }

        /**
         * 0..1 from the track's ReplayGain: the gain a player applies to bring it to the reference level, so a loud
         * master has a strongly negative gain. -1 dB and above is quiet (0), -12 dB and below is loud (1). Null when
         * the file carries none: the sequencer then treats the song as average, never as calm.
         */
        fun energyOf(replayGainDb: Double?): Double? =
            replayGainDb?.takeIf { it.isFinite() }?.let { ((-it - 1.0) / 11.0).coerceIn(0.0, 1.0) }

        private fun parseJsonList(text: String): List<String> {
            if (text.isBlank() || text == "[]") return emptyList()
            val arr = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
            return arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        }
    }
}
