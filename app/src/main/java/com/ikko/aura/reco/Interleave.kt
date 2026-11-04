package com.ikko.aura.reco

/** Spreads a few songs evenly through a ranked list, keeping the order of each: the ranker's best stays next, the others come at intervals. */
object Interleave {
    fun <T> spread(main: List<T>, extra: List<T>): List<T> {
        if (extra.isEmpty()) return main
        if (main.isEmpty()) return extra
        val total = main.size + extra.size
        val slots = extra.indices.map { k -> ((k + 0.5) * total / extra.size).toInt().coerceIn(0, total - 1) }.toSet()
        val m = main.iterator()
        val x = extra.iterator()
        return List(total) { i -> if (i in slots && x.hasNext()) x.next() else if (m.hasNext()) m.next() else x.next() }
    }
}
