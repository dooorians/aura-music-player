package com.ikko.aura.reco

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoBeatsTest {
    private val ok = """{"acousticness":0.174,"danceability":0.4004,"energy":0.6899,"instrumentalness":0.0309,"liveness":0.1188,"loudness":-6.0411,"speechiness":0.0566,"tempo":147.7849,"valence":0.2747}"""

    private fun client(answer: RecoBeatsAnswer, calls: MutableList<Int> = ArrayList(), now: () -> Long = { 1_000L }) =
        RecoBeatsClient(post = { audio -> calls += audio.size; answer }, minIntervalMs = 0L, clock = now)

    @Test fun `the documented answer is read`() {
        val f = RecoBeatsClient.parse(ok)!!
        assertEquals(0.6899, f.energy, 1e-9)
        assertEquals(0.174, f.acousticness, 1e-9)
        assertEquals(0.2747, f.valence, 1e-9)
        assertEquals(-6.0411, f.loudness, 1e-9)
        assertEquals(147.7849, f.tempo, 1e-9)
    }

    @Test fun `an answer without a field is no answer`() {
        assertNull(RecoBeatsClient.parse("""{"energy":0.5}"""))
        assertNull(RecoBeatsClient.parse("not json"))
        assertNull(RecoBeatsClient.parse("""{"error":"Unsupported file type","status":4002}"""))
    }

    @Test fun `an audio file is analysed, an empty or too large one is not even sent`() = runBlocking {
        val calls = ArrayList<Int>()
        val c = client(RecoBeatsAnswer(200, ok), calls)
        assertNotNull(c.analyse(ByteArray(480_000)))
        assertNull(c.analyse(ByteArray(0)))
        assertNull(c.analyse(ByteArray(RecoBeatsClient.MAX_BYTES + 1)))
        assertEquals(listOf(480_000), calls)
    }

    @Test fun `a refusal is none`() = runBlocking {
        assertNull(client(RecoBeatsAnswer(400, """{"error":"Unsupported file type"}""")).analyse(ByteArray(10)))
        assertNull(client(RecoBeatsAnswer(500, "")).analyse(ByteArray(10)))
    }

    @Test fun `too many requests puts the service aside for as long as it says`() = runBlocking {
        var now = 1_000L
        val calls = ArrayList<Int>()
        val c = client(RecoBeatsAnswer(429, "", retryAfterSeconds = 60), calls) { now }
        assertNull(c.analyse(ByteArray(10)))
        // Left alone: no request while the pause lasts.
        now += 59_000L
        assertNull(c.analyse(ByteArray(10)))
        assertEquals(1, calls.size)
        // Asked again once it is over.
        now += 2_000L
        c.analyse(ByteArray(10))
        assertEquals(2, calls.size)
    }

    @Test fun `a network failure is none, not a crash`() = runBlocking {
        val c = RecoBeatsClient(post = { throw java.io.IOException("offline") }, minIntervalMs = 0L)
        assertNull(c.analyse(ByteArray(10)))
    }

    // --- the ambiances on what the model heard (the numbers measured on real previews) ---

    private fun heard(energy: Double, acoustic: Double, valence: Double, dance: Double, loud: Double, tempo: Double, gain: Double? = null) =
        SongFeatures(gainDb = gain, feel = ReccoFeatures(energy, acoustic, valence, dance, loud, tempo))

    private val allNight = heard(0.81, 0.06, 0.41, 0.70, -5.6, 123.0, gain = -6.7)
    private val satie = heard(0.00, 0.99, 0.19, 0.45, -26.4, 134.0, gain = -18.4)
    private val oceanEyes = heard(0.24, 0.95, 0.20, 0.50, -10.4, 156.0, gain = -8.4)
    private val weightless = heard(0.17, 0.86, 0.10, 0.25, -24.8, 105.0, gain = -13.4)
    private val skinnyLove = heard(0.17, 0.88, 0.31, 0.60, -14.5, 132.0, gain = -16.2)
    private val apocalypse = heard(0.36, 0.22, 0.20, 0.42, -10.3, 165.0, gain = -8.6)
    private val master = heard(0.93, 0.01, 0.69, 0.47, -8.0, 125.0, gain = -11.4)
    private val vonDutch = heard(0.66, 0.05, 0.33, 0.73, -6.6, 130.0, gain = -7.6)

    private fun v(mood: Mood, f: SongFeatures, tags: List<String> = emptyList(), title: String = "Song") = MoodJudge.verdict(mood, f, tags, title)

    @Test fun `All Night is energetic, not calm and not for the night, whatever its name`() {
        assertNull(v(Mood.NIGHT, allNight, listOf("k-pop", "dance-pop"), "All Night"))
        assertNull(v(Mood.CHILL, allNight, listOf("k-pop"), "All Night"))
        assertNotNull(v(Mood.ENERGY, allNight, listOf("k-pop"), "All Night"))
    }

    @Test fun `quiet acoustic songs are calm, the quietest are for the night`() {
        for (calm in listOf(satie, oceanEyes, weightless, skinnyLove)) assertNotNull(v(Mood.CHILL, calm))
        assertNotNull(v(Mood.NIGHT, satie))
        assertNotNull(v(Mood.NIGHT, weightless))
        for (calm in listOf(satie, oceanEyes, weightless, skinnyLove)) assertNull(v(Mood.ENERGY, calm))
    }

    @Test fun `a dream-pop song mastered loud is still calm`() {
        assertNotNull(v(Mood.CHILL, apocalypse, listOf("dream pop", "indie pop")))
    }

    @Test fun `driven songs are energetic and never chill`() {
        for (loud in listOf(master, vonDutch)) {
            assertNotNull(v(Mood.ENERGY, loud))
            assertNull(v(Mood.CHILL, loud))
            assertNull(v(Mood.NIGHT, loud))
        }
    }

    @Test fun `what the model heard outweighs a tempo an octave off`() {
        // ocean eyes is listed at 156: read alone it is fast, with its energy and acousticness it is calm.
        assertTrue(MoodJudge.read(oceanEyes, emptyList(), "S").calm > 0.3)
    }
}
