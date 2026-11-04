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

    /**
     * Versions nobody asked for: covers, nightcore, slowed + reverb, sped up, karaoke, 8D. A bare "cover" is only taken as one in the
     * forms that say so ("(… Cover)", "- … cover", "cover by", "covers"), so "Cover Me Up" and "Discovery" stay.
     */
    private val BLACKLIST = Regex(
        "\\b(?:nightcore|karaoke|sped[\\s-]*up|8d\\s+audio|slowed(?:\\s*(?:\\+|&|and|n)?\\s*reverb(?:ed)?)?|reverb\\s+version)\\b" +
            "|\\bcover(?:ed)?\\s+by\\b|\\bcover\\s+version\\b|\\bcovers\\b|[(\\[][^)\\]]*\\bcover\\b[^)\\]]*[)\\]]|\\s-\\s[^-]*\\bcover\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun blacklisted(title: String): Set<String> = BLACKLIST.findAll(title).mapTo(HashSet()) { it.value.trim().lowercase() }

    fun isBlacklisted(title: String): Boolean = BLACKLIST.containsMatchIn(title)

    fun matches(title: String): Boolean {
        val t = title.lowercase().trim()
        return t.isNotEmpty() && (KEYWORDS.any { t.contains(it) } || isBlacklisted(t))
    }

    /** Like [matches], except for a blacklisted form [requested] itself carries: asking for "Cover Songs" is not asking for it to be hidden. */
    fun matches(title: String, requested: String): Boolean {
        val t = title.lowercase().trim()
        if (t.isEmpty()) return false
        return KEYWORDS.any { t.contains(it) } || (blacklisted(t) - blacklisted(requested)).isNotEmpty()
    }
}
