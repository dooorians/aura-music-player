package com.ikko.aura.ai.playlist

import com.ikko.aura.ai.slm.SlmCompletion
import com.ikko.aura.ai.slm.SlmEngine
import com.ikko.aura.ai.slm.SlmMessage
import com.ikko.aura.ai.slm.SlmRequest
import com.ikko.aura.data.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlaylistAssistantTest {

    private val uri = android.net.TestUri.INSTANCE
    private val prompt = PromptAssets(
        grammar = "root ::= \"{}\"",
        messages = listOf(SlmMessage("system", "s"), SlmMessage("user", "Night Drive"), SlmMessage("assistant", "{}")),
        key = "k",
    )

    private class RecordingEngine(private val answer: (SlmRequest) -> SlmCompletion) : SlmEngine {
        val requests = mutableListOf<SlmRequest>()
        override suspend fun complete(request: SlmRequest): SlmCompletion {
            requests += request
            return answer(request)
        }
    }

    private fun source(): FakeTrackSource {
        val genres = listOf("Deep House", "French Rap", "Jazz", "Trap")
        val tracks = (0 until 40).map { i ->
            Track(id = "t$i", title = "t$i", artist = "a${i % 10}", album = "al${i % 10}", artistId = 0, albumId = (i % 10).toLong(), artworkUri = null, contentUri = uri, durationMs = 200_000)
        }
        val rows = tracks.mapIndexed { i, t ->
            t.id to AiTrackRow(t.id, if (i % 4 == 0) 124.0 else 92.0, genres[i % 4], -6.0, "", "[]", 0.0, false, 0)
        }.toMap()
        return FakeTrackSource(tracks, rows)
    }

    private fun assistant(engine: SlmEngine, modelInstalled: Boolean = true) = AiPlaylistAssistant(
        PlaylistIntentInterpreter(engine, modelPath = { if (modelInstalled) "/model.gguf" else null }, assets = { prompt }, prefixCachePath = { "/cache/$it" }),
        AiPlaylistGenerator(source()),
    )

    private fun ok(json: String) = SlmCompletion(ok = true, text = json)

    @Test fun `an empty request never reaches the model`() = runTest {
        val engine = RecordingEngine { ok("{}") }
        assertEquals(AiPlaylistResult.EmptyRequest, assistant(engine).compose("   "))
        assertTrue(engine.requests.isEmpty())
    }

    @Test fun `the model is asked with the prompt, the request last, under the grammar, two threads`() = runTest {
        val engine = RecordingEngine { ok("""{"target_bpm":null,"bpm_tolerance":null,"energy_level":"high","genres_included":["house"],"genres_excluded":[],"target_duration_minutes":null,"mood":"party"}""") }
        val result = assistant(engine).compose("  Soirée house  ")
        val request = engine.requests.single()
        assertEquals("/model.gguf", request.modelPath)
        assertEquals(prompt.grammar, request.grammar)
        assertEquals(prompt.messages + SlmMessage("user", "Soirée house"), request.messages)
        assertEquals("/cache/k", request.prefixCachePath)
        assertEquals(2, request.threads)
        result as AiPlaylistResult.Ready
        assertEquals(IntentOrigin.MODEL, result.interpretation.origin)
        assertTrue("only house: ${result.tracks.map { it.id }}", result.tracks.all { it.id.removePrefix("t").toInt() % 4 == 0 })
    }

    @Test fun `without a model, the rules read the request and the playlist is still made`() = runTest {
        val engine = RecordingEngine { error("must not be called") }
        val result = assistant(engine, modelInstalled = false).compose("du jazz sans trap")
        result as AiPlaylistResult.Ready
        assertEquals(IntentOrigin.RULES, result.interpretation.origin)
        assertEquals(listOf("jazz"), result.interpretation.intent.genresIncluded)
        assertTrue(result.tracks.all { it.id.removePrefix("t").toInt() % 4 == 2 })
    }

    @Test fun `an engine failure or an unreadable answer falls back to the rules`() = runTest {
        for (answer in listOf(SlmCompletion(ok = false, text = "", error = "the model could not be loaded"), ok("I would suggest some jazz!"))) {
            val result = assistant(RecordingEngine { answer }).compose("du jazz")
            result as AiPlaylistResult.Ready
            assertEquals(IntentOrigin.RULES, result.interpretation.origin)
            assertEquals(answer, result.interpretation.completion)
        }
    }

    @Test fun `the request's numbers override the model's`() = runTest {
        val engine = RecordingEngine { ok("""{"target_bpm":null,"bpm_tolerance":null,"energy_level":null,"genres_included":["house"],"genres_excluded":[],"target_duration_minutes":2,"mood":null}""") }
        val result = assistant(engine).compose("house pendant 2 heures") as AiPlaylistResult.Ready
        assertEquals(120, result.interpretation.intent.targetDurationMinutes)
    }

    @Test fun `a request nothing answers is said so, with what was understood`() = runTest {
        val engine = RecordingEngine { ok("""{"target_bpm":null,"bpm_tolerance":null,"energy_level":null,"genres_included":[],"genres_excluded":["house","rap","jazz","trap"],"target_duration_minutes":null,"mood":null}""") }
        val result = assistant(engine).compose("rien de tout ça")
        result as AiPlaylistResult.NoMatch
        assertEquals(listOf("house", "rap", "jazz", "trap"), result.interpretation.intent.genresExcluded)
    }

    @Test fun `a long request is cut to fit the context`() = runTest {
        val engine = RecordingEngine { ok("{}") }
        assistant(engine).compose("jazz ".repeat(200))
        assertTrue(engine.requests.single().messages.last().content.length <= PlaylistIntentInterpreter.MAX_REQUEST_CHARS)
    }

    @Test fun `the stages are reported in order`() = runTest {
        val stages = mutableListOf<AiStage>()
        assistant(RecordingEngine { ok("{}") }).compose("jazz") { stages += it }
        assertEquals(listOf(AiStage.READING, AiStage.SELECTING), stages)
    }

    @Test fun `the prompt file's sections become messages`() {
        val messages = PromptAssets.parse("### system\nBe brief.\nReally.\n### user\nhi\n### assistant\n{}\n")
        assertEquals(listOf(SlmMessage("system", "Be brief.\nReally."), SlmMessage("user", "hi"), SlmMessage("assistant", "{}")), messages)
        assertNull(PromptAssets.parse("no header").firstOrNull())
    }

    @Test fun `the engine's answer is decoded from the JNI arrays`() {
        val c = SlmCompletion.decode(arrayOf<Any?>("{}".toByteArray(), "".toByteArray(), "1,0,1,21,44,239,454,2050"))
        assertEquals(SlmCompletion(ok = true, text = "{}", prefixCacheHit = true, promptTokens = 21, generatedTokens = 44, loadMs = 239, promptMs = 454, generateMs = 2050), c)
        assertEquals(false, SlmCompletion.decode(null).ok)
        assertEquals(false, SlmCompletion.decode(arrayOf<Any?>(null, null, "garbage")).ok)
    }
}
