package com.ikko.aura.viewmodel

/**
 * One stable key per entry of the queue, kept in step with the queue itself.
 *
 * The same song can be in the queue twice, so neither its id nor its position can name an entry: a list keyed by the
 * id crashes on the second copy, and one keyed by the position names a different entry after every move or removal.
 * An entry keeps its key for as long as it is in the queue, whatever happens around it.
 */
class QueueKeys {
    private var next = 0L

    /** [count] keys that no entry has had. */
    fun fresh(count: Int): List<Long> = List(count) { next++ }

    fun inserted(keys: List<Long>, at: Int, count: Int): List<Long> =
        keys.toMutableList().apply { addAll(at.coerceIn(0, size), fresh(count)) }

    fun appended(keys: List<Long>, count: Int): List<Long> = keys + fresh(count)

    fun moved(keys: List<Long>, from: Int, to: Int): List<Long> {
        if (from !in keys.indices) return keys
        return keys.toMutableList().apply { add(to.coerceIn(0, lastIndex), removeAt(from)) }
    }

    fun removed(keys: List<Long>, index: Int): List<Long> =
        keys.toMutableList().apply { if (index in indices) removeAt(index) }
}

/** The key of the entry at [index]; an entry the keys do not cover (they never should not) still gets a unique one. */
fun List<Long>.keyAt(index: Int): Long = getOrNull(index) ?: (-1L - index)
