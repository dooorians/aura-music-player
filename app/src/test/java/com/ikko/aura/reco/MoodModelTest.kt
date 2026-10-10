package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodModelTest {
    @Test fun `no cue means no verdict`() {
        val c = candidate("Neutral Title", "Anyone", durationMs = 210_000)
        assertEquals(null, MoodModel.verdict(c, Mood.CHILL))
        assertEquals(null, MoodModel.verdict(c, Mood.NIGHT))
        assertEquals(null, MoodModel.verdict(c, Mood.ENERGY))
        assertEquals(0, MoodModel.fit(c.title, c.album, c.durationMs).evidence)
    }

    @Test fun `words and tags are the cues`() {
        assertTrue(MoodModel.verdict(candidate("Acoustic Sunday", "A"), Mood.CHILL)!! >= MoodModel.THRESHOLD)
        assertTrue(MoodModel.verdict(candidate("Whatever", "A", tags = listOf("instrumental")), Mood.NIGHT)!! >= MoodModel.THRESHOLD)
        assertTrue(MoodModel.verdict(candidate("Workout Anthem", "A"), Mood.ENERGY)!! >= MoodModel.THRESHOLD)
    }

    @Test fun `a cue against the mood lowers it`() {
        val calmThenLoud = candidate("Chill Club Remix", "A")
        val v = MoodModel.verdict(calmThenLoud, Mood.CHILL)
        assertTrue(v == null || v < MoodModel.THRESHOLD)
    }

    @Test fun `a length alone never makes a mood`() {
        val long = candidate("Neutral", "A", durationMs = 480_000)
        assertEquals(null, MoodModel.verdict(long, Mood.NIGHT))
    }
}
