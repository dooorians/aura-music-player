package com.ikko.aura.core.audio.dsp

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.util.Locale

/**
 * Finds a track's loudness tag wherever its container keeps it: Vorbis comments (FLAC, Ogg, Opus), ID3 TXXX frames
 * (MP3) and iTunes free-form atoms (M4A). The track gain wins over the album gain — tracks here are played in a
 * shuffled, mixed queue, where the album's balance has no meaning.
 */
@UnstableApi
object ReplayGainReader {

    fun read(metadata: Metadata?): TrackGain? {
        if (metadata == null || metadata.length() == 0) return null
        val tags = ArrayList<Pair<String, String>>(4)
        for (i in 0 until metadata.length()) {
            when (val entry = metadata.get(i)) {
                is VorbisComment -> tags += entry.key to entry.value
                is TextInformationFrame -> entry.description?.let { d -> entry.values.firstOrNull()?.let { tags += d to it } }
                is InternalFrame -> tags += entry.description to entry.text
            }
        }
        return parse(tags)
    }

    /** The same, from plain key/value pairs: keys are matched without regard to case. */
    fun parse(tags: List<Pair<String, String>>): TrackGain? {
        fun find(key: String) = tags.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second

        find("REPLAYGAIN_TRACK_GAIN")?.let(::parseDb)?.let { return TrackGain(it, TrackGain.REPLAYGAIN_REFERENCE_LUFS) }
        find("R128_TRACK_GAIN")?.let(::parseQ78)?.let { return TrackGain(it, TrackGain.R128_REFERENCE_LUFS) }
        find("REPLAYGAIN_ALBUM_GAIN")?.let(::parseDb)?.let { return TrackGain(it, TrackGain.REPLAYGAIN_REFERENCE_LUFS) }
        find("R128_ALBUM_GAIN")?.let(::parseQ78)?.let { return TrackGain(it, TrackGain.R128_REFERENCE_LUFS) }
        return null
    }

    /** "-6.54 dB", "+2.1 dB", "-6,54" → dB. Anything outside ±60 dB is a broken tag, not a gain. */
    internal fun parseDb(raw: String): Double? {
        val cleaned = raw.trim().lowercase(Locale.ROOT).removeSuffix("db").trim().replace(',', '.')
        return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 60.0 }
    }

    /** RFC 7845: a signed integer in Q7.8 fixed point, i.e. 1/256 dB. */
    internal fun parseQ78(raw: String): Double? =
        raw.trim().toIntOrNull()?.takeIf { it in -32768..32767 }?.let { it / 256.0 }
}
