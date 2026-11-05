package com.ikko.aura.core.audio.dsp

import java.util.Locale
import kotlinx.serialization.Serializable

/** One filter of a saved profile, as it is written to disk. */
@Serializable
data class StoredFilter(val type: FilterType, val frequencyHz: Double, val q: Double, val gainDb: Double) {
    fun toSpec() = FilterSpec(type, frequencyHz, q, gainDb)

    companion object {
        fun of(spec: FilterSpec) = StoredFilter(spec.type, spec.frequencyHz, spec.q, spec.gainDb)
    }
}

/**
 * A named curve: either the ten graphic bands, or — imported from AutoEq — a parametric list, which then replaces
 * the bands. [builtIn] profiles ship with the app and cannot be deleted or overwritten.
 */
@Serializable
data class EqProfile(
    val id: String,
    val name: String,
    val bandGainsDb: List<Float> = FLAT_BANDS,
    val parametric: List<StoredFilter>? = null,
    val preampDb: Float = 0f,
    val builtIn: Boolean = false,
) {
    val isParametric: Boolean get() = !parametric.isNullOrEmpty()
}

val FLAT_BANDS: List<Float> = List(IsoBands.COUNT) { 0f }

/** The presets the app ships, over 31 63 125 250 500 1k 2k 4k 8k 16k. */
object BuiltInProfiles {
    const val FLAT_ID = "builtin:flat"

    val all: List<EqProfile> = listOf(
        EqProfile(FLAT_ID, "Neutre", FLAT_BANDS, builtIn = true),
        EqProfile("builtin:bass", "Basses renforcées", listOf(6f, 5.5f, 4.5f, 3f, 1.5f, 0f, 0f, 0f, 0f, 0f), builtIn = true),
        EqProfile("builtin:vocal", "Voix", listOf(-2f, -2f, -1f, 0f, 2f, 3.5f, 3.5f, 2.5f, 1f, 0f), builtIn = true),
        EqProfile("builtin:treble", "Aigus renforcés", listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 2f, 4f, 5.5f, 6.5f), builtIn = true),
        EqProfile("builtin:electronic", "Électronique", listOf(5f, 4.5f, 2f, 0f, -1.5f, 0f, 1f, 2f, 3.5f, 4.5f), builtIn = true),
        EqProfile("builtin:rock", "Rock", listOf(4.5f, 3.5f, 2f, -0.5f, -1.5f, -0.5f, 1.5f, 3f, 4f, 4.5f), builtIn = true),
    )

    fun byId(id: String?): EqProfile? = all.firstOrNull { it.id == id }
}

/**
 * Reads AutoEq's "ParametricEQ.txt" (the Equalizer APO format its headphone profiles are published in):
 *
 *     Preamp: -6.2 dB
 *     Filter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70
 *     Filter 2: ON PK Fc 2512 Hz Gain -2.1 dB Q 1.41
 *
 * Peaking (PK, PEQ), low shelf (LSC, LS) and high shelf (HSC, HS) are understood; lines switched OFF, and filter
 * types this engine does not have, are skipped and counted in [Result.skipped].
 */
object AutoEqParser {

    data class Result(val preampDb: Double, val filters: List<FilterSpec>, val skipped: Int)

    private val preampLine = Regex("""^\s*preamp\s*:\s*([-+]?[\d.,]+)\s*db""", RegexOption.IGNORE_CASE)
    private val filterLine = Regex(
        """^\s*filter\s*\d*\s*:\s*(on|off)\s+([a-z]+)\s+fc\s+([\d.,]+)\s*hz(?:\s+gain\s+([-+]?[\d.,]+)\s*db)?(?:\s+q\s+([\d.,]+))?""",
        RegexOption.IGNORE_CASE,
    )

    /** Null when the text holds no filter at all — it is not an AutoEq profile. */
    fun parse(text: String): Result? {
        var preamp = 0.0
        val filters = ArrayList<FilterSpec>()
        var skipped = 0
        for (line in text.lineSequence()) {
            preampLine.find(line)?.let { m -> m.groupValues[1].number()?.let { preamp = it } }
            val m = filterLine.find(line) ?: continue
            val on = m.groupValues[1].equals("on", ignoreCase = true)
            val type = when (m.groupValues[2].uppercase(Locale.ROOT)) {
                "PK", "PEQ" -> FilterType.PEAKING
                "LSC", "LS", "LSQ" -> FilterType.LOW_SHELF
                "HSC", "HS", "HSQ" -> FilterType.HIGH_SHELF
                else -> null
            }
            val fc = m.groupValues[3].number()
            val gain = m.groupValues[4].number() ?: 0.0
            val q = m.groupValues[5].number() ?: if (type == FilterType.PEAKING) IsoBands.Q else SHELF_Q
            if (!on || type == null || fc == null || fc <= 0.0 || q <= 0.0) {
                skipped++
                continue
            }
            if (filters.size == BiquadCascade.MAX_FILTERS) {
                skipped++
                continue
            }
            filters += FilterSpec(type, fc, q, gain.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB))
        }
        if (filters.isEmpty()) return null
        return Result(preamp.coerceIn(-MAX_GAIN_DB * 2, MAX_GAIN_DB), filters, skipped)
    }

    /** Writes [filters] back out in the same format, so an exported profile reads back as it was. */
    fun format(preampDb: Double, filters: List<FilterSpec>): String = buildString {
        append(String.format(Locale.US, "Preamp: %.1f dB\n", preampDb))
        filters.forEachIndexed { i, f ->
            val type = when (f.type) {
                FilterType.PEAKING -> "PK"
                FilterType.LOW_SHELF -> "LSC"
                FilterType.HIGH_SHELF -> "HSC"
            }
            append(String.format(Locale.US, "Filter %d: ON %s Fc %.0f Hz Gain %.1f dB Q %.2f\n", i + 1, type, f.frequencyHz, f.gainDb, f.q))
        }
    }

    private fun String.number(): Double? = replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private const val SHELF_Q = 0.7071067811865476
    private const val MAX_GAIN_DB = 24.0
}
