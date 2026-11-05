package com.ikko.aura.reco

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** The shape of the energy along a playlist, on a relative scale (0 the calmest song of the list, 1 the most intense). */
enum class ArcShape {
    /** Warm up, peak around 60 %, come back down: a set that breathes. The default for the broad mixes. */
    WAVE,
    /** Rise steadily, then hold high: a workout, a party. */
    BUILD,
    /** Start where the listener is, then settle lower and lower: the night. */
    WIND_DOWN,
    /** Stay level with a gentle sway: background, focus, chill. */
    PLATEAU;

    /** The target energy (0..1) at the relative position [t] (0 the first song, 1 the last). */
    fun target(t: Double): Double {
        val x = if (t.isFinite()) t.coerceIn(0.0, 1.0) else 0.0
        return when (this) {
            // sin(π·x^p) peaks where x^p = ½: p = ln ½ / ln 0.6 puts the peak at 60 % of the list.
            WAVE -> 0.3 + 0.55 * sin(PI * x.pow(WAVE_EXPONENT))
            // The small linear term keeps the plateau rising (or falling): every position has its own rank (see [PlaylistSequencer]).
            BUILD -> 0.15 + 0.7 * smoothstep(0.0, 0.75, x) + 0.05 * x
            WIND_DOWN -> 0.8 - 0.6 * smoothstep(0.1, 1.0, x) - 0.05 * x
            PLATEAU -> 0.5 + 0.12 * sin(2 * PI * x)
        }
    }

    companion object {
        private val WAVE_EXPONENT = kotlin.math.ln(0.5) / kotlin.math.ln(0.6)

        private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
            val k = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
            return k * k * (3 - 2 * k)
        }

        /** The arc that suits each generated playlist. */
        fun of(kind: RecoKind): ArcShape = when (kind) {
            RecoKind.ENERGY -> BUILD
            RecoKind.NIGHT -> WIND_DOWN
            RecoKind.CHILL -> PLATEAU
            RecoKind.DISCOVERY, RecoKind.STYLE, RecoKind.DAILY, RecoKind.NEW -> WAVE
        }
    }
}

/**
 * One song as the sequencer sees it. [bpm] and [energy] may be unknown (null, or anything [Tempo.sanitize] / [Double.isFinite] rejects): an unknown
 * value is scored as a neutral, average transition and never pushed to the end. [energy] may be on any scale: it is rank-normalised within the list.
 */
data class SeqItem<T>(
    val item: T,
    val key: String,
    val artist: String,
    val album: String = "",
    val bpm: Double? = null,
    val energy: Double? = null,
    /** How much the song belongs in the list; higher is better. Any finite scale. */
    val relevance: Double = 0.0,
    /** A song (or an artist) the listener already knows: what discovery is balanced against. */
    val familiar: Boolean = true,
)

/**
 * Orders a chosen set of songs so it flows: each song follows the one before it in tempo (half and double time included), the energy follows an
 * [ArcShape], the same artist never comes back within [Weights.artistGap] songs (an album within [Weights.albumGap]) when the list allows it, unknown
 * artists are anchored by familiar ones, and the strongest songs are not buried at the end.
 *
 * Greedy with a bounded look-ahead window: at each position, the remaining songs (best relevance first) are scored by the cost of placing them there,
 * and the cheapest one is placed. O(n · w) for n songs and a window of w, so it is linear on large lists and exact on playlists (n ≤ w). A final pass
 * swaps out any same-artist neighbours the greedy pass was forced into. Deterministic: the same input gives the same order.
 */
object PlaylistSequencer {

    data class Weights(
        val tempo: Double = 1.0,
        val energyArc: Double = 1.1,
        val energyStep: Double = 0.5,
        val relevance: Double = 0.8,
        val artistGap: Int = 3,
        val albumGap: Int = 2,
        val artistRepeat: Double = 6.0,
        val albumRepeat: Double = 2.0,
        /** More than this many unfamiliar songs in a row costs [noveltyRunCost]: a discovery reads better anchored by something known. */
        val maxNovelRun: Int = 2,
        val noveltyRunCost: Double = 0.6,
        /** How many remaining songs are looked at for each position. */
        val window: Int = 256,
    )

    /** What a missing energy costs against the arc: the mean distance of a random song to any target, so unknown is average, not worst. */
    private const val NEUTRAL_ARC_COST = 1.0 / 3.0

    private class Node<T>(
        val src: SeqItem<T>,
        val bpm: Double?,
        val energy: Double?,
        val relevance: Double,
        val index: Int,
    )

    fun <T> order(items: List<SeqItem<T>>, shape: ArcShape = ArcShape.WAVE, weights: Weights = Weights(), cancel: () -> Unit = {}): List<T> {
        if (items.isEmpty()) return emptyList()
        // Duplicates (the same key twice) are one song.
        val unique = LinkedHashMap<String, SeqItem<T>>(items.size * 2)
        for (it in items) unique.putIfAbsent(it.key, it)
        val src = unique.values.toList()
        if (src.size == 1) return listOf(src[0].item)

        val energy = rankNormalize(src.map { it.energy })
        val relevance = minMaxNormalize(src.map { it.relevance })
        val nodes = src.mapIndexed { i, s -> Node(s, Tempo.sanitize(s.bpm), energy[i], relevance[i], i) }
        val mixed = nodes.any { it.src.familiar } && nodes.any { !it.src.familiar }
        val n = nodes.size

        // The energies are ranks (uniform over 0..1), so the arc is too: the target of each position is its rank among the targets of all positions.
        // A build then asks for the calmest song first and the most intense last, whatever the shape's absolute levels, and no position asks for
        // an energy the list does not have.
        val targets = rankNormalize((0 until n).map { shape.target(if (n <= 1) 0.0 else it.toDouble() / (n - 1)) }).map { it ?: 0.5 }
        // Best relevance first, so the window always holds the strongest of what is left.
        val remaining = nodes.sortedWith(compareByDescending<Node<T>> { it.relevance }.thenBy { it.index }).toMutableList()
        val out = ArrayList<Node<T>>(nodes.size)
        while (remaining.isNotEmpty()) {
            cancel()
            val t = if (n <= 1) 0.0 else out.size.toDouble() / (n - 1)
            val target = targets[out.size]
            val w = minOf(weights.window, remaining.size)
            var bestIdx = 0
            var bestCost = Double.POSITIVE_INFINITY
            for (i in 0 until w) {
                val c = cost(remaining[i], out, t, target, weights, mixed)
                if (c < bestCost - 1e-12) { bestCost = c; bestIdx = i }
            }
            out += remaining.removeAt(bestIdx)
        }
        repairAdjacency(out, weights)
        return out.map { it.src.item }
    }

    private fun <T> cost(c: Node<T>, out: List<Node<T>>, t: Double, target: Double, w: Weights, mixed: Boolean): Double {
        val prev = out.lastOrNull()
        var cost = 0.0
        // Tempo: how well it follows the song before; unknown on either side is a neutral transition.
        if (prev != null) cost += w.tempo * (1.0 - Tempo.affinityOrNeutral(prev.bpm, c.bpm))
        // Energy: where the arc wants the list to be, and no cliff from the song before.
        cost += w.energyArc * (c.energy?.let { abs(it - target) } ?: NEUTRAL_ARC_COST)
        if (prev?.energy != null && c.energy != null) cost += w.energyStep * (abs(prev.energy - c.energy) - 0.25).coerceAtLeast(0.0)
        // The strongest songs early, but less and less so: the end of the list is for the arc.
        cost += w.relevance * (1.0 - c.relevance) * (1.0 - 0.6 * t)
        // Spacing: the same artist (or album) too soon is a wall that grows as the gap shrinks.
        val size = out.size
        for (back in 1..maxOf(w.artistGap, w.albumGap)) {
            val p = out.getOrNull(size - back) ?: break
            if (back <= w.artistGap && c.src.artist.isNotEmpty() && p.src.artist == c.src.artist) cost += w.artistRepeat * (w.artistGap - back + 1) / w.artistGap
            if (back <= w.albumGap && c.src.album.isNotEmpty() && p.src.album == c.src.album) cost += w.albumRepeat * (w.albumGap - back + 1) / w.albumGap
        }
        // Discovery anchored by the familiar.
        if (mixed && !c.src.familiar) {
            var run = 0
            for (i in size - 1 downTo 0) { if (out[i].src.familiar) break; run++ }
            if (run >= w.maxNovelRun) cost += w.noveltyRunCost * (run - w.maxNovelRun + 1)
        }
        return if (cost.isFinite()) cost else Double.MAX_VALUE
    }

    /**
     * Neighbours by the same artist the greedy pass was forced into (it fills the list from the front, and can leave one artist's songs for the end):
     * a local search swaps a song of such a pair with any song of the list, before or after it, whenever that lowers the number of same-artist
     * neighbours. Bounded to a few passes; each swap strictly lowers the count, so it ends.
     */
    private fun <T> repairAdjacency(list: MutableList<Node<T>>, w: Weights) {
        if (w.artistGap <= 0 || list.size < 3) return
        fun same(i: Int, j: Int): Boolean {
            if (i < 0 || j < 0 || i >= list.size || j >= list.size) return false
            val a = list[i].src.artist
            return a.isNotEmpty() && a == list[j].src.artist
        }
        // The same-artist pairs that involve positions i or j.
        fun around(i: Int, j: Int): Int {
            val pairs = HashSet<Int>()
            for (p in intArrayOf(i - 1, i, j - 1, j)) if (same(p, p + 1)) pairs += p
            return pairs.size
        }
        repeat(REPAIR_PASSES) {
            var improved = false
            for (i in 1 until list.size) {
                if (!same(i - 1, i)) continue
                for (j in list.indices) {
                    if (j == i || j == i - 1 || list[j].src.artist == list[i].src.artist) continue
                    val before = around(i, j)
                    java.util.Collections.swap(list, i, j)
                    if (around(i, j) < before) { improved = true; break }
                    java.util.Collections.swap(list, i, j)
                }
            }
            if (!improved) return
        }
    }

    private const val REPAIR_PASSES = 4

    /** Values replaced by their rank among the known ones, 0..1 (ties share their mean rank); unknown stays null. Any scale in, one scale out. */
    fun rankNormalize(values: List<Double?>): List<Double?> {
        val known = values.withIndex().filter { (_, v) -> v != null && v.isFinite() }.sortedBy { it.value }
        if (known.isEmpty()) return values.map { null }
        val out = arrayOfNulls<Double>(values.size)
        if (known.size == 1) { out[known[0].index] = 0.5; return out.toList() }
        var i = 0
        while (i < known.size) {
            var j = i
            while (j + 1 < known.size && known[j + 1].value == known[i].value) j++
            val rank = (i + j) / 2.0 / (known.size - 1)
            for (k in i..j) out[known[k].index] = rank
            i = j + 1
        }
        return out.toList()
    }

    /** 0..1 by min and max; every value the same (or none finite) gives ½ for all. */
    fun minMaxNormalize(values: List<Double>): List<Double> {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return values.map { 0.5 }
        val lo = finite.min()
        val hi = finite.max()
        if (hi - lo < 1e-12) return values.map { 0.5 }
        return values.map { if (it.isFinite()) (it - lo) / (hi - lo) else 0.0 }
    }

    // --- measures, for tests and logs ---

    /** Mean tempo affinity over the neighbours whose tempos are both known; null when no pair is. */
    fun <T> meanTempoAffinity(list: List<SeqItem<T>>): Double? =
        list.zipWithNext().mapNotNull { (a, b) -> Tempo.affinity(a.bpm, b.bpm) }.takeIf { it.isNotEmpty() }?.average()

    /** The smallest distance between two songs of the same artist (Int.MAX_VALUE when no artist repeats). */
    fun <T> minArtistGap(list: List<SeqItem<T>>): Int {
        val last = HashMap<String, Int>()
        var gap = Int.MAX_VALUE
        list.forEachIndexed { i, s ->
            if (s.artist.isEmpty()) return@forEachIndexed
            last[s.artist]?.let { gap = minOf(gap, i - it) }
            last[s.artist] = i
        }
        return gap
    }
}
