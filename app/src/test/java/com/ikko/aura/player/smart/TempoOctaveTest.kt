package com.ikko.aura.player.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoOctaveTest {

    @Test
    fun `half and double time are folded onto the outgoing tempo`() {
        assertEquals(126.0, alignTempoOctave(126.0, 63.0), 1e-9)
        assertEquals(126.0, alignTempoOctave(126.0, 252.0), 1e-9)
        assertEquals(128.0, alignTempoOctave(128.0, 512.0), 1e-9)
        assertEquals(124.0, alignTempoOctave(124.0, 31.0), 1e-9)
    }

    @Test
    fun `a tempo already in the octave is left as it is`() {
        assertEquals(120.0, alignTempoOctave(120.0, 120.0), 1e-9)
        assertEquals(150.0, alignTempoOctave(120.0, 150.0), 1e-9)
        assertEquals(90.0, alignTempoOctave(120.0, 90.0), 1e-9)
    }

    @Test
    fun `the result always lands between two thirds and one and a half times the outgoing tempo`() {
        val outgoing = listOf(60.0, 87.5, 120.0, 174.0, 200.0)
        val incoming = (1..400).map { it * 0.73 }
        for (o in outgoing) for (i in incoming) {
            val ratio = alignTempoOctave(o, i) / o
            assertTrue("$o / $i -> $ratio", ratio in 0.67..1.5)
        }
    }

    @Test
    fun `a missing tempo is returned untouched`() {
        assertEquals(0.0, alignTempoOctave(120.0, 0.0), 0.0)
        assertEquals(-3.0, alignTempoOctave(120.0, -3.0), 0.0)
        assertEquals(140.0, alignTempoOctave(0.0, 140.0), 0.0)
    }

    /** Regression: an infinite tempo was halved for ever (Infinity / 2 is Infinity), a loop no cancellation reaches. */
    @Test(timeout = 2_000)
    fun `an infinite or undefined tempo returns at once instead of looping for ever`() {
        assertEquals(Double.POSITIVE_INFINITY, alignTempoOctave(120.0, Double.POSITIVE_INFINITY), 0.0)
        assertTrue(alignTempoOctave(120.0, Double.NaN).isNaN())
        assertEquals(120.0, alignTempoOctave(Double.POSITIVE_INFINITY, 120.0), 0.0)
        assertEquals(120.0, alignTempoOctave(Double.NaN, 120.0), 0.0)
    }

    @Test
    fun `harmonic rules agree with the key distance`() {
        assertTrue(harmonicallyCompatible("C major", "C major"))
        assertTrue(harmonicallyCompatible("C major", "G major")) // a fifth
        assertTrue(harmonicallyCompatible("C major", "D major")) // a second
        assertTrue(!harmonicallyCompatible("C major", "F\u266F major")) // a tritone
        assertEquals(0, keyDistance("A minor", "A minor"))
        assertEquals(1, keyDistance("A minor", "A major")) // same root, other mode
        assertEquals(6, keyDistance("C major", "F\u266F major"))
        assertEquals(null, keyDistance("", "C major"))
        assertEquals(null, keyDistance("H major", "C major"))
        assertTrue(!harmonicallyCompatible("", ""))
    }

    @Test
    fun `key distance is symmetric and never more than seven`() {
        val roots = listOf("C", "C\u266F", "D", "E\u266D", "E", "F", "F\u266F", "G", "A\u266D", "A", "B\u266D", "B")
        val keys = roots.flatMap { listOf("$it major", "$it minor") }
        for (a in keys) for (b in keys) {
            val d = keyDistance(a, b) ?: continue
            assertEquals("$a / $b", d, keyDistance(b, a))
            assertTrue("$a / $b -> $d", d in 0..7)
        }
    }

    /**
     * The native analyser names its keys with these exact spellings (U+266F sharp, U+266D flat). The JNI bridge used to drop
     * their non-ASCII bytes, so "C\u266F minor" reached here as "C minor": the planner must read every one of them as its own root.
     */
    @Test
    fun `every key name the analyser emits is understood, with its sharps and flats`() {
        val analyserRoots = listOf("C", "C\u266F", "D", "E\u266D", "E", "F", "F\u266F", "G", "A\u266D", "A", "B\u266D", "B")
        analyserRoots.forEachIndexed { index, root ->
            assertEquals("$root major", 0, keyDistance("$root major", "$root major"))
            // A semitone away from the previous root: distinct, never folded onto its neighbour.
            if (index > 0) assertEquals("$root vs ${analyserRoots[index - 1]}", 1, keyDistance("$root major", "${analyserRoots[index - 1]} major"))
        }
        // Enharmonic spellings name the same pitch.
        assertEquals(0, keyDistance("C\u266F minor", "D\u266D minor"))
        // What the stripped name was read as: a different key, a semitone off.
        assertEquals(1, keyDistance("C\u266F minor", "C minor"))
    }

    @Test
    fun `a key the analyser was unsure of is no key`() {
        assertEquals("", trustedKey(TrackAnalysis(key = "C major", keyConfidence = 0.1)))
        assertEquals("C major", trustedKey(TrackAnalysis(key = "C major", keyConfidence = 0.9)))
        assertEquals("", trustedKey(TrackAnalysis(key = "", keyConfidence = 0.9)))
    }
}
