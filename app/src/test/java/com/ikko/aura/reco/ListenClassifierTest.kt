package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenClassifierTest {
    private val duration = 200_000L

    @Test fun `playing to the end is a listen`() {
        assertEquals(Outcome.COMPLETED, ListenClassifier.classify(199_000, duration, Transition.AUTO))
        assertEquals(Outcome.COMPLETED, ListenClassifier.classify(180_000, duration, Transition.USER_SKIP))
    }

    @Test fun `leaving within seconds is a clear no`() {
        assertEquals(Outcome.SKIPPED_EARLY, ListenClassifier.classify(4_000, duration, Transition.USER_SKIP))
    }

    @Test fun `leaving before half way is a skip, after it a partial listen`() {
        assertEquals(Outcome.SKIPPED, ListenClassifier.classify(60_000, duration, Transition.USER_SKIP))
        assertEquals(Outcome.PARTIAL, ListenClassifier.classify(130_000, duration, Transition.USER_SKIP))
    }

    @Test fun `starting another queue is an interruption, never a rejection`() {
        assertEquals(Outcome.INTERRUPTED, ListenClassifier.classify(3_000, duration, Transition.QUEUE_REPLACED))
        assertEquals(Outcome.INTERRUPTED, ListenClassifier.classify(40_000, duration, Transition.QUEUE_REPLACED))
        assertEquals(Outcome.PARTIAL, ListenClassifier.classify(120_000, duration, Transition.QUEUE_REPLACED))
    }

    @Test fun `an unannounced change is never counted against a song`() {
        assertEquals(Outcome.INTERRUPTED, ListenClassifier.classify(3_000, duration, Transition.UNKNOWN))
        assertEquals(Outcome.INTERRUPTED, ListenClassifier.classify(60_000, duration, Transition.UNKNOWN))
    }

    @Test fun `an unknown length falls back to what was heard`() {
        assertEquals(Outcome.PARTIAL, ListenClassifier.classify(90_000, 0, Transition.USER_SKIP))
        assertEquals(Outcome.SKIPPED_EARLY, ListenClassifier.classify(3_000, 0, Transition.USER_SKIP))
    }
}
