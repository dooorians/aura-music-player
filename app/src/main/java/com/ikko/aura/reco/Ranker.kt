package com.ikko.aura.reco

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

/** Everything a selection depends on besides the candidates themselves. */
data class RankContext(
    val profile: TasteProfile,
    val local: TrackIdentity.Index,
    /** Keys of the blocked artists, as the listener blocked them. */
    val blocked: Set<String>,
    /** Track ids and identity keys the listener hid or disliked. */
    val hidden: Set<String>,
    /** Identity key → when it was last proposed. */
    val history: Map<String, Long>,
    val nowMs: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Changes with the renewal (the day, the week) so two renewals break ties differently. */
    val variation: Long = 0L,
    /** The sources in the listener's order; a duplicate is kept from the earliest. */
    val sourceOrder: List<String> = emptyList(),
    /** Artist key → the title keys of their most played songs, most played first (Last.fm): what tells a hit from a deep cut. */
    val hits: Map<String, List<String>> = emptyMap(),
    /** Artist key → their tags (Last.fm), most telling first: what tells a metal band from a pop star, whatever a source says of their song. */
    val artistTags: Map<String, List<String>> = emptyMap(),
) {
    /** The broad families of what the listener plays, from the genres their library and their artists carry; empty when there is not enough to tell. */
    val likedFamilies: Set<String> by lazy {
        profile.genres.entries.sortedByDescending { it.value }.take(8).mapNotNull { GenreNormalizer.family(it.key) }.toSet()
    }

    val today: LocalDate get() = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
}

enum class Exclusion { BLOCKED_ARTIST, HIDDEN, KNOWN, LOCAL_DUPLICATE, LOCAL_ALBUM, JUNK }

object Eligibility {

    private val JUNK_TITLE = Regex(
        "\\b(?:karaoke|karaoké|8[- ]?bit|16[- ]?bit|tribute|ringtone|sonnerie|nightcore|made famous by|originally performed|" +
            "in the style of|backing track|midi version|lullaby (?:version|rendition)|music box|full album|\\d+ hours?|hours? of|" +
            "clip officiel|vid[ée]o ?clip|video oficial|video musical|official (?:music )?video|music video|m/?v|teaser|dance practice|performance video|making of|short film)\\b|" +
            "[(\\[]\\s*(?:official\\s+)?(?:lyrics?|lyric video|music video|video|visuali[sz]er|live)\\s*[)\\]]|" +
            // "Song - Lyrics", "Song lyrics video": a lyric video, not a song called "Lyrics" or a title that mentions them.
            "(?:[-–|]\\s*|\\s)lyrics?(?:\\s+video)?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Words of videos, talks and tutorials that are also titles of songs ("Breakdown", "Routine", "Reaction"): held against a title only
     * when it has three words or more, as a video's title has and a song's rarely does.
     */
    private val JUNK_TITLE_VIDEO = Regex(
        "\\b(?:clip|routine|skincare|podcast|interview|episode|reaction|tutorial|vlog|unboxing|explained|breakdown|rip off)\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun videoLike(title: String): Boolean = JUNK_TITLE_VIDEO.containsMatchIn(title) && title.trim().split(Regex("\\s+")).size >= 3

    /** Channels that publish playlists-in-disguise: cover bands, "workout" and "lofi" compilations, sleep music. */
    private val JUNK_ARTIST = Regex(
        "\\b(?:karaoke|8[- ]?bit arcade|tribute|cover band|backing tracks?|piano tribute|lullaby renditions?|running music|" +
            "lo-?fi|meditation|reiki|spa music|nature sounds?|dance hits|hits \\d{4}|\\d{4} hits|" +
            "music academy|music collective|music experience|sound series|lyrics?|playlist|tiefenentspannung)\\b|" +
            // A generic word alone is a band's name ("Sleep Token", "Beats Antique", "The Study"); it is a channel's when it describes its content.
            "\\b(?:sleep|study|relax(?:ing|ation)?|chill(?:out)?|focus|workout|yoga|spa|lo-?fi)\\s+(?:music|sounds?|beats|playlists?|sessions?|vibes|hits|tunes|radio|channel)\\b",
        RegexOption.IGNORE_CASE,
    )

    const val MIN_DURATION_MS = 45_000L
    const val MAX_DURATION_MS = 15 * 60_000L

    fun excluded(c: Candidate, ctx: RankContext): Exclusion? {
        if (ArtistNames.involves(c.credits, c.artist, ctx.blocked) ||
            c.extraCredits.any { ArtistNames.key(it) in ctx.blocked }
        ) return Exclusion.BLOCKED_ARTIST
        if (c.id in ctx.hidden || c.identity in ctx.hidden) return Exclusion.HIDDEN
        // An album the listener refused: none of its songs, however the source numbers them.
        if (ctx.hidden.isNotEmpty() && AlbumKeys.of(c.albumRef, c.album, c.artist).any { it in ctx.hidden }) return Exclusion.HIDDEN
        if (c.durationMs in 1 until MIN_DURATION_MS || c.durationMs > MAX_DURATION_MS ||
            JUNK_TITLE.containsMatchIn(c.title) || videoLike(c.title) || JUNK_ARTIST.containsMatchIn(c.artist) || JUNK_TITLE.containsMatchIn(c.album) ||
            com.ikko.aura.reco.authority.CompilationTitles.matches(c.title) || com.ikko.aura.reco.authority.CompilationTitles.matches(c.album)
        ) return Exclusion.JUNK
        if (c.identity in ctx.profile.knownTracks) return Exclusion.KNOWN
        if (ctx.local.contains(TrackIdentity.Entry(c.title, c.artist, c.durationMs, c.extraCredits))) return Exclusion.LOCAL_DUPLICATE
        // An album the listener has: its other songs are not a discovery either.
        if (c.album.isNotBlank() && ctx.local.hasAlbum(c.album, c.credits)) return Exclusion.LOCAL_ALBUM
        return null
    }
}

/** Collapses the same recording found by several sources, or by several queries, into one candidate. */
object CandidateMerger {
    fun merge(candidates: List<Candidate>, sourceOrder: List<String>): List<Candidate> {
        fun rank(sourceId: String): Int = sourceOrder.indexOf(sourceId).let { if (it < 0) Int.MAX_VALUE else it }
        val groups = LinkedHashMap<String, MutableList<Candidate>>()
        for (c in candidates) {
            val list = groups.getOrPut(c.identity) { ArrayList() }
            // Same title and artist but a very different length is another recording (a live cut, an edit).
            val existing = list.firstOrNull { TrackIdentity.sameLength(it.durationMs, c.durationMs, 15_000L) }
            if (existing == null) list += c else {
                val i = list.indexOf(existing)
                list[i] = combine(existing, c, ::rank)
            }
        }
        return groups.values.flatten()
    }

    private fun combine(a: Candidate, b: Candidate, rank: (String) -> Int): Candidate {
        // The kept copy is the one that is verified, then the one from the listener's preferred source.
        val keep = when {
            a.playability != b.playability -> if (a.playability == Playability.VERIFIED) a else b
            rank(a.sourceId) != rank(b.sourceId) -> if (rank(a.sourceId) < rank(b.sourceId)) a else b
            else -> a
        }
        val other = if (keep === a) b else a
        // A date one source knows is a date for the song: the most reliable of the two is kept.
        val release = if (reliabilityOf(other.release) > reliabilityOf(keep.release)) other.release else keep.release
        return keep.copy(
            release = release,
            provenance = (keep.provenance + other.provenance).distinct(),
            tags = (keep.tags + other.tags).distinct(),
            extraCredits = (keep.extraCredits + other.extraCredits).distinct(),
            artworkUrl = keep.artworkUrl ?: other.artworkUrl,
            durationMs = if (keep.durationMs > 0) keep.durationMs else other.durationMs,
        )
    }

    private fun reliabilityOf(r: Release) = when (r.reliability) { DateReliability.EXACT -> 2; DateReliability.YEAR -> 1; DateReliability.UNKNOWN -> 0 }
}

/** Per-playlist selection. */
object Ranker {

    /** What a playlist asks of its songs and how many it wants. */
    data class Spec(val target: Int, val minimum: Int, val maxPerArtist: Int) {
        /** The fewest songs worth replacing the previous playlist with: half of what is wanted, and three at least. */
        val acceptable: Int get() = maxOf(3, minimum / 2)
    }

    fun specOf(kind: RecoKind) = when (kind) {
        RecoKind.DAILY -> Spec(target = 40, minimum = 8, maxPerArtist = 1)
        RecoKind.DISCOVERY -> Spec(target = 40, minimum = 6, maxPerArtist = 2)
        RecoKind.NEW -> Spec(target = 25, minimum = 4, maxPerArtist = 2)
        else -> Spec(target = 35, minimum = 6, maxPerArtist = 2)
    }

    /** A release this recent is "new": a stated release date within [NEW_DAYS] days. */
    const val NEW_DAYS = 120

    /**
     * Whether a candidate is a real recent release. Only an exact release date counts. A year alone does not: it cannot
     * tell a new song from an old one on a compilation or a reissue dated this year, and a date that is when a file was
     * uploaded or added to a catalogue is not a release date at all. A source that gives none leaves the song out.
     */
    fun isRecentRelease(c: Candidate, ctx: RankContext): Boolean {
        val r = c.release
        return r.reliability == DateReliability.EXACT && r.date != null && !r.date.isAfter(ctx.today.plusDays(1)) &&
            r.date.isAfter(ctx.today.minusDays(NEW_DAYS.toLong()))
    }

    private fun recency(c: Candidate, ctx: RankContext): Double = when (c.release.reliability) {
        DateReliability.EXACT -> c.release.date?.let { 1.0 - (java.time.temporal.ChronoUnit.DAYS.between(it, ctx.today).coerceIn(0, NEW_DAYS.toLong()) / NEW_DAYS.toDouble()) } ?: 0.0
        DateReliability.YEAR -> 0.4
        DateReliability.UNKNOWN -> 0.0
    }

    private const val MOOD_MIN_TASTE = 0.12

    private class Scored(val c: Candidate, val score: Double, val reason: Reason, val cluster: String)

    /**
     * The songs of [kind] chosen from [pool] (already merged), in order. [used] holds the identities taken by playlists
     * chosen before this one in the same renewal, so the seven are not copies; the picks are added to it.
     */
    fun select(kind: RecoKind, pool: List<Candidate>, ctx: RankContext, used: MutableSet<String>): List<Pick> {
        val spec = specOf(kind)
        val profile = ctx.profile
        val scored = ArrayList<Scored>()
        for (c in pool) {
            if (c.identity in used) continue
            if (Eligibility.excluded(c, ctx) != null) continue
            scoreFor(kind, c, ctx)?.let { scored += it }
        }
        scored.sortByDescending { it.score }

        val perArtist = HashMap<String, Int>()
        val perCluster = HashMap<String, Int>()
        val clusterCap = if (kind == RecoKind.DAILY) 4 else 6
        fun knownArtist(c: Candidate) = c.credits.any { it in profile.knownArtists || ctx.local.hasArtist(it) }
        fun admit(s: Scored): Boolean {
            val primary = s.c.credits.firstOrNull().orEmpty()
            if ((perArtist[primary] ?: 0) >= spec.maxPerArtist) return false
            if ((perCluster[s.cluster] ?: 0) >= clusterCap) return false
            perArtist.merge(primary, 1, Int::plus)
            perCluster.merge(s.cluster, 1, Int::plus)
            return true
        }
        val chosen = ArrayList<Scored>()
        if (kind == RecoKind.DISCOVERY) {
            // A discovery is mostly artists the listener does not know: those are taken first, and the ones they do
            // know are a minority of the list (30 %), however few strangers there are.
            val strangers = scored.filter { !knownArtist(it.c) }
            strangers.forEach { if (chosen.size < spec.target && admit(it)) chosen += it }
            val allowedKnown = (chosen.size * 3) / 7
            var known = 0
            for (s in scored.filter { knownArtist(it.c) }) {
                if (known >= allowedKnown || chosen.size >= spec.target) break
                if (admit(s)) { chosen += s; known++ }
            }
            chosen.sortByDescending { it.score }
        } else {
            for (s in scored) {
                if (chosen.size >= spec.target) break
                if (admit(s)) chosen += s
            }
        }
        val picks = chosen.map { Pick(it.c, it.reason, it.score) }
        // A cold profile cannot fill a playlist honestly: fewer songs than the minimum is left to the caller, which
        // keeps the previous list rather than padding it.
        picks.forEach { used += it.candidate.identity }
        return picks
    }

    private fun jitter(c: Candidate, ctx: RankContext): Double =
        ((c.identity.hashCode().toLong() * 31 + ctx.variation).hashCode() and 0xFFFF) / 65535.0 * 0.06

    private fun historyPenalty(c: Candidate, ctx: RankContext): Double {
        val shown = ctx.history[c.identity] ?: return 0.0
        val days = (ctx.nowMs - shown) / 86_400_000.0
        return when { days < 14 -> 0.4; days < 60 -> 0.18; else -> 0.0 }
    }

    /** How many of an artist's top songs are called hits, and how many must be known before a song missing from them counts as deep. */
    private const val HIT_RANKS = 3
    private const val DEEP_AFTER = 15
    private const val HIT_PENALTY = 0.08
    private const val DEEP_BONUS = 0.03

    /** A hit of its artist is what an anonymous catalogue offers first: a little against it, and a little for what lies deeper. */
    internal fun depthAdjust(c: Candidate, ctx: RankContext): Double {
        val top = c.credits.firstOrNull()?.let { ctx.hits[it] } ?: return 0.0
        val rank = top.indexOf(TrackIdentity.titleKey(c.title))
        return when {
            rank in 0 until HIT_RANKS -> -HIT_PENALTY
            rank < 0 && top.size >= DEEP_AFTER -> DEEP_BONUS
            else -> 0.0
        }
    }

    /** Ambiances an artist is plainly not of: a metal band is not "Chill" or "Nuit", a symphony is not "Ça bouge !". */
    private val FORBIDDEN = mapOf(
        RecoKind.CHILL to setOf("metal", "punk"), RecoKind.NIGHT to setOf("metal", "punk"), RecoKind.ENERGY to setOf("classical", "soundtrack"),
    )

    /**
     * Whether a candidate's artist is of the playlist's style: "Dans ton style" holds the families the listener plays; the ambiances turn away an artist
     * plainly of another world. An artist whose tags are not known is not turned away: the rest of the ranking still has to find them close to the listener.
     */
    internal fun suits(kind: RecoKind, c: Candidate, ctx: RankContext): Boolean {
        val tags = c.credits.firstOrNull()?.let { ctx.artistTags[it] }.orEmpty()
        if (tags.isEmpty()) return true
        FORBIDDEN[kind]?.let { if (StyleFit.forbidden(it, tags)) return false }
        if (kind == RecoKind.STYLE && ctx.likedFamilies.isNotEmpty()) return StyleFit.belongs(ctx.likedFamilies, tags, unknownOk = true)
        return true
    }

    private fun decadeMatch(c: Candidate, profile: TasteProfile): Double {
        val year = c.release.year.takeIf { it > 0 } ?: return 0.0
        return profile.decades[year / 10 * 10] ?: 0.0
    }

    private fun scoreFor(kind: RecoKind, c: Candidate, ctx: RankContext): Scored? {
        val profile = ctx.profile
        val credits = c.credits
        val aff = profile.creditAffinity(credits)
        val prov = c.provenance.maxByOrNull { it.seedAffinity * (if (it.kind == ProvKind.ARTIST) 1.0 else 0.65) }
        val provAff = c.provenance.maxOfOrNull { p ->
            when (p.kind) {
                ProvKind.ARTIST -> p.seedAffinity
                ProvKind.RELATED_TRACK -> p.seedAffinity * 0.65
                ProvKind.COLLAB -> p.seedAffinity * 0.7
                ProvKind.GENRE -> p.seedAffinity * 0.3
                ProvKind.MOOD_QUERY -> 0.0
            }
        } ?: 0.0
        // Found several ways is a stronger tie than found one way.
        val agreement = (c.provenance.map { it.kind to it.seed }.distinct().size - 1).coerceIn(0, 2) * 0.05
        val taste = (max(aff, provAff) + agreement).coerceIn(0.0, 1.0)
        val artistKnown = credits.any { it in profile.knownArtists || ctx.local.hasArtist(it) }
        val novelty = if (artistKnown) 0.3 else 1.0
        val dislike = profile.dislike(credits, kind.key)
        // A "related" entry not yet known to be a song is ranked behind the ones that are: it is checked before it is offered.
        val depth = if (kind == RecoKind.DISCOVERY || kind == RecoKind.STYLE || kind == RecoKind.DAILY) depthAdjust(c, ctx) else 0.0
        val base = taste - 0.5 * dislike - historyPenalty(c, ctx) + jitter(c, ctx) + depth - if (c.confirmedSong) 0.0 else 0.1
        val cluster = prov?.seed?.let(ArtistNames::key)?.ifEmpty { null } ?: credits.firstOrNull().orEmpty()
        val seedName = prov?.seed.orEmpty()
        val artistName = credits.firstOrNull()?.let { profile.nameOf(it) }.orEmpty()
        val bestCredit = credits.maxByOrNull { profile.affinity(it) }

        fun tasteReason(): Reason = when {
            aff >= 0.2 && bestCredit != null -> Reason(ReasonCode.LISTENED_ARTIST, profile.nameOf(bestCredit))
            prov?.kind == ProvKind.COLLAB -> Reason(ReasonCode.COLLAB, seedName)
            prov?.kind == ProvKind.RELATED_TRACK -> Reason(ReasonCode.RELATED_TO, seedName)
            prov?.kind == ProvKind.GENRE -> Reason(ReasonCode.GENRE, seedName)
            prov?.kind == ProvKind.ARTIST && prov.seedAffinity >= 0.15 -> Reason(ReasonCode.LISTENED_ARTIST, seedName)
            aff > 0.0 -> Reason(ReasonCode.LIBRARY_ARTIST, artistName)
            else -> Reason(ReasonCode.NONE)
        }

        // A remix, a live cut or an acoustic take of a song the listener already has is not new to them: kept for the
        // energetic playlist only, where a remix is the point.
        val variantOfKnown = TrackIdentity.baseIdentity(c.title, c.artist) in profile.knownBases
        if (variantOfKnown && kind != RecoKind.ENERGY) return null

        // The playlist's own style, judged as Home judges its rows: on the artist's tags.
        if (!suits(kind, c, ctx)) return null

        return when (kind) {
            RecoKind.DISCOVERY -> {
                if (taste < 0.2) return null
                // Mostly artists the listener does not know; a known artist's unheard song is a lesser discovery.
                val score = 0.5 * novelty + 0.45 * taste + 0.05 * decadeMatch(c, profile) + (base - taste)
                val reason = if (!artistKnown && seedName.isNotEmpty() && prov?.kind != ProvKind.GENRE) Reason(ReasonCode.EXPLORATION, seedName) else tasteReason()
                Scored(c, score, reason, cluster)
            }
            RecoKind.STYLE -> {
                if (taste < 0.25) return null
                val score = 0.7 * taste + 0.15 * novelty + 0.15 * decadeMatch(c, profile) + (base - taste)
                Scored(c, score, tasteReason(), cluster)
            }
            RecoKind.DAILY -> {
                if (taste < 0.1) return null
                val score = 0.5 * taste + 0.3 * novelty + 0.2 * decadeMatch(c, profile) + (base - taste)
                Scored(c, score, tasteReason(), cluster)
            }
            RecoKind.NEW -> {
                if (!isRecentRelease(c, ctx)) return null
                if (taste < 0.15) return null
                val score = 0.5 * taste + 0.35 * recency(c, ctx) + 0.15 * novelty + (base - taste)
                val reason = if (aff >= 0.25 && bestCredit != null) Reason(ReasonCode.NEW_FROM_ARTIST, profile.nameOf(bestCredit)) else Reason(ReasonCode.NEW_RELEASE)
                Scored(c, score, reason, cluster)
            }
            RecoKind.ENERGY, RecoKind.CHILL, RecoKind.NIGHT -> {
                val mood = when (kind) { RecoKind.ENERGY -> Mood.ENERGY; RecoKind.CHILL -> Mood.CHILL; else -> Mood.NIGHT }
                val verdict = MoodModel.verdict(c, mood) ?: return null
                if (verdict < MoodModel.THRESHOLD) return null
                // Night and chill are told apart: a song that reads more as one is not put in the other.
                val fit = MoodModel.fit(c.title, c.album, c.durationMs, c.tags)
                if (mood == Mood.CHILL && fit.night > fit.chill + 0.05) return null
                if (mood == Mood.NIGHT && fit.chill > fit.night + 0.05) return null
                // The ambience decides what goes in, but only among songs with a tie to the listener: a query for "chill"
                // answers with whatever its source ranks first, and that is not the listener's music.
                if (taste < MOOD_MIN_TASTE) return null
                val score = 0.55 * verdict + 0.45 * taste + (base - taste)
                val code = when (mood) { Mood.CHILL -> ReasonCode.CALM; Mood.NIGHT -> ReasonCode.NIGHT; Mood.ENERGY -> ReasonCode.ENERGETIC }
                Scored(c, score, Reason(code), cluster)
            }
        }
    }
}

/** When a playlist is due again. */
object Renewal {
    /** A daily playlist is due on a new calendar day; a weekly one after seven days. */
    fun isDue(cadence: Cadence, lastAtMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (lastAtMs <= 0L) return true
        if (nowMs < lastAtMs) return false
        return when (cadence) {
            Cadence.DAILY -> {
                val last = java.time.Instant.ofEpochMilli(lastAtMs).atZone(zone).toLocalDate()
                val now = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
                now.isAfter(last)
            }
            Cadence.WEEKLY -> nowMs - lastAtMs >= 7L * 86_400_000L
        }
    }

    /** The value tie-breaking varies with: the day for the daily mix, the week number for the others. */
    fun variationOf(kind: RecoKind, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val day = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay()
        return if (kind.cadence == Cadence.DAILY) day else Math.floorDiv(day, 7L)
    }
}
