package com.ikko.aura.playback

import com.ikko.aura.source.PlayableSource
import com.ikko.aura.source.SourceId
import com.ikko.aura.source.SourceTrackRef
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryPolicyTest {

    private val policy = PlaybackRecoveryPolicy(maxAttemptsPerItem = 2, maxConsecutiveFailures = 5)
    private val alt = PlayableSource(SourceTrackRef(SourceId("ytm"), "x"), "uri://ytm/x", null, randomAccess = false)

    private fun decide(
        failure: PlaybackFailure = PlaybackFailure.MISSING,
        attempts: Int = 1,
        consecutive: Int = 0,
        alternatives: List<PlayableSource> = emptyList(),
        hasNext: Boolean = true,
    ) = policy.decide(failure, attempts, consecutive, alternatives, hasNext)

    @Test
    fun `an unknown failure is left alone, as it always was`() {
        assertEquals(RecoveryAction.Stop, decide(failure = PlaybackFailure.UNKNOWN, alternatives = listOf(alt)))
    }

    @Test
    fun `another source is tried first`() {
        assertEquals(RecoveryAction.TryAlternative(alt), decide(alternatives = listOf(alt)))
    }

    @Test
    fun `once the attempts on the item are used up it moves on`() {
        assertEquals(RecoveryAction.SkipToNext, decide(attempts = 2, alternatives = listOf(alt)))
    }

    @Test
    fun `with nothing else to try a missing track is skipped`() {
        assertEquals(RecoveryAction.SkipToNext, decide())
    }

    @Test
    fun `at the end of the queue it stops`() {
        assertEquals(RecoveryAction.Stop, decide(hasNext = false))
    }

    @Test
    fun `a run of failures stops rather than going round a queue of missing files`() {
        assertEquals(RecoveryAction.SkipToNext, decide(consecutive = 4))
        assertEquals(RecoveryAction.Stop, decide(consecutive = 5))
    }

    @Test
    fun `an unreachable or unsupported track is skipped like a missing one`() {
        assertEquals(RecoveryAction.SkipToNext, decide(failure = PlaybackFailure.UNREACHABLE))
        assertEquals(RecoveryAction.SkipToNext, decide(failure = PlaybackFailure.UNSUPPORTED))
    }
}
