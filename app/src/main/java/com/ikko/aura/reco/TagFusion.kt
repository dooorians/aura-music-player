package com.ikko.aura.reco

import kotlin.math.sqrt

/**
 * Genres and tags from every place they come from — the file's own genre tag, what the tag scanner read, the artist's Last.fm tags — fused into one
 * weighted vector per song, and compared with a soft cosine.
 *
 * Why not a plain set: a song tagged "Hip-Hop" by its file and listed with "rap, hip hop, trap, seen live" by Last.fm is one style, said four ways,
 * plus one piece of noise. Here:
 *  - every tag goes through [GenreNormalizer.normalize] (synonyms, spellings, ID3v1 numbers), so the four ways become two genres;
 *  - noise that is no style at all ("seen live", "favorites", a year) is dropped;
 *  - each source has a weight (the file's own tag is the listener's word, Last.fm's is the crowd's), and a tag's weight decays with its rank in its
 *    list (Last.fm lists the most telling first);
 *  - genres too broad to tell two songs apart ("Pop", "Rock") are kept but count for a third;
 *  - two vectors are compared with a soft cosine, where genres of one family ("Dream Pop", "Synthpop") or of neighbouring families count in part
 *    ([GenreNormalizer.similarity]). Identical vectors score 1, styles with nothing in common 0.
 *
 * Pure, so it is checked on the JVM.
 */
object TagFusion {
    /** What a file's own genre tag weighs: the listener's (or their tagger's) word. */
    const val W_FILE = 1.0
    /** What the genres the tag scanner read from the file weigh. */
    const val W_SCANNED = 0.9
    /** What the artist's tags weigh (Last.fm and the like): telling, but about the artist and not this song. */
    const val W_ARTIST = 0.7

    /** How fast a tag's weight decays with its rank in its list: 1 / (1 + k·rank). */
    private const val RANK_DECAY = 0.35
    /** The share a genre too broad to tell songs apart keeps. */
    private const val GENERIC_SHARE = 0.35
    /** Past this many tags a list says nothing more. */
    const val MAX_TAGS = 8

    /** Tags that are about the listener, the listen or the file, not the music. */
    private val NOISE = setOf(
        "seen live", "favorites", "favourites", "favorite", "favourite", "my favorite", "my favourite", "favorite songs", "love", "loved",
        "awesome", "beautiful", "amazing", "cool", "good", "great", "best", "albums i own", "spotify", "youtube", "under 2000 listeners",
        "female vocalists", "male vocalists", "female vocalist", "male vocalist", "female", "male", "vocal", "vocals", "singer",
        "all", "check out", "to listen", "wishlist", "radio", "covers", "cover", "live", "remix", "single", "ep", "album",
    )

    /** One genre as the vectors name it, or null when [raw] is no style: normalised, lowercased, noise out. */
    fun canonical(raw: String?): String? {
        val t = raw?.trim()?.lowercase().orEmpty()
        if (t.isEmpty() || t in NOISE) return null
        val g = GenreNormalizer.normalize(raw) ?: return null
        val key = g.lowercase()
        return if (key in NOISE) null else key
    }

    /**
     * A song's styles: unit-length [weights] (genre → weight), and a [confidence] 0..1 that says how specific they are — 1 when every tag names a
     * real style, [GENERIC_SHARE] when all there is is "Pop". The cosine alone would call two songs tagged only "Pop" identical; the confidence
     * keeps that a weak tie.
     */
    class GenreVector(val weights: Map<String, Double>, val confidence: Double) {
        val isEmpty: Boolean get() = weights.isEmpty()

        companion object {
            val Empty = GenreVector(emptyMap(), 0.0)
        }
    }

    /**
     * One vector from several lists, each with its weight. A genre several sources agree on adds up: agreement is evidence. [GenreVector.Empty] when
     * nothing usable was given.
     */
    fun fuse(sources: List<Pair<List<String>, Double>>): GenreVector {
        val acc = HashMap<String, Double>()
        var raw = 0.0
        var discounted = 0.0
        for ((tags, weight) in sources) {
            if (!(weight > 0.0) || !weight.isFinite()) continue
            var rank = 0
            val seen = HashSet<String>()
            for (tag in tags) {
                if (rank >= MAX_TAGS) break
                val g = canonical(tag) ?: continue
                if (!seen.add(g)) continue
                val w = weight / (1.0 + RANK_DECAY * rank)
                val share = if (GenreNormalizer.isGeneric(g)) GENERIC_SHARE else 1.0
                acc.merge(g, w * share, Double::plus)
                raw += w
                discounted += w * share
                rank++
            }
        }
        val weights = normalized(acc)
        if (weights.isEmpty() || !(raw > 0.0)) return GenreVector.Empty
        return GenreVector(weights, (discounted / raw).coerceIn(0.0, 1.0))
    }

    /** A vector of [genres] all weighing the same (what a caller with a plain set has). */
    fun uniform(genres: Collection<String>): GenreVector = fuse(listOf(genres.toList() to 1.0))

    private fun normalized(v: Map<String, Double>): Map<String, Double> {
        val norm = sqrt(v.values.sumOf { it * it })
        if (!(norm > 0.0) || !norm.isFinite()) return emptyMap()
        return v.mapValues { it.value / norm }
    }

    /** How alike two songs' styles are, 0..1: the soft [cosine] of their weights, scaled by how specific both are. */
    fun similarity(a: GenreVector, b: GenreVector): Double {
        if (a.isEmpty || b.isEmpty) return 0.0
        val c = cosine(a.weights, b.weights)
        return (c * sqrt(a.confidence * b.confidence)).coerceIn(0.0, 1.0)
    }

    /**
     * Soft cosine of two vectors (see the class): Σᵢⱼ aᵢ bⱼ s(i,j) / √(Σ aᵢaⱼ s(i,j) · Σ bᵢbⱼ s(i,j)), with s from [GenreNormalizer.similarity].
     * 0 when either is empty; always within 0..1.
     */
    fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val cross = bilinear(a, b)
        if (cross <= 0.0) return 0.0
        val denom = sqrt(bilinear(a, a) * bilinear(b, b))
        if (!(denom > 0.0) || !denom.isFinite()) return 0.0
        return (cross / denom).coerceIn(0.0, 1.0)
    }

    private fun bilinear(a: Map<String, Double>, b: Map<String, Double>): Double {
        var sum = 0.0
        for ((x, wx) in a) for ((y, wy) in b) {
            val s = if (x == y) 1.0 else GenreNormalizer.similarity(x, y)
            if (s > 0.0) sum += wx * wy * s
        }
        return sum
    }
}
