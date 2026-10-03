package com.ikko.aura.player.beat

/**
 * The hits of the music being heard, for the screens. The service points [active] at the detector of the audible
 * player; the UI, in the same process, only reads.
 */
object BeatGrids {
    @Volatile
    var active: BeatDetector? = null

    /** Seconds since the last hit of the music reached the ear; null while there is none to follow (a silence, a passage without a hit, nothing playing). */
    fun sinceBeat(): Double? = active?.sinceBeat(android.os.SystemClock.elapsedRealtimeNanos() / 1e9)
}
