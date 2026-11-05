package com.ikko.aura.ai.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistIntentJsonTest {

    @Test fun `a full answer is read field by field`() {
        val intent = PlaylistIntentJson.parse(
            """{"target_bpm":150,"bpm_tolerance":15,"energy_level":"high","genres_included":["house","disco"],"genres_excluded":["trap"],"target_duration_minutes":60,"mood":"party"}""",
        )
        assertEquals(
            PlaylistIntent(150, 15, EnergyLevel.HIGH, listOf("house", "disco"), listOf("trap"), 60, "party"),
            intent,
        )
    }

    @Test fun `missing keys fall back to no constraint`() {
        assertEquals(PlaylistIntent.EMPTY, PlaylistIntentJson.parse("{}"))
        assertEquals(PlaylistIntent(energy = EnergyLevel.LOW), PlaylistIntentJson.parse("""{"energy_level":"low"}"""))
        assertTrue(PlaylistIntentJson.parse("{}")!!.isEmpty)
    }

    @Test fun `nulls, wrong types and impossible values are dropped, not guessed`() {
        val intent = PlaylistIntentJson.parse(
            """{"target_bpm":"fast","bpm_tolerance":-3,"energy_level":"extreme","genres_included":"jazz","genres_excluded":[1,null,""],"target_duration_minutes":0,"mood":"null"}""",
        )!!
        assertNull(intent.targetBpm)
        assertNull(intent.bpmTolerance)
        assertNull(intent.energy)
        assertEquals("a single genre written as a string is a list of one", listOf("jazz"), intent.genresIncluded)
        assertEquals(emptyList<String>(), intent.genresExcluded)
        assertNull(intent.targetDurationMinutes)
        assertNull(intent.mood)
    }

    @Test fun `out-of-range numbers are refused or capped`() {
        assertNull(PlaylistIntentJson.parse("""{"target_bpm":300}""")!!.targetBpm)
        assertNull(PlaylistIntentJson.parse("""{"target_bpm":12}""")!!.targetBpm)
        assertEquals(128, PlaylistIntentJson.parse("""{"target_bpm":127.6}""")!!.targetBpm)
        assertEquals(128, PlaylistIntentJson.parse("""{"target_bpm":"128"}""")!!.targetBpm)
        assertEquals(PlaylistIntentJson.MAX_TOLERANCE, PlaylistIntentJson.parse("""{"bpm_tolerance":500}""")!!.bpmTolerance)
        assertEquals(PlaylistIntentJson.MAX_MINUTES, PlaylistIntentJson.parse("""{"target_duration_minutes":100000}""")!!.targetDurationMinutes)
    }

    @Test fun `text around the object, a fence or a sentence, is ignored`() {
        val expected = PlaylistIntent(targetBpm = 90, mood = "calm")
        assertEquals(expected, PlaylistIntentJson.parse("""Here you go: {"target_bpm":90,"mood":"calm"} Enjoy!"""))
        assertEquals(expected, PlaylistIntentJson.parse("```json\n{\"target_bpm\": 90, \"mood\": \"Calm \"}\n```"))
        // A brace inside a string does not end the object.
        assertEquals("a}b", PlaylistIntentJson.parse("""{"mood":"a}b"}""")!!.mood)
    }

    @Test fun `no object at all is no answer`() {
        assertNull(PlaylistIntentJson.parse(""))
        assertNull(PlaylistIntentJson.parse(null))
        assertNull(PlaylistIntentJson.parse("I cannot help with that."))
        assertNull(PlaylistIntentJson.parse("""{"target_bpm":120"""))
        assertNull(PlaylistIntentJson.parse("[1,2,3]"))
    }

    @Test fun `genres are trimmed, lower case, unique, and a genre both wanted and refused is refused`() {
        val intent = PlaylistIntentJson.parse(
            """{"genres_included":[" House ","house","Trap","jazz"],"genres_excluded":["trap"]}""",
        )!!
        assertEquals(listOf("house", "jazz"), intent.genresIncluded)
        assertEquals(listOf("trap"), intent.genresExcluded)
        val many = PlaylistIntentJson.parse("""{"genres_included":[${(1..20).joinToString(",") { "\"g$it\"" }}]}""")!!
        assertEquals(PlaylistIntentJson.MAX_GENRES, many.genresIncluded.size)
    }

    @Test fun `writing is compact, complete and ordered`() {
        assertEquals(
            """{"target_bpm":null,"bpm_tolerance":null,"energy_level":null,"genres_included":[],"genres_excluded":[],"target_duration_minutes":null,"mood":null}""",
            PlaylistIntentJson.write(PlaylistIntent.EMPTY),
        )
        val intent = PlaylistIntent(124, 6, EnergyLevel.HIGH, listOf("house"), listOf("trap"), 120, "party")
        assertEquals(intent, PlaylistIntentJson.parse(PlaylistIntentJson.write(intent)))
    }
}
