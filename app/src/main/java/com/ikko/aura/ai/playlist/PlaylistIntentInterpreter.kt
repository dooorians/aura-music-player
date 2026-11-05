package com.ikko.aura.ai.playlist

import com.ikko.aura.ai.slm.SlmCompletion
import com.ikko.aura.ai.slm.SlmEngine
import com.ikko.aura.ai.slm.SlmMessage
import com.ikko.aura.ai.slm.SlmRequest

/** Who read the request: the model (with the request's explicit facts on top), or the rules alone. */
enum class IntentOrigin { MODEL, RULES }

data class Interpretation(
    val intent: PlaylistIntent,
    val origin: IntentOrigin,
    /** The engine's answer, when it was asked; kept for the logs and the tests. */
    val completion: SlmCompletion? = null,
)

/**
 * The fixed part of the model's input, as the app ships it (assets/slm/): the grammar, and the system prompt with its
 * worked examples. [key] changes whenever either text does, so a cached prefix of an older prompt is never reused.
 */
data class PromptAssets(val grammar: String, val messages: List<SlmMessage>, val key: String) {
    companion object {
        /** "### system", "### user", "### assistant" headers, each followed by its message. */
        fun parse(promptText: String): List<SlmMessage> {
            val out = ArrayList<SlmMessage>()
            var role: String? = null
            val body = StringBuilder()
            fun flush() {
                role?.let { out += SlmMessage(it, body.toString().trim()) }
                body.setLength(0)
            }
            for (line in promptText.lineSequence()) {
                if (line.startsWith("### ")) {
                    flush()
                    role = line.removePrefix("### ").trim()
                } else if (role != null) {
                    body.append(line).append('\n')
                }
            }
            flush()
            return out
        }

        fun of(grammar: String, promptText: String): PromptAssets {
            val key = (promptText.hashCode().toUInt().toString(16) + promptText.length.toString(16))
            return PromptAssets(grammar, parse(promptText), key)
        }
    }
}

/**
 * Request → [PlaylistIntent]. With a model installed, the engine answers under the grammar and [IntentMerge] lays the
 * request's explicit facts over its answer; without one, or when its answer cannot be read, [RequestReader] alone
 * decides. Never throws for a bad answer: the worst case is a more literal reading.
 */
class PlaylistIntentInterpreter(
    private val engine: SlmEngine,
    /** The model's file, or null when it is not installed (or the native engine is missing). */
    private val modelPath: () -> String?,
    private val assets: () -> PromptAssets?,
    /** Where the engine may keep the processed prompt for [PromptAssets.key]. */
    private val prefixCachePath: (String) -> String = { "" },
) {

    suspend fun interpret(request: String): Interpretation {
        val rules = RequestReader.read(request)
        val path = modelPath() ?: return Interpretation(rules, IntentOrigin.RULES)
        val prompt = assets()?.takeIf { it.messages.isNotEmpty() && it.grammar.isNotBlank() } ?: return Interpretation(rules, IntentOrigin.RULES)
        val completion = engine.complete(
            SlmRequest(
                modelPath = path,
                messages = prompt.messages + SlmMessage("user", request.trim().take(MAX_REQUEST_CHARS)),
                grammar = prompt.grammar,
                prefixCachePath = prefixCachePath(prompt.key),
            ),
        )
        val parsed = (if (completion.ok) PlaylistIntentJson.parse(completion.text) else null)
            ?: return Interpretation(rules, IntentOrigin.RULES, completion)
        return Interpretation(IntentMerge.merge(request, parsed, rules), IntentOrigin.MODEL, completion)
    }

    companion object {
        /** A request is a sentence or two: anything longer is cut, so the prompt always fits the context. */
        const val MAX_REQUEST_CHARS = 280
    }
}
