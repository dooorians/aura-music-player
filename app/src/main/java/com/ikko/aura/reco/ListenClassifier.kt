package com.ikko.aura.reco

/** How the player left a song, as far as the app can tell. */
enum class Transition {
    /** It played to its end and the next one started. */
    AUTO,
    /** The listener moved on (next, or a tap on another song of the queue). */
    USER_SKIP,
    /** Another queue was started: the song was not rejected, it was put aside. */
    QUEUE_REPLACED,
    /** It started again (repeat one). */
    REPEAT,
    /** The player did not say. */
    UNKNOWN,
}

/** Who chose the song: the listener's own tap, a queue they built or started, or the app's automix / radio. */
enum class PlayOrigin { MANUAL, QUEUE, AUTOPLAY;
    companion object {
        fun of(manualStart: Boolean, autoplay: Boolean) = when {
            manualStart -> MANUAL
            autoplay -> AUTOPLAY
            else -> QUEUE
        }
    }
}

/** Tells a rejection from an interruption, and a listen from a glance. */
object ListenClassifier {

    /** A song heard at least this fraction through is a listen, however it was left. */
    const val COMPLETED_FRACTION = 0.85
    const val EARLY_MS = 15_000L
    const val EARLY_FRACTION = 0.08
    const val SKIP_FRACTION = 0.5

    fun classify(playedMs: Long, durationMs: Long, transition: Transition): Outcome {
        val fraction = if (durationMs > 0) (playedMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else -1.0
        return when (transition) {
            Transition.AUTO, Transition.REPEAT -> Outcome.COMPLETED
            Transition.QUEUE_REPLACED ->
                // Started something else: only what was really heard counts, and nothing counts against it.
                if (fraction >= COMPLETED_FRACTION) Outcome.COMPLETED
                else if (fraction >= SKIP_FRACTION || (fraction < 0 && playedMs >= 60_000L)) Outcome.PARTIAL
                else Outcome.INTERRUPTED
            Transition.USER_SKIP, Transition.UNKNOWN -> {
                val skip = transition == Transition.USER_SKIP
                when {
                    fraction >= COMPLETED_FRACTION -> Outcome.COMPLETED
                    fraction < 0 -> if (playedMs >= 60_000L) Outcome.PARTIAL else if (playedMs < EARLY_MS && skip) Outcome.SKIPPED_EARLY else Outcome.INTERRUPTED
                    // Left within the first moments: the clearest "no".
                    playedMs < EARLY_MS || fraction < EARLY_FRACTION -> if (skip) Outcome.SKIPPED_EARLY else Outcome.INTERRUPTED
                    fraction < SKIP_FRACTION -> if (skip) Outcome.SKIPPED else Outcome.INTERRUPTED
                    else -> Outcome.PARTIAL
                }
            }
        }
    }
}
