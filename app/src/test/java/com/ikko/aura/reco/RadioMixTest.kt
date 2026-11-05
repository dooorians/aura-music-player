package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioMixTest {
    @Test fun `the artist's own songs lead, one close song after every three of theirs`() {
        val out = RadioMix.mix(listOf("a1", "a2", "a3", "a4", "a5", "a6", "a7"), listOf("x1", "x2", "x3"))
        assertEquals(listOf("a1", "a2", "a3", "x1", "a4", "a5", "a6", "x2", "a7", "x3"), out)
    }

    @Test fun `with nothing close the radio is theirs alone, and with nothing of theirs it is what is close`() {
        assertEquals(listOf("a1", "a2"), RadioMix.mix(listOf("a1", "a2"), emptyList()))
        assertEquals(listOf("x1", "x2"), RadioMix.mix(emptyList(), listOf("x1", "x2")))
    }

    @Test fun `no song is lost`() {
        val own = (1..11).map { "a$it" }
        val others = (1..2).map { "x$it" }
        assertEquals((own + others).toSet(), RadioMix.mix(own, others).toSet())
        assertEquals(13, RadioMix.mix(own, others).size)
    }
}
