package com.ikko.aura.reco

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoTest {

    @Test fun `what no song can have is no tempo`() {
        for (bad in listOf(null, 0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 10.0, 39.9, 250.1, 999.0)) {
            assertNull("$bad", Tempo.sanitize(bad))
        }
        assertEquals(120.0, Tempo.sanitize(120.0)!!, 0.0)
        assertEquals(40.0, Tempo.sanitize(40.0)!!, 0.0)
        assertEquals(250.0, Tempo.sanitize(250.0)!!, 0.0)
    }

    @Test fun `the same tempo is a perfect match, and the match fades with the drift`() {
        assertEquals(1.0, Tempo.affinity(120.0, 120.0)!!, 1e-12)
        val a124 = Tempo.affinity(120.0, 124.0)!!
        val a130 = Tempo.affinity(120.0, 130.0)!!
        val a150 = Tempo.affinity(120.0, 150.0)!!
        assertTrue(a124 > a130 && a130 > a150)
        assertTrue("a DJ's pitch range is a good match: $a124", a124 > 0.75)
        assertTrue("a quarter off is no match: $a150", a150 < 0.05)
    }

    @Test fun `half and double time are the same pulse`() {
        val m = Tempo.match(70.0, 140.0)!!
        assertEquals(Tempo.Relation.OCTAVE, m.relation)
        assertEquals(0.0, m.drift, 1e-12)
        assertEquals(Tempo.OCTAVE_FACTOR, Tempo.affinity(70.0, 140.0)!!, 1e-12)
        assertEquals(Tempo.OCTAVE_FACTOR, Tempo.affinity(174.0, 87.0)!!, 1e-12)
        // A double-time reading of a close tempo beats an unrelated tempo in the same octave.
        assertTrue(Tempo.affinity(128.0, 65.0)!! > Tempo.affinity(128.0, 100.0)!!)
    }

    @Test fun `a shuffle feel is related, weakly`() {
        val m = Tempo.match(120.0, 80.0)!!
        assertEquals(Tempo.Relation.TRIPLET, m.relation)
        val a = Tempo.affinity(120.0, 80.0)!!
        assertTrue(a > 0.4 && a < Tempo.OCTAVE_FACTOR)
    }

    @Test fun `an unknown tempo is no opinion, never a bad match`() {
        assertNull(Tempo.affinity(null, 120.0))
        assertNull(Tempo.affinity(120.0, 0.0))
        assertNull(Tempo.affinity(Double.NaN, 120.0))
        assertEquals(Tempo.NEUTRAL, Tempo.affinityOrNeutral(120.0, null), 0.0)
        assertEquals(Tempo.NEUTRAL, Tempo.affinityOrNeutral(-1.0, 999.0), 0.0)
    }

    @Test fun `the match is symmetric, finite and within 0 to 1, whatever comes in`() {
        val rng = Random(11)
        val garbage = listOf(Double.NaN, 0.0, -3.0, 1e9, Double.POSITIVE_INFINITY)
        repeat(2_000) {
            val a = if (rng.nextInt(10) == 0) garbage.random(rng) else 40.0 + rng.nextDouble() * 210.0
            val b = if (rng.nextInt(10) == 0) garbage.random(rng) else 40.0 + rng.nextDouble() * 210.0
            val ab = Tempo.affinityOrNeutral(a, b)
            val ba = Tempo.affinityOrNeutral(b, a)
            assertTrue(ab.isFinite() && ab in 0.0..1.0)
            assertEquals("$a $b", ab, ba, 1e-9)
        }
    }

    @Test fun `folding puts every octave of a tempo on one value`() {
        assertEquals(Tempo.fold(70.0), Tempo.fold(140.0), 1e-9)
        assertEquals(Tempo.fold(70.0), Tempo.fold(280.0), 1e-9)
        assertTrue(Tempo.fold(Double.NaN).isNaN())
        assertTrue(Tempo.fold(0.0).isNaN())
        val f = Tempo.fold(333.0)
        assertTrue(f >= 80.0 && f < 160.0)
    }

    @Test fun `the session's tempo profile treats double time as the same tempo`() {
        val s = SessionContext().also { it.recordAcoustics(70.0, 0.5); it.recordAcoustics(140.0, 0.5); it.recordAcoustics(71.0, 0.5) }
        val p = s.acousticProfile()!!
        assertTrue("70, 140 and 71 are one tempo", p.bpmSettled)
        // A tempo no song has is not recorded as one.
        val g = SessionContext().also { repeat(4) { _ -> it.recordAcoustics(Double.NaN, Double.NaN) } }
        assertNull(g.acousticProfile())
    }
}
