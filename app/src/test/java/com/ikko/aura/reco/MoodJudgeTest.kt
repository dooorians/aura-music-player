package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodJudgeTest {
    private fun verdict(mood: Mood, f: SongFeatures?, artist: List<String> = emptyList(), title: String = "Song") = MoodJudge.verdict(mood, f, artist, title)

    // Deezer's numbers for real songs (see the lookups made when this was written).
    private val allNightIve = SongFeatures(bpm = null, gainDb = -6.7, trackTags = emptyList())
    private val gymnopedie = SongFeatures(bpm = 123.8, gainDb = -18.4, trackTags = listOf("piano", "classical"))
    private val weightless = SongFeatures(bpm = 119.8, gainDb = -13.4, trackTags = listOf("ambient", "chill"))
    private val oceanEyes = SongFeatures(bpm = 145.07, gainDb = -8.4, trackTags = listOf("sad", "chill"))

    @Test fun `a loud K-pop song called All Night is not a calm night song`() {
        val tags = listOf("k-pop", "dance-pop", "pop")
        assertNull(verdict(Mood.NIGHT, allNightIve, tags, "All Night"))
        assertNull(verdict(Mood.CHILL, allNightIve, tags, "All Night"))
        assertNotNull(verdict(Mood.ENERGY, allNightIve, tags, "All Night"))
    }

    @Test fun `the name alone never puts a song in an ambience`() {
        assertNull(verdict(Mood.NIGHT, null, emptyList(), "Midnight Nocturne"))
        assertNull(verdict(Mood.CHILL, SongFeatures(gainDb = -16.0), emptyList(), "Lullaby for Sleep"))
        assertNull(verdict(Mood.ENERGY, null, emptyList(), "Party Dance Club"))
    }

    @Test fun `a listed tempo twice the real one does not hide a calm song when the rest says calm`() {
        // Satie at 124, Weightless at 120: the catalogue counted the pulse twice.
        assertNotNull(verdict(Mood.CHILL, gymnopedie, listOf("classical", "piano")))
        assertNotNull(verdict(Mood.NIGHT, gymnopedie, listOf("classical", "piano")))
        assertNotNull(verdict(Mood.CHILL, weightless, listOf("ambient", "downtempo")))
    }

    @Test fun `a fast tempo with nothing calm around it stays fast`() {
        val dance = SongFeatures(bpm = 128.0, gainDb = -7.0, trackTags = listOf("dance"))
        assertNull(verdict(Mood.CHILL, dance, listOf("house", "dance")))
        assertTrue(verdict(Mood.ENERGY, dance, listOf("house", "dance"))!! > 0.5)
    }

    @Test fun `a slow ballad listed at double tempo is calm, a loud one is not Night`() {
        assertNotNull(verdict(Mood.CHILL, oceanEyes, listOf("indie pop", "dream pop")))
        // Too loud for the night, however sad.
        val loudBallad = SongFeatures(bpm = 145.0, gainDb = -6.8, trackTags = listOf("sad"))
        assertNull(verdict(Mood.NIGHT, loudBallad, listOf("pop")))
    }

    @Test fun `night is stricter than chill`() {
        val soft = SongFeatures(bpm = 100.0, gainDb = -12.0, trackTags = listOf("chill"))
        assertNotNull(verdict(Mood.CHILL, soft))
        assertNull(verdict(Mood.NIGHT, soft))
        val nocturnal = SongFeatures(bpm = 62.0, gainDb = -17.0, trackTags = listOf("night", "ambient"))
        assertTrue(verdict(Mood.NIGHT, nocturnal)!! > verdict(Mood.CHILL, soft)!!)
    }

    @Test fun `a metal band's quiet interlude is still not calm by its artist's style alone`() {
        // One signal besides the title is not enough: the artist's style, with nothing measured.
        assertNull(verdict(Mood.CHILL, null, listOf("ambient", "downtempo")))
    }

    @Test fun `the energetic ambience wants the opposite of calm`() {
        val calm = SongFeatures(bpm = 70.0, gainDb = -16.0, trackTags = listOf("chill"))
        assertNull(verdict(Mood.ENERGY, calm))
        assertEquals(null, verdict(Mood.CHILL, SongFeatures(bpm = 140.0, gainDb = -6.0, trackTags = listOf("party", "dance"))))
    }
}
