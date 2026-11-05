package com.ikko.aura.reco

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayEvaluatorTest {
    private val day = 86_400_000L
    private val t0 = 20_000L * day

    /** 12 artists of 8 songs; two genre families. */
    private val library = (0 until 12).flatMap { a ->
        (0 until 8).map { s ->
            ReplayEvaluator.Track("a${a}s$s", "artist$a", "album${a}_${s / 4}", setOf(if (a < 6) "Indie Rock" else "Electro House"))
        }
    }

    /** A listener who plays five artists (a0..a4) most of the time, the others now and then, and skips those at once, six songs a day. */
    private fun listener(seed: Int, n: Int): List<ReplayEvaluator.Event> {
        val rng = Random(seed)
        return (0 until n).map { i ->
            val loved = rng.nextDouble() < 0.85
            val artist = if (loved) rng.nextInt(5) else 5 + rng.nextInt(7)
            val track = "a${artist}s${rng.nextInt(8)}"
            val skip = !loved && rng.nextDouble() < 0.7
            ReplayEvaluator.Event(
                trackId = track, playedMs = if (skip) 6_000L else 200_000L, durationMs = 200_000L, atMs = t0 + i * 4 * 3_600_000L,
                transition = if (skip) Transition.USER_SKIP else Transition.AUTO, manual = !skip && rng.nextDouble() < 0.2,
            )
        }
    }

    @Test fun `a ranker that has learned the listener beats chance by a wide margin`() {
        val report = ReplayEvaluator.run(library, listener(seed = 7, n = 600))
        assertTrue("$report", report.steps > 100)
        assertTrue("$report", report.hitAt10 > report.randomHitAt10 * 1.5)
        assertTrue("mrr ${report.meanReciprocalRank}", report.meanReciprocalRank > 0.1)
        assertTrue(report.hitAt1 <= report.hitAt5 && report.hitAt5 <= report.hitAt10)
    }

    @Test fun `the report is a deterministic function of the history`() {
        val history = listener(seed = 3, n = 250)
        assertEquals(ReplayEvaluator.run(library, history), ReplayEvaluator.run(library, history))
    }

    @Test fun `it does not propose the same few songs forever`() {
        val report = ReplayEvaluator.run(library, listener(seed = 11, n = 500))
        assertTrue("coverage ${report.coverageAt10}", report.coverageAt10 > 0.4)
    }

    @Test fun `the kept measures judge only the plays that were not left at once`() {
        val report = ReplayEvaluator.run(library, listener(seed = 5, n = 500))
        assertTrue("$report", report.keptSteps in 1 until report.steps)
        assertTrue(report.keptHitAt10 in 0.0..1.0 && report.keptMeanReciprocalRank in 0.0..1.0 && report.skippedInTop10 in 0.0..1.0)
        // What the listener keeps is what a ranker that learned them puts up: better than on all plays, skips included.
        assertTrue("$report", report.keptHitAt10 > report.hitAt10)
    }

    @Test fun `nothing to judge before the warm-up and in an empty library`() {
        assertEquals(0, ReplayEvaluator.run(library, listener(1, 10), warmup = 20).steps)
        assertEquals(0.0, ReplayEvaluator.run(emptyList(), listener(1, 50)).hitAt10, 0.0)
    }
}
