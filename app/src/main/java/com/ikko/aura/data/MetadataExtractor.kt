package com.ikko.aura.data

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.ikko.aura.data.model.AudioFormat

data class TrackAudioProperties(
    val format: AudioFormat,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateKbps: Int,
)

object MetadataExtractor {

    fun extract(context: Context, uri: Uri, containerMimeType: String?): TrackAudioProperties {
        val probed = runCatching { probeWithExtractor(context, uri) }.getOrNull()
        return probed ?: TrackAudioProperties(
            format = resolveFormat(codecMime = null, containerMime = containerMimeType),
            sampleRateHz = 0,
            bitDepth = 16,
            bitrateKbps = 0,
        )
    }

    private fun probeWithExtractor(context: Context, uri: Uri): TrackAudioProperties {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val audioTrack = (0 until extractor.trackCount)
                .map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return TrackAudioProperties(AudioFormat.UNKNOWN, 0, 16, 0)

            val codecMime = audioTrack.getString(MediaFormat.KEY_MIME)
            val sampleRate = audioTrack.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            val bitrateKbps = audioTrack.getIntOrNull(MediaFormat.KEY_BIT_RATE)?.div(1000) ?: 0
            val bitDepth = when (audioTrack.getIntOrNull(MediaFormat.KEY_PCM_ENCODING)) {
                AndroidAudioFormat.ENCODING_PCM_32BIT, AndroidAudioFormat.ENCODING_PCM_FLOAT -> 32
                AndroidAudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                else -> 16
            }

            return TrackAudioProperties(
                format = resolveFormat(codecMime, null),
                sampleRateHz = sampleRate,
                bitDepth = bitDepth,
                bitrateKbps = bitrateKbps,
            )
        } finally {
            extractor.release()
        }
    }

    /**
     * Some extractors store a key as a long or a float (a corrupted or exotic header): `getInteger` then throws a
     * ClassCastException, which used to throw away the whole probe. The value is read whatever its number type, and
     * a nonsensical one (negative, zero) is no value at all.
     */
    private fun MediaFormat.getIntOrNull(key: String): Int? = runCatching {
        if (!containsKey(key)) return@runCatching null
        when (val value = getNumber(key)) {
            null -> null
            else -> value.toLong().takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        }
    }.getOrNull()

    private fun resolveFormat(codecMime: String?, containerMime: String?): AudioFormat = when {
        codecMime == "audio/alac" -> AudioFormat.ALAC
        codecMime == "audio/flac" || containerMime == "audio/flac" || containerMime == "audio/x-flac" -> AudioFormat.FLAC
        containerMime == "audio/mpeg" -> AudioFormat.MP3
        codecMime?.startsWith("audio/mp4a") == true || containerMime == "audio/mp4" || containerMime == "audio/m4a" -> AudioFormat.AAC
        codecMime == "audio/opus" || containerMime == "audio/opus" -> AudioFormat.OPUS
        containerMime == "audio/ogg" -> AudioFormat.OGG
        containerMime == "audio/x-ms-wma" -> AudioFormat.WMA
        containerMime == "audio/x-wav" || containerMime == "audio/wav" || containerMime == "audio/vnd.wave" -> AudioFormat.WAV
        containerMime == "audio/x-aiff" || containerMime == "audio/aiff" -> AudioFormat.AIFF
        else -> AudioFormat.UNKNOWN
    }
}
