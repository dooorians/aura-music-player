package com.ikko.aura.reco

/**
 * What the current listening session remembers, in memory only: the last songs and artists played, and the genres the listener
 * keeps skipping. Gone with the app's process, which is what "the end of the session" is.
 */
class SessionContext {
    private val lock = Any()
    private val recentTracks = ArrayDeque<String>()
    private val recentArtists = ArrayDeque<String>()
    private val quickSkips = ArrayList<Set<String>>()
    private val penalized = HashSet<String>()

    fun recordPlay(trackId: String, artistKey: String) = synchronized(lock) {
        recentTracks.remove(trackId)
        recentTracks.addLast(trackId)
        while (recentTracks.size > RecommendationConfig.SESSION_TRACKS) recentTracks.removeFirst()
        if (artistKey.isNotEmpty() && recentArtists.lastOrNull() != artistKey) recentArtists.addLast(artistKey)
        while (recentArtists.size > RecommendationConfig.SESSION_ARTISTS) recentArtists.removeFirst()
    }

    /**
     * A song of [genres] was left within 20 seconds. When two quick skips of the session share a genre, that genre is set
     * aside until the session ends.
     */
    fun recordQuickSkip(genres: Set<String>) = synchronized(lock) {
        val specific = genres.filterNot { GenreNormalizer.isGeneric(it) }.map { it.lowercase() }.toSet()
        if (specific.isEmpty()) return@synchronized
        for (genre in specific) {
            if (quickSkips.count { genre in it } + 1 >= RecommendationConfig.MOMENTUM_SKIPS) penalized += genre
        }
        quickSkips += specific
    }

    fun recentTrackIds(): Set<String> = synchronized(lock) { recentTracks.toHashSet() }
    fun lastArtists(): Set<String> = synchronized(lock) { recentArtists.toHashSet() }
    fun penalizedGenres(): Set<String> = synchronized(lock) { penalized.toHashSet() }
}
