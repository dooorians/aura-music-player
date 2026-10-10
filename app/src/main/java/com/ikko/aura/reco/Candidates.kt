package com.ikko.aura.reco

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** What a source says when asked whether it can really give a song's audio, and what else it learned doing so. */
data class Verification(
    /** True: the audio address was obtained. False: the source refused. Null: could not tell (no network). */
    val playable: Boolean?,
    val release: Release? = null,
    val tags: List<String> = emptyList(),
    /** The album the song's page names, when it does. */
    val album: String? = null,
    /** True: the page is an auto-generated song (not a video, a clip or a talk). Null: not told. */
    val isSong: Boolean? = null,
)

/** A place recommendations can be found: a search, and where it has one, a "similar to" and a check. */
interface CandidateSource {
    val id: String
    val name: String

    /** Whether the source can be asked right now (it is on, configured). */
    suspend fun available(): Boolean

    /** Songs for a text query. [provenance] is attached to every one found. */
    suspend fun search(query: String, provenance: Provenance): List<Candidate>

    /** Songs the source lists as close to a song; empty when the source has no such thing. */
    suspend fun related(title: String, artist: String, provenance: Provenance): List<Candidate>

    /** Checks a candidate of this source before it is offered. */
    suspend fun verify(candidate: Candidate): Verification
}

/** What is asked of the sources to find candidates for a profile. */
sealed class RecoQuery {
    abstract val text: String
    /** Songs by [artist]; [suffix] steers the source's ranking ("new song" surfaces recent releases) without changing who it is about. */
    data class ByArtist(val artist: String, val affinity: Double, val suffix: String = "") : RecoQuery() {
        override val text get() = if (suffix.isEmpty()) artist else "$artist $suffix"
    }
    data class SimilarTo(val title: String, val artist: String, val affinity: Double) : RecoQuery() { override val text get() = "$artist $title" }
    data class ByGenre(val genre: String, val share: Double) : RecoQuery() { override val text get() = genre }
    data class ByMood(val term: String, val mood: Mood) : RecoQuery() { override val text get() = term }
}

object QueryPlan {
    private val MOOD_TERMS = mapOf(
        Mood.CHILL to listOf("chill", "acoustic chill", "lofi chill", "soft indie", "calm songs"),
        Mood.NIGHT to listOf("late night", "nocturne", "ambient night", "midnight", "dream pop night"),
        Mood.ENERGY to listOf("workout", "dance hits", "upbeat", "hype", "party"),
    )

    private val GENERIC_GENRES = setOf("music", "other", "unknown", "genre", "misc", "various", "vocal", "soundtrack", "audiobook", "podcast", "pop", "rock")

    /**
     * The queries to run for [kinds]. Bounded, so a renewal is a handful of requests per source and not a crawl; the
     * mood terms rotate with [variation] so two renewals do not ask the same.
     */
    fun build(profile: TasteProfile, kinds: Set<RecoKind>, variation: Long, blocked: Set<String>): List<RecoQuery> {
        val out = ArrayList<RecoQuery>()
        val artists = profile.topArtists(10, excluding = blocked)
        val extra = profile.topArtists(30, excluding = blocked + artists.toSet()).drop(3)
            .let { rest -> if (rest.isEmpty()) emptyList() else List(minOf(4, rest.size)) { i -> rest[((variation + i * 7) % rest.size).toInt().coerceAtLeast(0)] } }.distinct()
        (artists + extra).forEach { k -> out += RecoQuery.ByArtist(profile.nameOf(k), profile.affinity(k)) }
        // Songs that say the most about the taste, at most two per artist: "similar to" asked of six songs by one artist
        // is one question asked six times.
        val perArtist = HashMap<String, Int>()
        profile.seeds.filter { s -> ArtistNames.split(s.artist).firstOrNull()?.let { it !in blocked } == true }
            .filter { s -> perArtist.merge(ArtistNames.split(s.artist).first(), 1, Int::plus)!! <= 2 }
            .take(8).forEach { s -> out += RecoQuery.SimilarTo(s.title, s.artist, profile.creditAffinity(ArtistNames.split(s.artist)).coerceAtLeast(0.3)) }
        // The recent releases of the artists played the most: asked for by name, as a search for "<artist>" alone answers
        // with their best-known songs, which are rarely their newest.
        if (RecoKind.NEW in kinds) artists.take(8).forEach { k -> out += RecoQuery.ByArtist(profile.nameOf(k), profile.affinity(k), "new song") }
        if (RecoKind.DISCOVERY in kinds || RecoKind.DAILY in kinds) {
            // Only genres that say something: a tag like "Music" or "Other" is not a taste, and a one-word catch-all asks
            // the source for whatever it ranks first.
            profile.genres.entries.filter { it.value >= 0.12 && it.key.lowercase().trim() !in GENERIC_GENRES }
                .sortedByDescending { it.value }.take(2).forEach { out += RecoQuery.ByGenre(it.key, it.value) }
        }
        for ((mood, kind) in listOf(Mood.CHILL to RecoKind.CHILL, Mood.NIGHT to RecoKind.NIGHT, Mood.ENERGY to RecoKind.ENERGY)) {
            if (kind !in kinds) continue
            val terms = MOOD_TERMS.getValue(mood)
            repeat(2) { i -> out += RecoQuery.ByMood(terms[((variation + i) % terms.size).toInt().coerceAtLeast(0)], mood) }
        }
        return out.distinctBy { it.javaClass to it.text.lowercase() }
    }
}

/** How a source fared during one gathering: what was asked, what failed. */
data class SourceHealth(val sourceId: String, val requests: Int, val failures: Int) {
    val allFailed: Boolean get() = requests > 0 && failures == requests
}

data class Gathered(val candidates: List<Candidate>, val health: List<SourceHealth>)

object CandidateGatherer {
    private const val CALL_TIMEOUT_MS = 12_000L
    private const val TOTAL_TIMEOUT_MS = 70_000L

    suspend fun gather(sources: List<CandidateSource>, queries: List<RecoQuery>, parallelism: Int = 4): Gathered = coroutineScope {
        val gate = Semaphore(parallelism)
        val requests = HashMap<String, Int>()
        val failures = HashMap<String, Int>()
        val found = ArrayList<Candidate>()
        val lock = Any()

        suspend fun run(source: CandidateSource, block: suspend () -> List<Candidate>) {
            val result = gate.withPermit {
                try { withTimeoutOrNull(CALL_TIMEOUT_MS) { block() } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            }
            synchronized(lock) {
                requests.merge(source.id, 1, Int::plus)
                if (result == null) failures.merge(source.id, 1, Int::plus) else found += result
            }
        }

        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            sources.flatMap { source ->
                queries.map { q ->
                    async {
                        when (q) {
                            is RecoQuery.ByArtist -> run(source) { source.search(q.text, Provenance(ProvKind.ARTIST, q.artist, q.affinity)) }
                            is RecoQuery.SimilarTo -> run(source) { source.related(q.title, q.artist, Provenance(ProvKind.RELATED_TRACK, q.artist, q.affinity)) }
                            is RecoQuery.ByGenre -> run(source) { source.search(q.genre, Provenance(ProvKind.GENRE, q.genre, q.share.coerceIn(0.3, 0.9))) }
                            is RecoQuery.ByMood -> run(source) { source.search(q.term, Provenance(ProvKind.MOOD_QUERY, q.term, 0.0, q.mood)) }
                        }
                    }
                }
            }.awaitAll()
        }
        Gathered(
            candidates = found.toList(),
            health = sources.map { SourceHealth(it.id, requests[it.id] ?: 0, failures[it.id] ?: 0) },
        )
    }
}
