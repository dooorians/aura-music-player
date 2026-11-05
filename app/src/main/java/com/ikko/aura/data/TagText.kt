package com.ikko.aura.data

/**
 * Cleans a tag read from a file or from MediaStore before it reaches the library.
 *
 * Tags written by old or careless taggers come back with NUL terminators ("Title\u0000\u0000"), stray control
 * characters, byte-order marks, lone UTF-16 surrogates or a run of U+FFFD where an encoding was guessed wrong. Kept as
 * they are, they break sorting and grouping (two copies of one album), JSON written to disk, and text drawing.
 * Never throws: whatever comes in, a plain string or null comes out.
 */
object TagText {

    /** The cleaned text, or null when nothing readable is left. */
    fun clean(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        // An ID3 frame ends at its NUL: anything after it is padding or garbage left by the tagger.
        val cut = raw.indexOf('\u0000').let { if (it >= 0) raw.substring(0, it) else raw }
        val out = StringBuilder(cut.length)
        var i = 0
        while (i < cut.length) {
            val c = cut[i]
            when {
                Character.isHighSurrogate(c) && i + 1 < cut.length && Character.isLowSurrogate(cut[i + 1]) -> {
                    out.append(c).append(cut[i + 1])
                    i++
                }
                // A surrogate without its other half cannot be encoded or drawn.
                Character.isSurrogate(c) -> Unit
                c == '﻿' || c == '￾' || c == '￿' -> Unit
                c == '\t' || c == '\n' || c == '\r' -> out.append(' ')
                Character.isISOControl(c) -> Unit
                else -> out.append(c)
            }
            i++
        }
        val collapsed = WHITESPACE.replace(out, " ").trim()
        // Nothing but replacement characters: the encoding was not understood at all, so there is no tag.
        if (collapsed.isEmpty() || collapsed.all { it == '�' || it == ' ' }) return null
        return collapsed
    }

    /** [clean], or [fallback] when nothing readable is left. */
    fun cleanOr(raw: String?, fallback: String): String = clean(raw) ?: fallback

    private val WHITESPACE = Regex("\\s+")
}
