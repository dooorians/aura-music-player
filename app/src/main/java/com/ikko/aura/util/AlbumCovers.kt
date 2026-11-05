package com.ikko.aura.util

/**
 * Which file's picture is the cover of a device album: one choice, made once per scan, the same wherever the cover is
 * drawn.
 *
 * Covers are cached per album, but each row of a list asks for its own track's picture, so the first one decoded
 * decided the album's cover for everyone: on an album whose tracks carry different pictures (singles on an album, a
 * downloaded track with another edition's sleeve) the cover then depended on what had been on screen before. Now the
 * cache decodes an album's cover from the first of [candidates] that has a picture, whoever asks and from whichever
 * track.
 *
 * The order is the album's own: tracks with a track number first, by number, then by their MediaStore id, so the
 * cover is the picture of the album's opening track, and a track with none (or one whose file carries no picture)
 * lets the next one stand in.
 */
object AlbumCovers {
    class Entry(val albumId: Long, val trackNumber: Int, val mediaId: Long, val uri: String)

    @Volatile
    private var byAlbum: Map<String, List<String>> = emptyMap()

    /** The file whose picture won the vote for an album, kept so the album's other sizes do not sample again. */
    private val chosen = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Replaces what was known: called after each successful library scan. */
    fun publish(entries: List<Entry>) {
        byAlbum = order(entries)
        chosen.clear()
    }

    fun chosenFor(cacheKey: String): String? = chosen[cacheKey]

    fun choose(cacheKey: String, file: String) {
        chosen[cacheKey] = file
    }

    /** The files whose picture may be this album's cover, best first; null for what is not a device album. */
    fun candidates(cacheKey: String): List<String>? = byAlbum[cacheKey]

    /**
     * Which of the sampled files carries the album's own cover: the picture most of them share, the earliest of them
     * (in the album's order) when there is a tie. [looks] are the files in that order with a 64-bit look of their
     * picture, null for a file that had none. Looks within [SAME_PICTURE_BITS] bits of each other are one picture (the
     * same artwork at another size or compression). Null when no file had a picture.
     *
     * An album's opening track is often a single with a sleeve of its own; what most of the album carries is the album's.
     */
    fun vote(looks: List<Pair<String, Long?>>): String? {
        val clusters = ArrayList<Pair<Long, MutableList<String>>>()
        for ((file, look) in looks) {
            if (look == null) continue
            val cluster = clusters.firstOrNull { java.lang.Long.bitCount(it.first xor look) <= SAME_PICTURE_BITS }
            if (cluster != null) cluster.second += file else clusters += look to mutableListOf(file)
        }
        // maxByOrNull keeps the first of equals: clusters were opened in the album's order.
        return clusters.maxByOrNull { it.second.size }?.second?.first()
    }

    private const val SAME_PICTURE_BITS = 4

    internal fun order(entries: List<Entry>): Map<String, List<String>> =
        entries.groupBy { it.albumId.toString() }.mapValues { (_, tracks) ->
            tracks
                .sortedWith(
                    compareBy<Entry>({ if (it.trackNumber > 0) 0 else 1 }, { it.trackNumber }, { it.mediaId }, { it.uri }),
                )
                .map { it.uri }
                .distinct()
        }
}
