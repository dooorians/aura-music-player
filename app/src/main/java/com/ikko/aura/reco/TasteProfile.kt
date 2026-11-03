package com.ikko.aura.reco

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** How a listen ended: told apart so a skip is a rejection and an interruption is not. */
enum class Outcome { COMPLETED, PARTIAL, SKIPPED, SKIPPED_EARLY, INTERRUPTED }

/** One song heard (or started), as the profile reads it. */
data class ListenRecord(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val year: Int,
    val durationMs: Long,
    val playedMs: Long,
    val outcome: Outcome,
    val atMs: Long,
    /** The playlist ([RecoKind.key]) it was played from, if it was played from one. */
    val context: String?,
    /** Credits a source lists apart from the artist field. */
    val extraCredits: List<String> = emptyList(),
)

/** A song the listener put somewhere on purpose (a heart, a playlist). */
data class MarkedTrack(val trackId: String, val title: String, val artist: String, val atMs: Long, val year: Int = 0)

data class LibraryEntry(val title: String, val artist: String, val year: Int = 0, val genre: String? = null, val durationMs: Long = 0)

data class AlbumPlayRecord(val artist: String, val plays: Int, val lastPlayedAtMs: Long)

/**
 * [DISLIKE_ALBUM] and [LIKE_ALBUM]: an album refused, or liked, as a whole; its [FeedbackRecord.subject] is an [AlbumKeys] key, its title
 * the album's name. An album is written once per key it answers to (its reference, its name), so it counts once in the profile.
 */
enum class FeedbackKind { LIKE, DISLIKE, HIDE, KNOWN, BLOCK_ARTIST, DISLIKE_ALBUM, LIKE_ALBUM }

/** An explicit answer of the listener. For [FeedbackKind.BLOCK_ARTIST] [subject] is the artist's name, else a track's id. */
data class FeedbackRecord(
    val kind: FeedbackKind,
    val subject: String,
    val artist: String,
    val title: String,
    val atMs: Long,
    val context: String?,
)

/** A seed of the profile the listener typed (an artist they like), for when there is little to learn from. */
data class ProfileInputs(
    val listens: List<ListenRecord> = emptyList(),
    val favorites: List<MarkedTrack> = emptyList(),
    val playlistAdds: List<MarkedTrack> = emptyList(),
    val library: List<LibraryEntry> = emptyList(),
    val albumPlays: List<AlbumPlayRecord> = emptyList(),
    val feedback: List<FeedbackRecord> = emptyList(),
    val seedArtists: List<String> = emptyList(),
    /** The ones of [seedArtists] that were chosen in the taste picker. */
    val pickedArtists: List<String> = emptyList(),
    val nowMs: Long,
)

/** A song that says what the listener likes: a seed for "similar to" lookups. */
data class SeedTrack(val title: String, val artist: String, val weight: Double, val trackId: String?)

/**
 * What is known of the listener's taste: how much each artist is liked (0..1), what is mildly disliked, which genres
 * and decades the library leans to, and how much evidence there is — little evidence is a cold start, handled
 * differently by the ranking.
 */
class TasteProfile(
    /** Artist key → affinity 0..1. */
    val artists: Map<String, Double>,
    val names: Map<String, String>,
    /** Artist key → generic dislike 0..1 (skips, dislikes): a nudge, never an exclusion. */
    val penalty: Map<String, Double>,
    /** Context → artist key → dislike 0..1 for skips made in that playlist. */
    val contextPenalty: Map<String, Map<String, Double>>,
    val genres: Map<String, Double>,
    val decades: Map<Int, Double>,
    val seeds: List<SeedTrack>,
    /** Artists the listener knows (has files of, listened to, or said they know). */
    val knownArtists: Set<String>,
    /** Identity keys of songs the listener has, listened to or said they know. */
    val knownTracks: Set<String>,
    /** The same songs with remix / live / acoustic decorations set aside (see [TrackIdentity.baseIdentity]). */
    val knownBases: Set<String>,
    val evidence: Double,
) {
    val coldStart: Boolean get() = evidence < COLD_START

    fun affinity(artistKey: String): Double = artists[artistKey] ?: 0.0

    /** The strongest affinity among the credited artists, the primary one counting fully and the others a little less. */
    fun creditAffinity(credits: List<String>): Double =
        credits.withIndex().maxOfOrNull { (i, k) -> affinity(k) * if (i == 0) 1.0 else 0.8 } ?: 0.0

    /** How much the credited artists are disliked in [context] (their own context counts in full, others a third). */
    fun dislike(credits: List<String>, context: String?): Double {
        var worst = 0.0
        for (k in credits) {
            val generic = penalty[k] ?: 0.0
            val inContext = context?.let { contextPenalty[it]?.get(k) } ?: 0.0
            val elsewhere = contextPenalty.entries.filter { it.key != context }.maxOfOrNull { it.value[k] ?: 0.0 } ?: 0.0
            worst = max(worst, max(generic, max(inContext, elsewhere / 3.0)))
        }
        return worst
    }

    fun topArtists(n: Int, excluding: Set<String> = emptySet()): List<String> =
        artists.entries.filter { it.key !in excluding && it.value > 0.0 }.sortedByDescending { it.value }.take(n).map { it.key }

    fun nameOf(key: String): String = names[key] ?: key

    companion object {
        /** Below this much positive evidence the profile leans on the library and the typed seeds. */
        const val COLD_START = 6.0
        val Empty = TasteProfile(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptySet(), emptySet(), emptySet(), 0.0)
    }
}

/** Builds a [TasteProfile] from the signals; pure and deterministic for the same inputs. */
object TasteProfileBuilder {

    private const val DAY_MS = 86_400_000.0
    private const val RECENT_HALF_LIFE_DAYS = 30.0
    private const val LONG_HALF_LIFE_DAYS = 365.0

    // Weights of a signal, in "one full listen" units.
    private const val W_COMPLETED = 1.0
    private const val W_REPLAY = 0.3
    private const val W_FAVORITE = 2.0
    private const val W_PLAYLIST = 1.2
    private const val W_LIKE = 3.0
    private const val W_SEED = 1.8
    /** An artist chosen on purpose in the picker counts more than a like of one of their songs. */
    private const val W_PICKED = 4.5
    private const val W_ALBUM_PLAY = 0.5

    private class Acc {
        var recent = 0.0
        var long = 0.0
        var negative = 0.0
        val byContext = HashMap<String, Double>()
        var display: String? = null
        var count = 0
        var libraryTracks = 0
        var seeded = false
        fun add(w: Double, ageDays: Double) {
            recent += w * 0.5.pow(ageDays / RECENT_HALF_LIFE_DAYS)
            long += w * 0.5.pow(ageDays / LONG_HALF_LIFE_DAYS)
        }
    }

    fun build(inputs: ProfileInputs): TasteProfile {
        val now = inputs.nowMs
        val acc = HashMap<String, Acc>()
        val knownTracks = HashSet<String>()
        val knownBases = HashSet<String>()
        val knownArtists = HashSet<String>()
        fun know(title: String, artist: String) {
            knownTracks += TrackIdentity.key(title, artist)
            knownBases += TrackIdentity.baseIdentity(title, artist)
        }
        val seedWeights = HashMap<String, SeedTrack>()
        val genreAcc = HashMap<String, Double>()
        val decadeAcc = HashMap<Int, Double>()
        var evidence = 0.0

        fun accOf(raw: String, name: String?): List<Pair<String, Acc>> =
            ArtistNames.split(raw).mapIndexed { i, k ->
                val a = acc.getOrPut(k) { Acc() }
                if (a.display == null && i == 0) a.display = name ?: ArtistNames.cleanChannel(raw)
                k to a
            }

        // Primary artist counts fully, featured ones for half.
        fun credit(raw: String, extra: List<String>, title: String, weight: Double, ageDays: Double, context: String? = null, negative: Boolean = false) {
            val parts = accOf(raw, null) + extra.flatMap { accOf(it, it) } +
                ArtistNames.featuredIn(title).map { k -> k to acc.getOrPut(k) { Acc() } }
            parts.distinctBy { it.first }.forEachIndexed { i, (_, a) ->
                val w = weight * if (i == 0) 1.0 else 0.5
                if (negative) {
                    a.negative += w * 0.5.pow(ageDays / RECENT_HALF_LIFE_DAYS * 0.5)
                    if (context != null) a.byContext.merge(context, w, Double::plus)
                } else a.add(w, ageDays)
            }
        }

        fun ageDays(atMs: Long) = max(0.0, (now - atMs) / DAY_MS)

        // Library composition: a quiet prior, never more than a handful of listens' worth.
        for (e in inputs.library) {
            val keys = ArtistNames.split(e.artist)
            keys.firstOrNull()?.let { acc.getOrPut(it) { Acc() }.also { a -> a.libraryTracks++; if (a.display == null) a.display = ArtistNames.cleanChannel(e.artist) } }
            keys.forEach { knownArtists += it }
            know(e.title, e.artist)
            e.genre?.takeIf { it.isNotBlank() }?.let { genreAcc.merge(it.trim(), 1.0, Double::plus) }
            if (e.year in 1950..2100) decadeAcc.merge(e.year / 10 * 10, 1.0, Double::plus)
        }

        // Listening: a finished listen counts, a partial one by how much was heard, a skip pushes the other way; an
        // interruption (the queue was replaced, the app closed) neither.
        val completionsByTrack = HashMap<String, Int>()
        for (l in inputs.listens) {
            val age = ageDays(l.atMs)
            val fraction = if (l.durationMs > 0) (l.playedMs.toDouble() / l.durationMs).coerceIn(0.0, 1.0) else 0.5
            know(l.title, l.artist)
            ArtistNames.split(l.artist).forEach { knownArtists += it }
            when (l.outcome) {
                Outcome.COMPLETED -> {
                    val n = completionsByTrack.merge(l.trackId, 1, Int::plus)!!
                    val w = W_COMPLETED + if (n > 1) W_REPLAY else 0.0
                    credit(l.artist, l.extraCredits, l.title, w, age)
                    evidence += w
                    seedWeights.merge(l.trackId, SeedTrack(l.title, l.artist, w, l.trackId)) { a, b -> SeedTrack(a.title, a.artist, a.weight + b.weight, a.trackId) }
                    if (l.year in 1950..2100) decadeAcc.merge(l.year / 10 * 10, w, Double::plus)
                }
                Outcome.PARTIAL -> {
                    val w = 0.2 + 0.5 * fraction
                    credit(l.artist, l.extraCredits, l.title, w, age)
                    evidence += w
                }
                Outcome.SKIPPED_EARLY -> credit(l.artist, l.extraCredits, l.title, 0.7, age, l.context, negative = true)
                Outcome.SKIPPED -> credit(l.artist, l.extraCredits, l.title, 0.4, age, l.context, negative = true)
                Outcome.INTERRUPTED -> if (fraction > 0.5) {
                    credit(l.artist, emptyList(), l.title, 0.1, age)
                }
            }
        }

        // Albums played before the track-level history existed.
        for (p in inputs.albumPlays) {
            val w = W_ALBUM_PLAY * ln(1.0 + p.plays) * 1.2
            credit(p.artist, emptyList(), "", w, ageDays(p.lastPlayedAtMs))
            evidence += min(w, 1.5)
        }

        for (f in inputs.favorites) {
            val age = ageDays(f.atMs)
            credit(f.artist, emptyList(), f.title, W_FAVORITE, age)
            know(f.title, f.artist)
            evidence += W_FAVORITE
            seedWeights.merge(f.trackId, SeedTrack(f.title, f.artist, W_FAVORITE, f.trackId)) { a, b -> SeedTrack(a.title, a.artist, a.weight + b.weight, a.trackId) }
            if (f.year in 1950..2100) decadeAcc.merge(f.year / 10 * 10, W_FAVORITE, Double::plus)
        }
        for (p in inputs.playlistAdds) {
            credit(p.artist, emptyList(), p.title, W_PLAYLIST, ageDays(p.atMs))
            know(p.title, p.artist)
            evidence += W_PLAYLIST
        }

        // Explicit answers.
        val blocked = HashSet<String>()
        val albumsAnswered = HashSet<String>()
        for (fb in inputs.feedback) {
            val age = ageDays(fb.atMs)
            when (fb.kind) {
                FeedbackKind.LIKE -> {
                    know(fb.title, fb.artist)
                    credit(fb.artist, emptyList(), fb.title, W_LIKE, age)
                    evidence += W_LIKE
                    seedWeights.merge(fb.subject, SeedTrack(fb.title, fb.artist, W_LIKE, fb.subject)) { a, b -> SeedTrack(a.title, a.artist, a.weight + b.weight, a.trackId) }
                }
                FeedbackKind.DISLIKE -> credit(fb.artist, emptyList(), fb.title, 0.8, age, fb.context, negative = true)
                // An album refused: said once for the album, whatever its number of songs, against its artist in the context it was refused in.
                FeedbackKind.DISLIKE_ALBUM ->
                    if (albumsAnswered.add("-" + AlbumKeys.ofName(fb.title, fb.artist))) credit(fb.artist, emptyList(), fb.title, 1.2, age, fb.context, negative = true)
                // An album liked: said once for the album too, as strongly as a song is, for its artist.
                FeedbackKind.LIKE_ALBUM ->
                    if (albumsAnswered.add("+" + AlbumKeys.ofName(fb.title, fb.artist))) {
                        credit(fb.artist, emptyList(), fb.title, W_LIKE, age)
                        evidence += W_LIKE
                    }
                // Hiding a track says nothing of its artist.
                FeedbackKind.HIDE -> Unit
                // "Already known" is not a rejection: it only makes the song, and its artist, familiar.
                FeedbackKind.KNOWN -> {
                    know(fb.title, fb.artist)
                    ArtistNames.split(fb.artist).forEach { knownArtists += it }
                }
                FeedbackKind.BLOCK_ARTIST -> blocked += ArtistNames.key(fb.subject)
            }
        }

        // Artists the listener typed in: a strong prior for someone with nothing else to go on.
        // In the order given (what is played most first, for those from Last.fm): the first count in full, the last less.
        val seedCount = inputs.seedArtists.size
        val picked = inputs.pickedArtists.mapTo(HashSet()) { ArtistNames.key(it) }
        for ((i, s) in inputs.seedArtists.withIndex()) {
            val k = ArtistNames.key(s)
            if (k.isEmpty()) continue
            val a = acc.getOrPut(k) { Acc() }
            if (a.display == null) a.display = s.trim()
            a.seeded = true
            a.add(if (k in picked) W_PICKED else W_SEED * (1.0 - 0.6 * i / max(1, seedCount - 1)), 0.0)
            knownArtists += k
            evidence += W_SEED * 0.5
        }

        val artists = HashMap<String, Double>()
        val names = HashMap<String, String>()
        val penalty = HashMap<String, Double>()
        val contextPenalty = HashMap<String, HashMap<String, Double>>()
        for ((k, a) in acc) {
            if (k in blocked) continue
            a.display?.let { names[k] = it }
            // Two saturating clocks — the last weeks and the last year — so one listen is a small step and a habit a
            // large one, and neither a burst nor an old habit decides alone.
            val listen = 0.5 * saturate(a.recent, 2.5) + 0.5 * saturate(a.long, 5.0)
            val library = 0.25 * saturate(a.libraryTracks.toDouble(), 10.0)
            val positive = 1.0 - (1.0 - listen) * (1.0 - library)
            val dislike = 0.7 * saturate(a.negative, 3.0)
            // A skip dents a liking, never cancels it out.
            val net = positive * (1.0 - 0.6 * dislike)
            if (net > 0.0) artists[k] = net
            if (dislike > 0.02) penalty[k] = dislike
            for ((ctx, w) in a.byContext) {
                contextPenalty.getOrPut(ctx) { HashMap() }[k] = 0.7 * saturate(w, 2.0)
            }
        }
        val genreTotal = genreAcc.values.sum().takeIf { it > 0 } ?: 1.0
        val decadeTotal = decadeAcc.values.sum().takeIf { it > 0 } ?: 1.0
        return TasteProfile(
            artists = artists,
            names = names,
            penalty = penalty,
            contextPenalty = contextPenalty,
            genres = genreAcc.mapValues { it.value / genreTotal },
            decades = decadeAcc.mapValues { it.value / decadeTotal },
            seeds = seedWeights.values.sortedByDescending { it.weight }.take(40),
            knownArtists = knownArtists,
            knownTracks = knownTracks,
            knownBases = knownBases,
            evidence = evidence,
        )
    }

    private fun saturate(x: Double, k: Double) = 1.0 - exp(-max(0.0, x) / k)
}
