package com.ikko.aura.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class AudioFormat {
    MP3, AAC, OGG, OPUS, WMA,
    FLAC, ALAC, WAV, AIFF, APE,
    UNKNOWN,
}

/**
 * [Uri] is an external platform type the Compose compiler can't see the internals of, so without
 * this annotation it infers the whole class as unstable — meaning every [com.ikko.aura.ui
 * .components.TrackListItem] in a scrolling list recomposes on *any* parent recomposition
 * (e.g. every 500ms position tick) rather than being skipped when the actual [Track] it was
 * given hasn't changed. [Track] genuinely never mutates a [Uri] in place, so asserting stability
 * here is correct and is the standard, documented fix for this exact situation.
 */
@Immutable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artistId: Long,
    val albumId: Long,
    val artworkUri: Uri?,
    val contentUri: Uri,
    val durationMs: Long,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val format: AudioFormat = AudioFormat.UNKNOWN,
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 16,
    /** A streamed song's album on its source (a YouTube Music playlist id), when known. */
    val remoteAlbumId: String? = null,
    /** When the file reached the device, in seconds (the media store's DATE_ADDED); 0 when it is not known. */
    val addedAtSec: Long = 0L,
    /** The disc of a multi-disc album, 0 when not known: it orders the album's songs before the track number does. */
    val discNumber: Int = 0,
) {
    val isLossless: Boolean
        get() = format in LOSSLESS_FORMATS

    val isHiRes: Boolean
        get() = isLossless && (sampleRateHz > 44_100 || bitDepth > 16)

    companion object {
        private val LOSSLESS_FORMATS = setOf(
            AudioFormat.FLAC, AudioFormat.ALAC, AudioFormat.WAV, AudioFormat.AIFF, AudioFormat.APE,
        )
    }
}
