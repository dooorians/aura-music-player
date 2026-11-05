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
    /** The names of the blocked artists, as the listener blocked them: what the catalogues are asked about. */
    suspend fun blockedNames(): List<String> = emptyList()
    /** Ids and identity keys of what the listener hid, disliked or called known. */
    suspend fun hidden(): Set<String>
    suspend fun history(): Map<String, Long>
    /** Kind → when it was last generated (0 if never). */
    suspend fun generatedAt(): Map<RecoKind, Long>
    /** Kind → when a renewal last tried, whatever came of it (0 if never). */
    suspend fun attemptedAt(): Map<RecoKind, Long>
    suspend fun sourceOrder(): List<String>
    /** Replaces a playlist with [picks] and records them as proposed. */
    suspend fun commit(kind: RecoKind, picks: List<Pick>, nowMs: Long)
    /** Records that an attempt left the previous playlist in place. */
    suspend fun keep(kind: RecoKind, status: String, nowMs: Long)
}

/** A renewal that found no source to ask or no answer: not counted as a try, so the next opening tries again. */
const val STATUS_UNAVAILABLE = "unavailable"

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
    /** Diagnostics only: what was asked and found. Never carries anything the listener did not already have on the device. */
    private val log: (String) -> Unit = {},
    /** What an outside catalogue says of artists: keeps out what has nothing to do with the listener's taste. */
    private val insight: ArtistInsight? = null,
    private val authority: com.ikko.aura.reco.authority.ArtistAuthority? = null,
    private val verifier: com.ikko.aura.reco.authority.DiscographyVerifier? = null,
    private val deezerClient: com.ikko.aura.data.deezer.DeezerClient? = null,
    /** What can be measured of a song (tempo, loudness, its tags): what the ambiances are judged on. */
    private val features: SongFeatureSource? = null,
    /** The fewest songs a playlist needs to replace the previous one; null for each playlist's own (see [Ranker.Spec.acceptable]). */
    private val playlistFloor: Int? = null,
) {
    /**
     * Most specific first: a song fits the narrowest playlist it can, the broad ones take what is left. Discovery comes
     * before style because strangers are what is scarce: style has the listener's own artists to draw on.
     */
    private val order = listOf(RecoKind.NEW, RecoKind.ENERGY, RecoKind.NIGHT, RecoKind.CHILL, RecoKind.DISCOVERY, RecoKind.STYLE, RecoKind.DAILY)

    suspend fun refresh(requested: Set<RecoKind>? = null, force: Boolean = false): RunResult {
        val now = clock()
        val generated = store.generatedAt()
        val attempted = store.attemptedAt()
        val kinds = (requested ?: RecoKind.entries.toSet()).filter { k ->
            // Due by its cadence — and not tried again at every opening of the app when the last try found too little.
            force || (Renewal.isDue(k.cadence, generated[k] ?: 0L, now, zone) && now - (attempted[k] ?: 0L) >= RETRY_AFTER_MS)
        }.toSet()
        if (kinds.isEmpty()) return RunResult(RunStatus.NOTHING_DUE)

        val live = sources().filter { runCatching { it.available() }.getOrDefault(false) }
        if (live.isEmpty()) {
            kinds.forEach { store.keep(it, STATUS_UNAVAILABLE, now) }
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
        // Similar artists from Last.fm and Deezer related graphs: verified and deduplicated
        val similarQueries = runCatching { similarArtistQueries(insight, profile, blocked) }.getOrDefault(emptyList())
        val queries = similarQueries + QueryPlan.build(profile, kinds, variation, blocked)
        val gathered = CandidateGatherer.gather(live, queries)
        if (gathered.candidates.isEmpty()) {
            kinds.forEach { store.keep(it, STATUS_UNAVAILABLE, now) }
            return RunResult(RunStatus.SOURCES_FAILED, kept = kinds)
        }

        log("queries=${queries.size} candidates=${gathered.candidates.size} health=${gathered.health}")
        val sourceOrder = store.sourceOrder()
        val hidden = store.hidden()
        // What the artists' best-known songs are, so that a discovery is not their hit again.
        val hits = insight?.let { i -> runCatching { hitsOf(i, gathered.candidates) }.getOrDefault(emptyMap()) }.orEmpty()
        val ctxBase0 = RankContext(
            profile = profile,
            local = store.localIndex(),
            blocked = blocked,
            hidden = hidden,
            history = store.history(),
            nowMs = now,
            zone = zone,
            variation = variation,
            sourceOrder = sourceOrder,
            hits = hits,
        )
        val sourcesById = live.associateBy { it.id }
        // What each source said about a song when asked, kept for the whole renewal: a song is looked up once.
        val checked = HashMap<String, Verification>()
        var pool = withCollabs(CandidateMerger.merge(gathered.candidates, sourceOrder), profile)
        insight?.let { i ->
            val before = pool.size
            pool = runCatching { TasteGate(i, profile, store.blockedNames()).apply(pool) }.getOrDefault(pool)
            log("taste gate: $before -> ${pool.size}")
        }
        // What the artists of the candidates are, by their own tags: what every playlist judges its style on (see [StyleFit]).
        val artistTags = runCatching { tagsOf(insight, pool, profile) }.getOrDefault(emptyMap())
        // The songs the ambiances may draw from are measured (their tempo, their loudness, the tags of the song): the closest to the listener first.
        val moodsAsked = kinds.any { it == RecoKind.ENERGY || it == RecoKind.NIGHT || it == RecoKind.CHILL }
        val songFeatures = if (moodsAsked && features != null) {
            val toMeasure = pool.filter { Eligibility.excluded(it, ctxBase0) == null }.sortedByDescending { tieOf(it, profile) }.take(MAX_FEATURE_LOOKUPS)
            runCatching { features?.featuresOf(toMeasure, FEATURE_BUDGET_MS) }.getOrNull().orEmpty()
        } else emptyMap()
        log("tags=${artistTags.size} measured=${songFeatures.size}")
        val ctxBase = ctxBase0.copy(artistTags = artistTags, songFeatures = songFeatures)
        // Dates of recent releases are not in a search result: asked for the few that could be new.
        if (RecoKind.NEW in kinds) {
            lookUp(pool.filter { needsDate(it, ctxBase) }.sortedByDescending { tieOf(it, profile) }.take(MAX_DATE_LOOKUPS), sourcesById, checked)
            val seen = pool.map { applied(it, checked[it.id]) }
            log(
                "pool=${seen.size} dated=${seen.count { it.release.reliability == DateReliability.EXACT }} " +
                    "recent=${seen.count { Ranker.isRecentRelease(it, ctxBase) }}",
            )
        }

        // Entries a source lists beside a song may be clips or talks: those with a tie to the listener are asked about
        // first, so the songs among them are known — with their album and artists — before anything is chosen.
        lookUp(
            pool.filter { it.sourceId == "ytm" && !it.confirmedSong && Eligibility.excluded(it, ctxBase) == null }
                .sortedByDescending { tieOf(it, profile) }.take(MAX_SONG_CHECKS),
            sourcesById, checked,
        )

        val used = HashSet<String>()
        // Songs a source refused, that are not songs, or that the listener has (found out by asking): out of every playlist.
        val rejected = HashSet<String>()
        val updated = LinkedHashSet<RecoKind>()
        val kept = LinkedHashSet<RecoKind>()
        for (kind in order.filter { it in kinds }) {
            var picks: List<Pick> = emptyList()
            for (attempt in 0 until 3) {
                val ctx = ctxBase.copy(hidden = hidden + rejected)
                val current = pool.map { applied(it, checked[it.id]) }
                val selected = Ranker.select(kind, current, ctx, HashSet(used))
                lookUp(selected.map { it.candidate }, sourcesById, checked)
                val verified = selected.map { p -> p.copy(candidate = applied(p.candidate, checked[p.candidate.id])) }
                val bad = ArrayList<Pick>()
                for (p in verified) {
                    if (!acceptable(p.candidate, checked[p.candidate.id], ctx)) {
                        bad += p
                    }
                }
                picks = verified - bad.toSet()
                log("${kind.key} attempt=$attempt selected=${selected.size} refused=${bad.size} kept=${picks.size}")
                if (bad.isEmpty()) break
                rejected += bad.map { it.candidate.identity }
            }
            // A short list is kept short rather than padded; a very short one is not worth replacing the last one with.
            val spec = Ranker.specOf(kind)
            if (picks.size >= (playlistFloor?.let { maxOf(it, spec.minimum / 2) } ?: spec.acceptable)) {
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

    /**
     * Artists close to the listener's top ones, by Last.fm and Deezer: those named by several of them first, and the closest of each; never
     * one the listener already has, nor a blocked one.
     */
    private suspend fun similarArtistQueries(insight: ArtistInsight?, profile: TasteProfile, blocked: Set<String>): List<RecoQuery> = coroutineScope {
        val tops = profile.topArtists(12, excluding = blocked)
        val lastFmLists = if (insight != null) {
            tops.map { k -> async { k to withTimeoutOrNull(8_000L) { insight.similar(profile.nameOf(k)) }.orEmpty() } }.awaitAll()
        } else emptyList()

        val deezerLists = if (deezerClient != null && authority != null) {
            tops.map { k ->
                async {
                    val name = profile.nameOf(k)
                    val resolved = authority.resolve(name, profile.genres.keys)
                    val dId = resolved?.deezerId
                    if (dId != null && dId > 0) {
                        val related = runCatching { deezerClient.getRelatedArtists(dId, limit = 20) }.getOrDefault(emptyList())
                        k to related.map { it.name }
                    } else {
                        k to emptyList()
                    }
                }
            }.awaitAll()
        } else emptyList()

        val score = HashMap<String, Double>()
        val names = HashMap<String, String>()
        val seedOf = HashMap<String, String>()
        for ((seed, similar) in lastFmLists) {
            similar.take(15).forEachIndexed { i, name ->
                val key = ArtistNames.key(name)
                if (key.isEmpty() || key in profile.knownArtists || key in blocked || profile.affinity(key) > 0.0) return@forEachIndexed
                score.merge(key, profile.affinity(seed) * (1.0 - i / 20.0), Double::plus)
                names.putIfAbsent(key, name)
                seedOf.putIfAbsent(key, profile.nameOf(seed))
            }
        }
        for ((seed, related) in deezerLists) {
            related.take(15).forEachIndexed { i, name ->
                val key = ArtistNames.key(name)
                if (key.isEmpty() || key in profile.knownArtists || key in blocked || profile.affinity(key) > 0.0) return@forEachIndexed
                score.merge(key, profile.affinity(seed) * (1.0 - i / 20.0), Double::plus)
                names.putIfAbsent(key, name)
                seedOf.putIfAbsent(key, profile.nameOf(seed))
            }
        }
        val topSimilar = score.entries.sortedByDescending { it.value }.take(MAX_SIMILAR_ARTISTS)
        val verifiedSimilar = ArrayList<RecoQuery>()
        for ((key, s) in topSimilar) {
            val candidateName = names.getValue(key)
            val seedName = seedOf.getValue(key)
            if (authority != null) {
                val resolved = authority.resolve(candidateName, profile.genres.keys)
                if (resolved != null && resolved.primaryTags.isNotEmpty() && profile.genres.isNotEmpty()) {
                    val hasMatch = resolved.primaryTags.any { t -> profile.genres.containsKey(t) }
                    if (!hasMatch && (resolved.primaryTags.contains("spoken word") || resolved.primaryTags.contains("comedy") || resolved.primaryTags.contains("religious"))) {
                        continue
                    }
                }
            }
            verifiedSimilar += RecoQuery.SimilarArtist(candidateName, seedName, (0.45 + s * 0.2).coerceAtMost(0.8))
        }
        verifiedSimilar
    }

    /** The tags of the artists the candidates are by, the ones with the closest tie to the listener first. */
    private suspend fun tagsOf(insight: ArtistInsight?, pool: List<Candidate>, profile: TasteProfile): Map<String, List<String>> = coroutineScope {
        if (insight == null) return@coroutineScope emptyMap()
        val gate = Semaphore(6)
        pool.groupBy { it.credits.firstOrNull().orEmpty() }.filterKeys { it.isNotEmpty() }.entries
            .sortedByDescending { (_, list) -> list.maxOf { tieOf(it, profile) } }.take(MAX_TAG_LOOKUPS)
            .map { (key, list) ->
                async { gate.withPermit { key to withTimeoutOrNull(6_000L) { insight.tags(ArtistNames.primary(list.first().artist)) } } }
            }.awaitAll()
            .mapNotNull { (key, tags) -> tags?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { key to it } }.toMap()
    }

    /** The most played songs of the artists the candidates are by (the most represented first), as title keys. */
    private suspend fun hitsOf(insight: ArtistInsight, candidates: List<Candidate>): Map<String, List<String>> = coroutineScope {
        val gate = Semaphore(4)
        candidates.groupBy { it.credits.firstOrNull().orEmpty() }.filterKeys { it.isNotEmpty() }.entries
            .sortedByDescending { it.value.size }.take(MAX_HIT_LOOKUPS)
            .map { (key, list) ->
                async { gate.withPermit { key to withTimeoutOrNull(6_000L) { insight.topTracks(ArtistNames.primary(list.first().artist)) } } }
            }.awaitAll()
            .mapNotNull { (key, top) -> top?.takeIf { it.isNotEmpty() }?.let { key to it.map(TrackIdentity::titleKey) } }.toMap()
    }

    /** The candidate as what its source said when asked adds to it: the album its page names, a stated date, a song. */
    private fun applied(c: Candidate, v: Verification?): Candidate {
        if (v == null) return c
        val release = v.release?.takeIf { it.reliability != DateReliability.UNKNOWN }
        // The page of an official release names who the song is by, collaborations included: better than a channel's name.
        val artists = v.artists.filter { it.isNotBlank() }
        return c.copy(
            artist = if (artists.isNotEmpty()) artists.first() else c.artist,
            extraCredits = if (artists.size > 1) (c.extraCredits + artists.drop(1)).distinct() else c.extraCredits,
            album = if (c.album.isBlank() && !v.album.isNullOrBlank()) v.album else c.album,
            release = if (release != null && rank(release) > rank(c.release)) release else c.release,
            tags = (c.tags + v.tags).distinct(),
            // The page has the last word: an official release's description makes it a song, any other page is a video
            // (a clip, a session, a talk) whatever list it was found in. Only when the page could not be read does the
            // list it came from (a search of songs) stand.
            confirmedSong = v.isSong ?: c.confirmedSong,
            playability = if (v.playable == true) Playability.VERIFIED else c.playability,
        )
    }

    private fun rank(r: Release) = when (r.reliability) { DateReliability.EXACT -> 2; DateReliability.YEAR -> 1; DateReliability.UNKNOWN -> 0 }

    /**
     * Whether a song can be offered once its source has been asked: not refused, a song and not a video, and not a
     * song of an album (or a recording) the listener has — which the page may only reveal now.
     * Enforces strict verified discography integrity and disambiguation.
     */
    private suspend fun acceptable(c: Candidate, v: Verification?, ctx: RankContext): Boolean {
        if (v?.playable == false) return false
        // Of the sources, only YouTube Music mixes videos in with songs: its songs must be vouched for, by an official
        // release's own description, or — when the page could not be read — by the search of songs they came from.
        if (c.sourceId == "ytm" && !c.confirmedSong) return false
        if (Eligibility.excluded(c, ctx) != null) return false

        // Discography integrity for the artists we can identify: a track or album outside the official catalogue is a
        // compilation, a bootleg or a namesake. A stranger no catalogue knows is left to the taste gate and the ranking:
        // rejecting them outright would shut discovery out, which is the point of the recommendations.
        if (authority != null && verifier != null) {
            val primaryArtist = ArtistNames.primary(c.artist, beforeAmpersand = true)
            if (primaryArtist.isNotEmpty()) {
                val resolved = authority.resolve(primaryArtist, ctx.profile.genres.keys)
                val identified = resolved != null && (resolved.deezerId != null || !resolved.mbid.isNullOrBlank())
                if (resolved != null && identified) {
                    val isOfficial = verifier.isOfficialTrackAndRelease(resolved, c.title, c.album)
                    if (!isOfficial) {
                        log("Excluded non-official candidate: ${c.artist} - ${c.title} on [${c.album}]")
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun tieOf(c: Candidate, profile: TasteProfile): Double =
        maxOf(profile.creditAffinity(c.credits), c.provenance.maxOfOrNull { it.seedAffinity } ?: 0.0)

    /** A song whose date could settle whether it is new: undated or dated this year, with a tie to the listener. */
    private fun needsDate(c: Candidate, ctx: RankContext): Boolean =
        (c.release.reliability == DateReliability.UNKNOWN || (c.release.reliability == DateReliability.YEAR && c.release.year >= ctx.today.year)) &&
            Eligibility.excluded(c, ctx) == null && tieOf(c, ctx.profile) >= 0.15

    /** Asks the sources about [candidates] not asked about yet, a few at a time. */
    private suspend fun lookUp(candidates: List<Candidate>, sources: Map<String, CandidateSource>, into: MutableMap<String, Verification>) = coroutineScope {
        val gate = Semaphore(5)
        candidates.filter { it.id !in into }.distinctBy { it.id }.map { c ->
            async {
                // A source that lists a song as playable (a server's own file) needs no asking.
                val source = sources[c.sourceId]
                val v = when {
                    source == null -> Verification(null)
                    c.playability == Playability.VERIFIED && c.confirmedSong -> Verification(true)
                    else -> gate.withPermit {
                        try { withTimeoutOrNull(15_000L) { source.verify(c) } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    } ?: Verification(null)
                }
                c.id to v
            }
        }.awaitAll().forEach { (id, v) -> into[id] = v }
    }

    /** Songs credited with one of the listener's artists that is not their primary one: a tie worth saying. */
    private fun withCollabs(pool: List<Candidate>, profile: TasteProfile): List<Candidate> = pool.map { c ->
        val extra = c.credits.drop(1).filter { profile.affinity(it) >= 0.2 }
        if (extra.isEmpty()) c else {
            val best = extra.maxBy { profile.affinity(it) }
            c.copy(provenance = c.provenance + Provenance(ProvKind.COLLAB, profile.nameOf(best), profile.affinity(best)))
        }
    }

    private companion object {
        /** After an attempt that kept the previous playlist, the next automatic one waits this long. */
        const val RETRY_AFTER_MS = 6L * 60 * 60 * 1000
        const val MAX_DATE_LOOKUPS = 24
        const val MAX_SONG_CHECKS = 120
        const val MAX_SIMILAR_ARTISTS = 40
        const val MAX_HIT_LOOKUPS = 16
        const val MAX_TAG_LOOKUPS = 150
        const val MAX_FEATURE_LOOKUPS = 160
        const val FEATURE_BUDGET_MS = 100_000L
    }
}
