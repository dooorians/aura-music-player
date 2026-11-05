package com.ikko.aura.reco

import kotlin.math.abs

/**
 * Maximal Marginal Relevance (Carbonell & Goldstein, 1998): picks, one at a time, the item that maximises
 *
 *     λ · relevance(i) − (1 − λ) · max_{j chosen} similarity(i, j)
 *
 * so the second song by an artist, the fifth song from one seed, the third song of one style have to be clearly better than a song from elsewhere
 * to come in. Relevance is brought to 0..1 by [scaled], which keeps the proportions of the scores (a song at 0.6 of the best stays at 0.6, it is
 * not called 0 for being the lowest), so λ means the same thing whatever the scale.
 *
 * The maximum similarity of every item to the chosen set is kept up to date incrementally: each step is one pass to find the best item and one to fold
 * the new pick into the maxima, so k picks from n items cost O(k · n) similarity calls, never O(n²) per step.
 */
object Diversifier {

    /**
     * Up to [k] items of [items] in MMR order. [admit] may refuse a pick (a cap per artist, per cluster): a refused item is dropped and the next best is
     * taken; it is called once per item at most and only for items that would be picked. [cancel] is called once per step (a coroutine's ensureActive).
     */
    fun <T> select(
        items: List<T>,
        k: Int,
        lambda: Double,
        relevance: (T) -> Double,
        similarity: (T, T) -> Double,
        admit: (T) -> Boolean = { true },
        cancel: () -> Unit = {},
    ): List<T> {
        if (items.isEmpty() || k <= 0) return emptyList()
        val l = if (lambda.isFinite()) lambda.coerceIn(0.0, 1.0) else 1.0
        val rel = scaled(items.map(relevance))
        val maxSim = DoubleArray(items.size)
        val alive = BooleanArray(items.size) { true }
        var left = items.size
        val out = ArrayList<T>(minOf(k, items.size))
        while (out.size < k && left > 0) {
            cancel()
            var best = -1
            var bestValue = Double.NEGATIVE_INFINITY
            for (i in items.indices) {
                if (!alive[i]) continue
                val v = l * rel[i] - (1 - l) * maxSim[i]
                // Ties keep the input order: a caller that sorted by score keeps its order among equals.
                if (v > bestValue) { bestValue = v; best = i }
            }
            if (best < 0) break
            alive[best] = false
            left--
            val pick = items[best]
            if (!admit(pick)) continue
            out += pick
            for (i in items.indices) {
                if (!alive[i]) continue
                val s = similarity(pick, items[i])
                if (s.isFinite() && s > maxSim[i]) maxSim[i] = s.coerceAtMost(1.0)
            }
        }
        return out
    }

    /**
     * Scores to 0..1, the best at 1. For positive scores this is score / best (proportions kept); when the scores straddle zero, or are all
     * equal, it falls back to min-max. Anything not finite counts as the lowest.
     */
    fun scaled(values: List<Double>): List<Double> {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return values.map { 1.0 }
        val hi = finite.max()
        val lo = finite.min()
        val range = hi - lo
        if (range < 1e-12) return values.map { if (it.isFinite()) 1.0 else 0.0 }
        // The share of the scale the scores span: small when they are all close to the best, 1 when they straddle zero.
        val span = (range / maxOf(abs(hi), abs(lo), 1e-12)).coerceIn(0.0, 1.0)
        return values.map { v -> if (!v.isFinite()) 0.0 else (1.0 - span) + span * (v - lo) / range }
    }
}
