package com.ikko.aura.ai.playlist

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

enum class EnergyLevel(val wire: String) {
    LOW("low"), MEDIUM("medium"), HIGH("high");

    companion object {
        /** The level a word names, in the schema's English or as a listener might write it; null for anything else. */
        fun of(raw: String?): EnergyLevel? = when (raw?.trim()?.lowercase()) {
            "low", "calm", "faible", "basse", "bas", "douce", "doux" -> LOW
            "medium", "mid", "moderate", "moyen", "moyenne", "modérée" -> MEDIUM
            "high", "intense", "élevée", "elevee", "haute", "fort", "forte" -> HIGH
            else -> null
        }
    }
}

/**
 * What a listener asked of a playlist, as the assistant's model reads it (see playlist_intent.gbnf for the exact JSON):
 * every field is optional, and an unknown field is no constraint. Genres are lower case, as written by the model or the
 * listener ("french rap", "house"); [AiPlaylistPlanner] matches them against the library's.
 */
@Immutable
data class PlaylistIntent(
    val targetBpm: Int? = null,
    val bpmTolerance: Int? = null,
    val energy: EnergyLevel? = null,
    val genresIncluded: List<String> = emptyList(),
    val genresExcluded: List<String> = emptyList(),
    val targetDurationMinutes: Int? = null,
    val mood: String? = null,
) {
    val isEmpty: Boolean
        get() = targetBpm == null && energy == null && genresIncluded.isEmpty() && genresExcluded.isEmpty() &&
            targetDurationMinutes == null && mood == null

    companion object {
        val EMPTY = PlaylistIntent()
    }
}

/**
 * The JSON of a [PlaylistIntent], both ways. Reading is defensive: the grammar already holds the model to the schema,
 * but this is the one place a malformed answer could reach the rest of the app, so it accepts what it can and replaces
 * the rest with "no constraint". A missing key, a value of the wrong type or out of range is dropped; text around the
 * object (a fence, a sentence) is ignored; a genre both asked for and excluded is excluded.
 */
object PlaylistIntentJson {

    /** The keys, in the order the grammar writes them. */
    val KEYS = listOf(
        "target_bpm", "bpm_tolerance", "energy_level", "genres_included", "genres_excluded", "target_duration_minutes", "mood",
    )

    const val MIN_BPM = 40
    const val MAX_BPM = 250
    const val MAX_TOLERANCE = 60
    const val MAX_MINUTES = 600
    const val MAX_GENRES = 8
    private const val MAX_WORD = 40

    private val json = Json { isLenient = true }

    /** The intent [text] holds, or null when it holds no JSON object at all. */
    fun parse(text: String?): PlaylistIntent? {
        val body = firstObject(text ?: return null) ?: return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val excluded = genres(root["genres_excluded"])
        val excludedSet = excluded.toSet()
        return PlaylistIntent(
            targetBpm = int(root["target_bpm"])?.takeIf { it in MIN_BPM..MAX_BPM },
            bpmTolerance = int(root["bpm_tolerance"])?.takeIf { it > 0 }?.coerceAtMost(MAX_TOLERANCE),
            energy = EnergyLevel.of(string(root["energy_level"])),
            genresIncluded = genres(root["genres_included"]).filterNot { it in excludedSet },
            genresExcluded = excluded,
            targetDurationMinutes = int(root["target_duration_minutes"])?.takeIf { it > 0 }?.coerceAtMost(MAX_MINUTES),
            mood = string(root["mood"])?.let(::word),
        )
    }

    /** [intent] as the grammar would write it: compact, every key, in order. */
    fun write(intent: PlaylistIntent): String = buildString {
        fun str(s: String) = "\"" + s.replace("\\", "").replace("\"", "") + "\""
        fun list(l: List<String>) = l.joinToString(",", "[", "]") { str(it) }
        append('{')
        append("\"target_bpm\":").append(intent.targetBpm?.toString() ?: "null").append(',')
        append("\"bpm_tolerance\":").append(intent.bpmTolerance?.toString() ?: "null").append(',')
        append("\"energy_level\":").append(intent.energy?.let { str(it.wire) } ?: "null").append(',')
        append("\"genres_included\":").append(list(intent.genresIncluded)).append(',')
        append("\"genres_excluded\":").append(list(intent.genresExcluded)).append(',')
        append("\"target_duration_minutes\":").append(intent.targetDurationMinutes?.toString() ?: "null").append(',')
        append("\"mood\":").append(intent.mood?.let(::str) ?: "null")
        append('}')
    }

    /** The first balanced {...} of [text], outside strings; null when there is none. */
    internal fun firstObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun int(e: JsonElement?): Int? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        val d = p.content.trim().toDoubleOrNull() ?: return null
        return if (d.isFinite() && d in -1e6..1e6) d.roundToInt() else null
    }

    private fun string(e: JsonElement?): String? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull || !p.isString) return null
        return p.content.trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun word(s: String): String? =
        s.trim().lowercase().replace(Regex("\\s+"), " ").take(MAX_WORD).trim().takeIf { it.isNotEmpty() }

    private fun genres(e: JsonElement?): List<String> {
        val raw = when (e) {
            is JsonArray -> e.mapNotNull { string(it) }
            // A single genre written as a string instead of a list of one.
            is JsonPrimitive -> listOfNotNull(string(e))
            else -> emptyList()
        }
        return raw.mapNotNull(::word).distinct().take(MAX_GENRES)
    }
}
