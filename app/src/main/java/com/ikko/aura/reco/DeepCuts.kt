package com.ikko.aura.reco

/**
 * Without an account, a source's "up next" opens on the same few chart hits whatever the song. The first [SKIP] are set aside and the
 * queue is drawn from the ranks below them (4 to [UP_TO]): the deep cuts, the other tracks of the albums, the B-sides.
 *
 * Pure, so it is checked without a device.
 */
object DeepCuts {
    const val SKIP = 3
    const val UP_TO = 20
    /** With fewer than this many left below the hits, the whole list is kept: a short list is not worth emptying. */
    const val MIN_LEFT = 3

    fun <T> pick(next: List<T>, skip: Int = SKIP, upTo: Int = UP_TO): List<T> {
        val deep = next.drop(skip).take((upTo - skip).coerceAtLeast(0))
        return if (deep.size >= MIN_LEFT) deep else next
    }
}
