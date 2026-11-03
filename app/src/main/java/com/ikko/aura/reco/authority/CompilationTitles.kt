package com.ikko.aura.reco.authority

/**
 * Album titles that are a playlist or a compilation rather than an artist's own record: kept out of every row and not taken as proof
 * that an artist made a song. One list for the whole app; it used to exist three times, each with other entries.
 *
 * Pure, so it can be tested without a device.
 */
object CompilationTitles {
    private val KEYWORDS = listOf(
        // Playlist-albums that sources file under a real artist.
        "bichotas",
        "girlboss",
        "various artists",
        "top tracks",
        "summer hits",
        "party mix",
        "workout hits",
        "compilation",
        "lo-fi beats",
        "lofi beats",
        "tiktok hits",
        "soundtrack from",
        "hits 20",
        "playlist",
        "best of",
        "hits of",
        "top 50",
        "top 100",
        "greatest hits",
        "megamix",
        "mashup",
        "tribute",
        // Anthems of sports events are made for a crowd, not for a taste.
        "official anthem",
        "world cup",
    )

    fun matches(title: String): Boolean {
        val t = title.lowercase().trim()
        return t.isNotEmpty() && KEYWORDS.any { t.contains(it) }
    }
}
