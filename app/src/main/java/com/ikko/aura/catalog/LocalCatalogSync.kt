package com.ikko.aura.catalog

/** The rows a scan has to write: nothing at all when the library has not changed since the last one. */
class CatalogSyncPlan(
    /** Item ids of the source whose binding is gone (a file re-linked under a new id). */
    val removedItemIds: List<String>,
    /** Bindings that are new or changed, written with REPLACE. */
    val bindings: List<TrackBindingEntity>,
    /** Catalogue rows that are new or changed, written with REPLACE. */
    val tracks: List<CatalogTrackEntity>,
) {
    val isEmpty: Boolean get() = removedItemIds.isEmpty() && bindings.isEmpty() && tracks.isEmpty()
}

/**
 * Turns "what the catalogue should hold for the local source" into the few writes that get it there.
 *
 * The scan used to clear every local binding and insert them all again, and replace every catalogue row, at each
 * launch: three writes per file (and their index updates) for a library that had, most of the time, not changed at
 * all, and an invalidation that re-ran every query observing the catalogue. The end state is the same, apart from the
 * last-seen date of an unchanged file, which is refreshed once a day instead of at every scan: no query reads it for a
 * local file more finely than that. Pure, so it is checked without a database.
 */
object LocalCatalogSync {

    const val LAST_SEEN_REFRESH_MS = 24L * 60 * 60 * 1000

    fun plan(
        previousBindings: List<TrackBindingEntity>,
        nextBindings: List<TrackBindingEntity>,
        previousTracks: List<CatalogTrackEntity>,
        nextTracks: List<CatalogTrackEntity>,
        now: Long,
        refreshAfterMs: Long = LAST_SEEN_REFRESH_MS,
    ): CatalogSyncPlan {
        val before = previousBindings.associateBy { it.itemId }
        val nextIds = nextBindings.mapTo(HashSet(nextBindings.size)) { it.itemId }
        val removed = previousBindings.filter { it.itemId !in nextIds }.map { it.itemId }
        val bindings = nextBindings.filter { next ->
            val old = before[next.itemId] ?: return@filter true
            old.trackKey != next.trackKey ||
                old.fingerprint != next.fingerprint ||
                old.availability != next.availability ||
                // Only a file seen now moves its date forward; a missing one keeps the date it was last seen.
                (next.lastSeenAt > old.lastSeenAt && now - old.lastSeenAt >= refreshAfterMs)
        }
        val kept = previousTracks.associateBy { it.key }
        val tracks = nextTracks.filter { kept[it.key] != it }
        return CatalogSyncPlan(removed, bindings, tracks)
    }
}
