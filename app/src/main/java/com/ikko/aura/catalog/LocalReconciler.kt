package com.ikko.aura.catalog

enum class Availability { AVAILABLE, MISSING }

/** One file as the MediaStore scan saw it. [fingerprint] is what survives a re-index that hands the file a new id. */
data class ScannedFile(
    val itemId: String,
    val fingerprint: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/** Which MediaStore item currently holds [trackKey]. A binding is marked, never deleted, when its file goes. */
data class LocalBinding(
    val itemId: String,
    val trackKey: String,
    val fingerprint: String,
    val availability: Availability,
    val lastSeenAt: Long,
)

/** What the library shows for a key: enough to list it as unavailable once its file has gone. */
data class CatalogTrack(val key: String, val title: String, val artist: String, val album: String, val durationMs: Long)

class Reconciliation(val bindings: List<LocalBinding>, val tracks: List<CatalogTrack>) {
    val keyByItem: Map<String, String> = bindings.associate { it.itemId to it.trackKey }
}

/**
 * Matches a fresh scan against what the catalogue remembers, so a track keeps its key whatever MediaStore does.
 *
 * - A file still there keeps the key it had.
 * - A new file whose fingerprint is that of exactly one vanished file, and of no other new file, is that file
 *   re-indexed under a new id: it takes the old key, so playlists, favourites and lyrics follow it.
 * - Any other new file takes its own MediaStore id as key, as every track did before the catalogue existed.
 * - A file that is gone stays, marked MISSING.
 * - An empty scan changes nothing: it is a MediaStore hiccup or a revoked permission far more often than an
 *   emptied library, and marking everything missing on it would be wrong the moment it came back.
 */
object LocalReconciler {

    fun reconcile(previous: List<LocalBinding>, scanned: List<ScannedFile>, now: Long): Reconciliation {
        if (scanned.isEmpty()) return Reconciliation(previous, emptyList())

        val scannedIds = scanned.mapTo(HashSet()) { it.itemId }
        val previousByItem = previous.associateBy { it.itemId }
        val vanished = previous.filter { it.itemId !in scannedIds }
        val arrivals = scanned.filter { it.itemId !in previousByItem }

        val vanishedByFingerprint = vanished.groupBy { it.fingerprint }
        val arrivalsByFingerprint = arrivals.groupBy { it.fingerprint }

        val usedKeys = HashSet<String>().apply { previous.forEach { add(it.trackKey) } }
        val rebound = HashMap<String, LocalBinding>() // arrival item id -> the vanished binding it continues
        for ((fingerprint, gone) in vanishedByFingerprint) {
            val arrived = arrivalsByFingerprint[fingerprint] ?: continue
            if (gone.size == 1 && arrived.size == 1) rebound[arrived[0].itemId] = gone[0]
        }
        val consumed = rebound.values.mapTo(HashSet()) { it.itemId }

        val bindings = ArrayList<LocalBinding>(scanned.size + vanished.size)
        val tracks = ArrayList<CatalogTrack>(scanned.size)
        for (file in scanned) {
            val key = previousByItem[file.itemId]?.trackKey
                ?: rebound[file.itemId]?.trackKey
                ?: freshKey(file.itemId, usedKeys)
            usedKeys += key
            bindings += LocalBinding(file.itemId, key, file.fingerprint, Availability.AVAILABLE, now)
            tracks += CatalogTrack(key, file.title, file.artist, file.album, file.durationMs)
        }
        for (gone in vanished) {
            if (gone.itemId in consumed) continue
            bindings += gone.copy(availability = Availability.MISSING)
        }
        return Reconciliation(bindings, tracks)
    }

    /** The MediaStore id itself, unless a re-linked file already holds that key. */
    private fun freshKey(itemId: String, used: Set<String>): String = if (itemId in used) "l_$itemId" else itemId
}
