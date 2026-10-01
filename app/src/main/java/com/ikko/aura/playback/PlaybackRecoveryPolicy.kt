package com.ikko.aura.playback

import com.ikko.aura.source.PlayableSource

/** What to do about a track that failed. */
sealed interface RecoveryAction {
    /** Play the same track from another source. */
    data class TryAlternative(val source: PlayableSource) : RecoveryAction

    /** Give up on this track and go to the next. */
    data object SkipToNext : RecoveryAction

    /** Leave the player as it is. */
    data object Stop : RecoveryAction
}

/**
 * The decision, and nothing else: pure, so every branch is tested without a player. The service applies it
 * (see PlaybackRecovery).
 */
class PlaybackRecoveryPolicy(
    private val maxAttemptsPerItem: Int = 2,
    /**
     * Tracks failing one after another before giving up. What stops "repeat all" over a queue of missing files from
     * going round and round forever.
     */
    private val maxConsecutiveFailures: Int = 5,
) {
    fun decide(
        failure: PlaybackFailure,
        attemptsOnItem: Int,
        consecutiveItemFailures: Int,
        alternatives: List<PlayableSource>,
        hasNext: Boolean,
    ): RecoveryAction = when {
        // As before: an audio-output error or an unclassified one is not cured by skipping, and skipping on it would
        // walk the whole queue.
        failure == PlaybackFailure.UNKNOWN -> RecoveryAction.Stop
        alternatives.isNotEmpty() && attemptsOnItem < maxAttemptsPerItem ->
            RecoveryAction.TryAlternative(alternatives.first())
        hasNext && consecutiveItemFailures < maxConsecutiveFailures -> RecoveryAction.SkipToNext
        else -> RecoveryAction.Stop
    }
}
