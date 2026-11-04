package com.ikko.aura.reco

/** Every weight and threshold of the smart recommendations, in one place. */
object RecommendationConfig {
    // --- affinity scale (what one event adds to a song's score) ---
    const val MANUAL_START = 3.0
    const val LIKE = 15.0
    const val REPLAY = 2.5
    const val COMPLETED = 1.0
    const val PARTIAL = 0.2
    const val QUICK_SKIP = -1.5
    const val INSTANT_SKIP = -2.5
    /** Share of a song's change that reaches its artist. */
    const val ARTIST_ECHO = 0.3

    const val COMPLETED_FRACTION = 0.8
    const val PARTIAL_FRACTION = 0.3
    const val INSTANT_SKIP_MS = 5_000L
    const val QUICK_SKIP_MS = 20_000L
    /** This many skips in a row, without a complete listen, take a song out. */
    const val MAX_CONSECUTIVE_SKIPS = 5

    // --- session ---
    const val SESSION_TRACKS = 50
    const val SESSION_ARTISTS = 3
    /** Two quick skips (under [QUICK_SKIP_MS]) that share a genre put it aside for the session. */
    const val MOMENTUM_SKIPS = 2

    // --- phase 1: hard filter ---
    const val RECENT_PLAY_MS = 24L * 3_600_000L
    /** With fewer artists than this, the last artists played are not set aside. */
    const val MIN_ARTISTS_FOR_ARTIST_FILTER = 5

    // --- phase 2: score ---
    const val W_TRACK = 0.35
    const val W_ARTIST_ALBUM = 0.15
    const val W_CONTEXT = 30.0
    const val W_HOUR = 10.0
    /** Weekday vs weekend is a weaker habit signal than the hour of day. */
    const val W_WEEK = 5.0
    const val CTX_LASTFM = 0.45
    const val CTX_PLAYLIST = 0.35
    const val CTX_GENRE = 0.20
    const val ARTIST_PART = 0.7
    const val ALBUM_PART = 0.3
    const val LIKE_BONUS = 15.0
    const val REDISCOVERY_BONUS = 8.0
    const val REDISCOVERY_AFTER_MS = 30L * 86_400_000L
    const val REDISCOVERY_COMPLETION = 0.75
    const val NEW_BONUS = 6.0
    const val NEW_MAX_PLAYS = 3
    const val NEW_WITHIN_MS = 14L * 86_400_000L
    const val RECENT_PENALTY_DAYS = 3.0
    const val RECENT_PENALTY_PER_DAY = 5.0
    const val SESSION_GENRE_PENALTY = 25.0
    /** An artist Last.fm lists as close to one the listener blocked: it is much less likely to please. */
    const val BLOCKED_NEIGHBOR_PENALTY = 30.0

    // --- phase 3: distribution ---
    const val SHARE_EXPLOIT = 0.70
    const val SHARE_EXPLORE = 0.20
    /** One genre's ceiling in the queue: once a genre passes this share of what is picked, it is set aside. */
    const val MAX_GENRE_SHARE = 0.40
    /** Below this completion rate, with this many plays and no like, a song leaves the exploit pool. */
    const val LOW_ENGAGEMENT_PLAYS = 3
    const val LOW_ENGAGEMENT_COMPLETION = 0.40
    const val POOL_SIZE = 15
    const val EXPLORE_MAX_PLAYS = 5
    const val EXPLORE_TOP_SHARE = 0.20
    const val GEM_AFTER_MS = 30L * 86_400_000L
    const val GEM_MIN_AFFINITY = 5.0

    // --- phase 4: draw ---
    /** Softmax temperature: low sticks to the best, high flattens the draw. */
    const val TEMPERATURE = 4.0

    // --- queue ---
    const val REFILL_BELOW = 3
    const val REFILL_COUNT = 5
    /** Share of a refill that may come from streaming sources when Automix is on and the network is there. */
    const val STREAM_SHARE = 0.3

    // --- Last.fm graph ---
    const val LASTFM_TTL_MS = 30L * 86_400_000L
}
