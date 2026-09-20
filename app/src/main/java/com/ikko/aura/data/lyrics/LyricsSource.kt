/*
 * Ported from BitChord (github.com/kushagrasinghx/BitChord), data/lyrics. Their
 * chain has eight providers, three of which key on a YouTube video id and are no
 * use to a local-file player. These are the three that match on title, artist,
 * album and duration alone.
 */
package com.ikko.aura.data.lyrics

/** A place lyrics can come from, in the order they are preferred. */
enum class LyricsSource(val label: String, val wordSynced: Boolean) {
    /** Apple Music TTML via the BetterLyrics backend — per-syllable, key-less. */
    BETTER_LYRICS("BetterLyrics", wordSynced = true),

    /** The YouLy+ backend. Finest timing of the lot, flakiest hosting — hence the mirrors. */
    LYRICS_PLUS("LyricsPlus", wordSynced = true),

    /** LRCLIB: line-synced only, but between it and nothing it is almost always up. */
    LRCLIB("LRCLIB", wordSynced = false),
    ;

    companion object {
        /** Where a track's lyrics came from when they were read off the device, not the network. */
        const val SIDECAR = "sidecar"
        const val CACHE = "cache"
    }
}

/**
 * Titles carry noise that the lyric databases don't match on — "(Official Video)",
 * "(feat. …)", bracketed edition markers — and a local library's tags carry rather a
 * lot of it. Blanking out to nothing is guarded against: a track genuinely named
 * "(Intro)" keeps its name.
 */
internal fun String.cleanedForSearch(): String = this
    .replace(SEARCH_NOISE, " ")
    .substringBefore(" | ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifBlank { this }

private val SEARCH_NOISE = Regex(
    """\((?:from|feat\.?|official|lyrical|video|audio|remix)[^)]*\)|\[[^]]*]|""" +
        """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
    RegexOption.IGNORE_CASE,
)
