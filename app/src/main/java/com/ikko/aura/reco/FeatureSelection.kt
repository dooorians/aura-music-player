package com.ikko.aura.reco

/**
 * Which songs are measured (their previews listened to) when only so many can be: the closest to the listener, but from as many artists as possible. Taken by
 * closeness alone, the 160 are the hits of the five artists the listener plays most, and an ambiance finds songs of five artists, two each at most.
 *
 * Pure, so it is checked without a device.
 */
object FeatureSelection {
    const val PER_ARTIST = 3

    /** The first song of every artist (the artists closest to the listener first), then the second of each, and so on up to [perArtist], [limit] in all. */
    fun spread(songs: List<Candidate>, limit: Int, perArtist: Int = PER_ARTIST, tie: (Candidate) -> Double): List<Candidate> {
        val byArtist = songs.groupBy { it.credits.firstOrNull().orEmpty() }
            .mapValues { (_, list) -> list.sortedByDescending(tie) }
        val artists = byArtist.entries.sortedByDescending { (_, list) -> tie(list.first()) }
        val out = ArrayList<Candidate>()
        for (round in 0 until perArtist) {
            for ((_, list) in artists) {
                list.getOrNull(round)?.let { out += it }
                if (out.size >= limit) return out
            }
        }
        return out
    }
}
