package com.ikko.aura.player.beat

/**
 * The beat grid of the music being heard, for the screens. The service points [active] at the detector of the audible
 * player; the UI, in the same process, only reads.
 */
object BeatGrids {
    @Volatile
    var active: BeatDetector? = null

    /** Seconds since the last beat right now; null while the tempo is not known yet (the first seconds of a track) or there is no clear one. */
    fun sinceBeat(): Double? = active?.sinceBeat(android.os.SystemClock.elapsedRealtimeNanos() / 1e9)
}
