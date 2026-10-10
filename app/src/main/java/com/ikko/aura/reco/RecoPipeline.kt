package com.ikko.aura.reco

import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Where the pipeline reads what it knows and writes what it makes. Nothing in it reaches the player. */
interface RecoStore {
    suspend fun profileInputs(nowMs: Long): ProfileInputs
    suspend fun localIndex(): TrackIdentity.Index
    /** Keys of the blocked artists. */
    suspend fun blockedArtists(): Set<String>
    /** Ids and identity keys of what the listener hid, disliked or called known. */
    suspend fun hidden(): Set<String>
    suspend fun history(): Map<String, Long>
    /** Kind → when it was last generated (0 if never). */
    suspend fun generatedAt(): Map<RecoKind, Long>
    suspend fun sourceOrder(): List<String>
    /** Replaces a playlist with [picks] and records them as proposed. */
    suspend fun commit(kind: RecoKind, picks: List<Pick>, nowMs: Long)
    /** Records that an attempt left the previous playlist in place. */
    suspend fun keep(kind: RecoKind, status: String, nowMs: Long)
}

enum class RunStatus { UPDATED, NOTHING_DUE, NO_SOURCE, NO_PROFILE, SOURCES_FAILED }

data class RunResult(val status: RunStatus, val updated: Set<RecoKind> = emptySet(), val kept: Set<RecoKind> = emptySet())

/**
 * One renewal, end to end: signals → profile → candidates → filter → rank → write. Everything it touches is behind
 * [RecoStore] and [CandidateSource], so it runs the same on a phone and in a test. It never calls the player: a
 * renewal only replaces the rows of playlists, and a queue the listener is playing keeps its own songs.
 */
class RecoPipeline(
    private val store: RecoStore,
    private val sources: suspend () -> List<CandidateSource>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Most specific first: a song fits the narrowest playlist it can, the broad ones take what is left. */
    private val order = listOf(RecoKind.NEW, RecoKind.ENERGY, RecoKind.NIGHT, RecoKind.CHILL, RecoKind.STYLE, RecoKind.DISCOVERY, RecoKind.DAILY)

    suspend fun refresh(requested: Set<RecoKind>? = null, force: Boolean = false): RunResult {
        val now = clock()
        val generated = store.generatedAt()
        val kinds = (requested ?: RecoKind.entries.toSet()).filter { k ->
            force || Renewal.isDue(k.cadence, generated[k] ?: 0L, now, zone)
        }.toSet()
        if (kinds.isEmpty()) return RunResult(RunStatus.NOTHING_DUE)

        val live = sources().filter { runCatching { it.available() }.getOrDefault(false) }
        if (live.isEmpty()) {
            kinds.forEach { store.keep(it, "kept", now) }
            return RunResult(RunStatus.NO_SOURCE, kept = kinds)
        }

        val inputs = store.profileInputs(now)
        val profile = TasteProfileBuilder.build(inputs)
        val blocked = store.blockedArtists()
        if (profile.artists.isEmpty() && profile.seeds.isEmpty()) {
            kinds.forEach { store.keep(it, "none", now) }
            return RunResult(RunStatus.NO_PROFILE, kept = kinds)
        }

        val variation = kinds.maxOf { Renewal.variationOf(it, now, zone) }
        val queries = QueryPlan.build(profile, kinds, variation, blocked)
        val gathered = CandidateGatherer.gather(live, queries)
        if (gathered.candidates.isEmpty()) {
            kinds.forEach { store.keep(it, "kept", now) }
            return RunResult(RunStatus.SOURCES_FAILED, kept = kinds)
        }

        val sourceOrder = store.sourceOrder()
        val hidden = store.hidden().toMutableSet()
        val ctxBase = RankContext(
            profile = profile,
            local = store.localIndex(),
            blocked = blocked,
            hidden = hidden,
            history = store.history(),
            nowMs = now,
            zone = zone,
            variation = variation,
            sourceOrder = sourceOrder,
        )
        var pool = withCollabs(CandidateMerger.merge(gathered.candidates, sourceOrder), profile)
        // Dates of recent releases are not in a search result: asked for the few that could be new.
        if (RecoKind.NEW in kinds) pool = enrichReleases(pool, live, ctxBase)

        val sourcesById = live.associateBy { it.id }
        val used = HashSet<String>()
        val updated = LinkedHashSet<RecoKind>()
        val kept = LinkedHashSet<RecoKind>()
        for (kind in order.filter { it in kinds }) {
            // Songs a source refuses are dropped and replaced by the next ones, twice at most.
            val dropped = HashSet<String>()
            var picks: List<Pick> = emptyList()
            for (attempt in 0 until 3) {
                val ctx = if (dropped.isEmpty()) ctxBase else ctxBase.copy(hidden = hidden + dropped)
                val checked = verifyAll(Ranker.select(kind, pool, ctx, HashSet(used)), sourcesById)
                val bad = checked.filter { it.second.playable == false }.map { it.first.candidate.identity }
                picks = checked.filter { it.second.playable != false }.map { (p, v) -> markVerified(p, v) }
                if (bad.isEmpty()) break
                dropped += bad
            }
            if (picks.size >= Ranker.specOf(kind).minimum) {
                picks.forEach { used += it.candidate.identity }
                store.commit(kind, picks, now)
                updated += kind
            } else {
                store.keep(kind, "kept", now)
                kept += kind
            }
        }
        return RunResult(RunStatus.UPDATED, updated, kept)
    }

    private fun markVerified(p: Pick, v: Verification): Pick =
        if (v.playable == true) p.copy(candidate = p.candidate.copy(playability = Playability.VERIFIED)) else p

    /** Songs credited with one of the listener's artists that is not their primary one: a tie worth saying. */
    private fun withCollabs(pool: List<Candidate>, profile: TasteProfile): List<Candidate> = pool.map { c ->
        val extra = c.credits.drop(1).filter { profile.affinity(it) >= 0.2 }
        if (extra.isEmpty()) c else {
            val best = extra.maxBy { profile.affinity(it) }
            c.copy(provenance = c.provenance + Provenance(ProvKind.COLLAB, profile.nameOf(best), profile.affinity(best)))
        }
    }

    private suspend fun enrichReleases(pool: List<Candidate>, live: List<CandidateSource>, ctx: RankContext): List<Candidate> {
        val sourcesById = live.associateBy { it.id }
        // A year that is not this one already says the song is not new: no need to ask.
        val toAsk = pool.filter { (it.release.reliability == DateReliability.UNKNOWN || (it.release.reliability == DateReliability.YEAR && it.release.year >= ctx.today.year)) &&
            it.sourceId in sourcesById &&
            Eligibility.excluded(it, ctx) == null &&
            maxOf(ctx.profile.creditAffinity(it.credits), it.provenance.maxOfOrNull { p -> p.seedAffinity } ?: 0.0) >= 0.15 }
            .sortedByDescending { c -> maxOf(ctx.profile.creditAffinity(c.credits), c.provenance.maxOfOrNull { it.seedAffinity } ?: 0.0) }
            .take(MAX_DATE_LOOKUPS)
        if (toAsk.isEmpty()) return pool
        val answers = verifyAll(toAsk.map { Pick(it, Reason(ReasonCode.NONE), 0.0) }, sourcesById)
            .associate { (p, v) -> p.candidate.id to v }
        return pool.map { c ->
            val v = answers[c.id] ?: return@map c
            c.copy(
                release = v.release?.takeIf { it.reliability != DateReliability.UNKNOWN } ?: c.release,
                tags = (c.tags + v.tags).distinct(),
                playability = if (v.playable == true) Playability.VERIFIED else c.playability,
            )
        }
    }

    private suspend fun verifyAll(picks: List<Pick>, sources: Map<String, CandidateSource>): List<Pair<Pick, Verification>> = coroutineScope {
        val gate = Semaphore(4)
        picks.map { p ->
            async {
                if (p.candidate.playability == Playability.VERIFIED) return@async p to Verification(true)
                val source = sources[p.candidate.sourceId] ?: return@async p to Verification(null)
                val v = gate.withPermit {
                    try { withTimeoutOrNull(15_000L) { source.verify(p.candidate) } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                } ?: Verification(null)
                p to v
            }
        }.awaitAll()
    }

    private companion object {
        const val MAX_DATE_LOOKUPS = 24
    }
}
