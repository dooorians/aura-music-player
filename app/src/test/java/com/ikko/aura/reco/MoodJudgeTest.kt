package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodJudgeTest {
    private fun verdict(mood: Mood, f: SongFeatures?, artist: List<String> = emptyList(), title: String = "Song") = MoodJudge.verdict(mood, f, artist, title)

    // Deezer's numbers for real songs (see the lookups made when this was written).
    private val allNightIve = SongFeatures(bpm = null, gainDb = -6.7, trackTags = listOf("k-pop", "dance"))
    private val gymnopedie = SongFeatures(bpm = 123.8, gainDb = -18.4, trackTags = listOf("piano", "classical"))
    private val weightless = SongFeatures(bpm = 119.8, gainDb = -13.4, trackTags = listOf("ambient", "chill"))
    private val oceanEyes = SongFeatures(bpm = 145.07, gainDb = -8.4, trackTags = listOf("sad", "chill"))

    @Test fun `a loud K-pop song called All Night is not a calm night song`() {
        val tags = listOf("k-pop", "dance-pop", "pop")
        assertNull(verdict(Mood.NIGHT, allNightIve, tags, "All Night"))
        assertNull(verdict(Mood.CHILL, allNightIve, tags, "All Night"))
        assertNotNull(verdict(Mood.ENERGY, allNightIve, tags, "All Night"))
    }

    @Test fun `K-pop is not energetic by its genre, a K-pop ballad is calm`() {
        val ballad = SongFeatures(bpm = 72.0, gainDb = -13.0, trackTags = listOf("ballad", "sad"))
        val tags = listOf("k-pop", "korean", "pop")
        assertNotNull(verdict(Mood.CHILL, ballad, tags, "Spring Day"))
        assertNull(verdict(Mood.ENERGY, ballad, tags, "Spring Day"))
        // The genre alone says nothing of the song: no ambience on it.
        assertNull(verdict(Mood.ENERGY, null, tags, "Any"))
        assertNull(verdict(Mood.CHILL, null, tags, "Any"))
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

    // --- what the app heard of the previews, judged against the others ---

    private fun heard(rms: Double, bpm: Double, low: Double, movement: Double, gain: Double = -10.0) =
        SongFeatures(gainDb = gain, probe = AudioProbe(bpm, beatConfidence = 0.8, rmsDb = rms, lowShare = low, variability = movement))

    /** Twelve songs from steady and soft (0) to loud and driving (11). */
    private val pool = (0 until 12).map { heard(rms = -26.0 + it * 1.5, bpm = 70.0 + it * 8, low = 0.1 + it * 0.05, movement = 0.2 + it * 0.1) }
    private val stats = MoodJudge.PoolStats.of(pool)

    @Test fun `the calmest of the songs heard is calm and the most driving is energetic`() {
        assertNotNull(MoodJudge.verdict(Mood.CHILL, pool.first(), emptyList(), "Song", stats))
        assertNull(MoodJudge.verdict(Mood.ENERGY, pool.first(), emptyList(), "Song", stats))
        assertNotNull(MoodJudge.verdict(Mood.ENERGY, pool.last(), emptyList(), "Song", stats))
        assertNull(MoodJudge.verdict(Mood.CHILL, pool.last(), emptyList(), "Song", stats))
    }

    @Test fun `about a third of a pool is chill, fewer are night, and a song is never both energetic and chill`() {
        val chill = pool.count { MoodJudge.verdict(Mood.CHILL, it, emptyList(), "S", stats) != null }
        val night = pool.count { MoodJudge.verdict(Mood.NIGHT, it, emptyList(), "S", stats) != null }
        val energy = pool.count { MoodJudge.verdict(Mood.ENERGY, it, emptyList(), "S", stats) != null }
        assertTrue("chill $chill", chill in 2..7)
        assertTrue("night $night <= chill $chill", night in 1..chill)
        assertTrue("energy $energy", energy in 2..7)
        assertTrue(pool.none { MoodJudge.verdict(Mood.CHILL, it, emptyList(), "S", stats) != null && MoodJudge.verdict(Mood.ENERGY, it, emptyList(), "S", stats) != null })
    }

    @Test fun `a tempo the app heard beats the one a catalogue states, unless it is not sure`() {
        val sure = SongFeatures(bpm = 70.0, gainDb = -16.0, probe = AudioProbe(130.0, 0.9, -14.0, 0.2, 0.3))
        val unsure = SongFeatures(bpm = 70.0, gainDb = -16.0, probe = AudioProbe(130.0, 0.1, -14.0, 0.2, 0.3))
        assertTrue(MoodJudge.read(sure, emptyList(), "S").bpm!! >= 100.0)
        assertEquals(70.0, MoodJudge.read(unsure, emptyList(), "S").bpm!!, 0.0)
    }

    @Test fun `a pool too small to compare with falls back on the fixed scale`() {
        val loud = heard(rms = -8.0, bpm = 128.0, low = 0.5, movement = 1.0, gain = -6.0)
        assertNull(MoodJudge.verdict(Mood.CHILL, loud, emptyList(), "S", MoodJudge.PoolStats.of(listOf(loud))))
        assertNotNull(MoodJudge.verdict(Mood.ENERGY, loud, emptyList(), "S", MoodJudge.PoolStats.of(listOf(loud))))
    }
}
