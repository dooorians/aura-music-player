package com.ikko.aura.player.smart

/** Where the beats of the music being played fall, in the track's own time: one at [firstBeat] seconds, then every [interval]. */
data class BeatGrid(val firstBeat: Double, val interval: Double)
