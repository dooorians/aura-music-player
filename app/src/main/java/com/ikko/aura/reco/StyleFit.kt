package com.ikko.aura.reco

/**
 * Whether an artist is of a style, judged on their own tags and nothing a source says of one album: the same judge for the rows of Home and for the
 * recommended playlists, so that a pop star is neither in "Rock" on the one nor in a rock-minded "Dans ton style" on the other.
 *
 * An artist is of a family (see [GenreNormalizer.family]) when one of the tags that lead their list is: what comes after is what is left of whatever else
 * they are. Pure, so it is checked without a device.
 */
object StyleFit {
    /** How many of an artist's first tags say what they are. */
    const val LEADING_TAGS = 3

    /** The families of an artist's first [n] tags. */
    fun families(tags: Collection<String>, n: Int = LEADING_TAGS): Set<String> =
        tags.take(n).mapNotNull { GenreNormalizer.family(it) }.toSet()

    /**
     * Whether an artist belongs in something of the families [allowed]: one of their leading tags is of one of them. No restriction when [allowed] is null.
     * An artist with no tags known cannot be placed: [unknownOk] says whether that keeps them in (a playlist drawn on the listener's taste) or out (a row of a
     * style of Home).
     */
    fun belongs(allowed: Set<String>?, tags: Collection<String>, unknownOk: Boolean = false): Boolean {
        if (allowed == null) return true
        if (tags.isEmpty()) return unknownOk
        return families(tags).any { it in allowed }
    }

    /** Whether an artist is plainly of a family in [forbidden]: one of their first two tags is. What nothing is known of is not forbidden. */
    fun forbidden(forbidden: Set<String>, tags: Collection<String>): Boolean = families(tags, 2).any { it in forbidden }
}
