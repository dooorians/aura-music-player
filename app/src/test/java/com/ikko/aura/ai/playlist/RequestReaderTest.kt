package com.ikko.aura.ai.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestReaderTest {

    private fun read(s: String) = RequestReader.read(s)

    @Test fun `an empty or blank request is no constraint`() {
        assertEquals(PlaylistIntent.EMPTY, read(""))
        assertEquals(PlaylistIntent.EMPTY, read("   \n "))
    }

    @Test fun `the four inspirations read as the prompt's examples say`() {
        read("Workout 135+ BPM").let {
            assertEquals(150, it.targetBpm)
            assertEquals(15, it.bpmTolerance)
            assertEquals(EnergyLevel.HIGH, it.energy)
            assertEquals("workout", it.mood)
        }
        read("Session Focus").let {
            assertEquals(EnergyLevel.LOW, it.energy)
            assertEquals("focus", it.mood)
            assertNull(it.targetBpm)
        }
        read("Night Drive").let {
            assertEquals(EnergyLevel.MEDIUM, it.energy)
            assertEquals("night drive", it.mood)
        }
        read("Chill acoustique").let {
            assertEquals(EnergyLevel.LOW, it.energy)
            assertEquals(listOf("acoustic"), it.genresIncluded)
            assertEquals("chill", it.mood)
        }
    }

    @Test fun `tempos, exact, around, a range, a floor, a ceiling`() {
        assertEquals(128 to null, read("de la house à 128 bpm").let { it.targetBpm to it.bpmTolerance })
        assertEquals(90 to null, read("autour de 90 BPM").let { it.targetBpm to it.bpmTolerance })
        assertEquals(124 to 4, read("entre 120 et 128 bpm").let { it.targetBpm to it.bpmTolerance })
        assertEquals(124 to 4, read("120-128 bpm").let { it.targetBpm to it.bpmTolerance })
        assertEquals(155 to 15, read("plus de 140 bpm").let { it.targetBpm to it.bpmTolerance })
        assertEquals(85 to 15, read("under 100 bpm").let { it.targetBpm to it.bpmTolerance })
        assertNull("not a tempo a song can have", read("900 bpm").targetBpm)
        assertNull("a number that is not a tempo", read("les années 90").targetBpm)
    }

    @Test fun `lengths in hours, minutes and words`() {
        assertEquals(60, read("1h de rap").targetDurationMinutes)
        assertEquals(90, read("1h30 de jazz").targetDurationMinutes)
        assertEquals(120, read("pendant 2 heures").targetDurationMinutes)
        assertEquals(90, read("1 heure et demie").targetDurationMinutes)
        assertEquals(45, read("45 min de calme").targetDurationMinutes)
        assertEquals(30, read("une demi-heure de sport").targetDurationMinutes)
        assertEquals(60, read("an hour of jazz").targetDurationMinutes)
        assertNull("135+ BPM is a tempo, not a length", read("Workout 135+ BPM").targetDurationMinutes)
    }

    @Test fun `genres refused after a negation, asked for otherwise`() {
        read("musique douce pour lire, sans rap ni metal").let {
            assertEquals(listOf("hip-hop", "metal"), it.genresExcluded)
            assertEquals(emptyList<String>(), it.genresIncluded)
            assertEquals(EnergyLevel.LOW, it.energy)
        }
        read("du rap français mais pas de trap").let {
            assertEquals(listOf("french rap"), it.genresIncluded)
            assertEquals(listOf("trap"), it.genresExcluded)
        }
        read("house and disco, no techno").let {
            assertEquals(listOf("house", "disco"), it.genresIncluded)
            assertEquals(listOf("techno"), it.genresExcluded)
        }
        assertEquals("k-pop is one genre, not pop", listOf("k-pop"), read("de la k-pop").genresIncluded)
        assertEquals("trap is not rap", listOf("trap"), read("trap").genresIncluded)
    }

    @Test fun `what the request gives ground for`() {
        assertTrue(RequestReader.impliesTempo("Workout"))
        assertTrue(RequestReader.impliesTempo("vers 100"))
        assertFalse(RequestReader.impliesTempo("Night Drive"))
        assertTrue(RequestReader.impliesDuration("une longue session"))
        assertFalse(RequestReader.impliesDuration("Night Drive"))
    }

    @Test fun `the request's own facts win over the model's, its judgement wins elsewhere`() {
        // What the 0.5B model really answered for this request on the host harness: 2 minutes for "2 heures".
        val request = "Soirée house et disco qui bouge pendant 2 heures"
        val model = PlaylistIntent(energy = EnergyLevel.MEDIUM, genresIncluded = listOf("house", "disco"), targetDurationMinutes = 2, mood = "soul")
        val merged = IntentMerge.merge(request, model)
        assertEquals(120, merged.targetDurationMinutes)
        assertEquals("the model's reading of the intensity is kept", EnergyLevel.MEDIUM, merged.energy)
        assertEquals(listOf("house", "disco"), merged.genresIncluded)
        assertEquals("soul", merged.mood)
    }

    @Test fun `a number the request gives no ground for is dropped`() {
        val merged = IntentMerge.merge("Night Drive", PlaylistIntent(targetBpm = 100, bpmTolerance = 10, targetDurationMinutes = 30, mood = "night drive"))
        assertNull(merged.targetBpm)
        assertNull(merged.bpmTolerance)
        assertNull(merged.targetDurationMinutes)
        assertEquals("night drive", merged.mood)
    }

    @Test fun `refusals add up, and a refused genre is never asked for`() {
        val merged = IntentMerge.merge(
            "du rap sans trap ni drill",
            PlaylistIntent(genresIncluded = listOf("hip-hop", "drill"), genresExcluded = listOf("country")),
        )
        assertEquals(listOf("trap", "drill", "country"), merged.genresExcluded)
        assertEquals(listOf("hip-hop"), merged.genresIncluded)
    }

    @Test fun `without the model, the rules are the whole reading`() {
        assertEquals(read("Workout 135+ BPM"), IntentMerge.merge("Workout 135+ BPM", null))
    }
}
