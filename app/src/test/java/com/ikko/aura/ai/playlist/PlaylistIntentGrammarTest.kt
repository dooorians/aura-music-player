package com.ikko.aura.ai.playlist

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grammar the sampler is held to (assets/slm/playlist_intent.gbnf) and the examples the model is shown
 * (assets/slm/playlist_intent_prompt.txt), checked against each other and against the parser: what the model can write is
 * exactly the schema, and everything it can write is read back.
 */
class PlaylistIntentGrammarTest {

    private fun asset(name: String): String =
        listOf(File("src/main/assets/slm/$name"), File("app/src/main/assets/slm/$name")).first { it.isFile }.readText()

    private val grammar = GbnfMatcher(asset("playlist_intent.gbnf"))
    private val examples = PromptAssets.parse(asset("playlist_intent_prompt.txt"))

    @Test fun `the root writes the seven keys of the schema, in the parser's order`() {
        val text = asset("playlist_intent.gbnf")
        val keys = Regex("""\\"([a-z_]+)\\":""").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(PlaylistIntentJson.KEYS, keys)
    }

    @Test fun `every intent the app can write is in the language`() {
        val intents = listOf(
            PlaylistIntent.EMPTY,
            PlaylistIntent(targetBpm = 150, bpmTolerance = 15, energy = EnergyLevel.HIGH, mood = "workout"),
            PlaylistIntent(genresIncluded = listOf("french rap", "r&b", "lo-fi"), genresExcluded = listOf("trap"), targetDurationMinutes = 60),
            PlaylistIntent(targetBpm = 40, bpmTolerance = 39, energy = EnergyLevel.LOW, targetDurationMinutes = 499, mood = "chanson française"),
            PlaylistIntent(targetBpm = 249, energy = EnergyLevel.MEDIUM, genresIncluded = listOf("a", "b", "c", "d", "e", "f")),
        )
        for (intent in intents) {
            val json = PlaylistIntentJson.write(intent)
            assertTrue(json, grammar.matches(json))
            assertEquals(intent, PlaylistIntentJson.parse(json))
        }
    }

    @Test fun `the prompt's examples are answers the sampler could give, and read back to themselves`() {
        val answers = examples.filter { it.role == "assistant" }.map { it.content }
        assertTrue("the prompt has worked examples", answers.size >= 4)
        assertEquals("one answer per example request", examples.count { it.role == "user" }, answers.size)
        assertEquals("system", examples.first().role)
        for (answer in answers) {
            assertTrue(answer, grammar.matches(answer))
            val intent = PlaylistIntentJson.parse(answer)
            assertNotNull(answer, intent)
            assertEquals(answer, PlaylistIntentJson.write(intent!!))
        }
    }

    @Test fun `the inspiration chips are worked examples, so the model answers them as shown`() {
        val requests = examples.filter { it.role == "user" }.map { it.content }
        for (chip in listOf("Session Focus", "Night Drive", "Workout 135+ BPM", "Chill acoustique")) assertTrue(chip, chip in requests)
    }

    @Test fun `anything chatty, reformatted or out of range is outside the language`() {
        val valid = PlaylistIntentJson.write(PlaylistIntent(targetBpm = 120, energy = EnergyLevel.HIGH, genresIncluded = listOf("house")))
        assertTrue(grammar.matches(valid))
        val refused = listOf(
            "Sure! $valid",
            "```json\n$valid\n```",
            "$valid\n",
            valid.replace(":", ": "),
            valid.replace("\"target_bpm\":120,", ""),
            valid.replace("{\"target_bpm\":120,\"bpm_tolerance\":null,", "{\"bpm_tolerance\":null,\"target_bpm\":120,"),
            valid.replace("}", ",\"comment\":\"great\"}"),
            valid.replace("\"high\"", "\"extreme\""),
            valid.replace("120", "300"),
            valid.replace("120", "39"),
            valid.replace("120", "\"120\""),
            valid.replace("120", "120.5"),
            valid.replace("\"house\"", "\"House\""),
            valid.replace("\"house\"", "\"a\\\"b\""),
            valid.replace("\"house\"", "\"\""),
            valid.replace("\"house\"", (1..7).joinToString(",") { "\"g$it\"" }),
            valid.replace("\"house\"", "\"" + "x".repeat(25) + "\""),
            valid.replace("\"bpm_tolerance\":null", "\"bpm_tolerance\":0"),
            valid.replace("\"target_duration_minutes\":null", "\"target_duration_minutes\":500"),
            valid.replace("\"target_duration_minutes\":null", "\"target_duration_minutes\":-5"),
            valid.replace("\"mood\":null", "\"mood\":{}"),
        )
        for (text in refused) assertFalse(text, grammar.matches(text))
    }

    @Test fun `every string of the language is read as a valid intent`() {
        val random = Random(42)
        repeat(500) {
            val text = grammar.sample(random)
            assertTrue(text, grammar.matches(text))
            val intent = PlaylistIntentJson.parse(text)
            assertNotNull(text, intent)
            intent!!
            intent.targetBpm?.let { assertTrue(text, it in PlaylistIntentJson.MIN_BPM..PlaylistIntentJson.MAX_BPM) }
            intent.bpmTolerance?.let { assertTrue(text, it in 1..PlaylistIntentJson.MAX_TOLERANCE) }
            intent.targetDurationMinutes?.let { assertTrue(text, it in 1..PlaylistIntentJson.MAX_MINUTES) }
            assertTrue(text, intent.genresIncluded.size <= 6 && intent.genresExcluded.size <= 6)
            assertTrue(text, intent.genresIncluded.none { it in intent.genresExcluded })
        }
    }
}
