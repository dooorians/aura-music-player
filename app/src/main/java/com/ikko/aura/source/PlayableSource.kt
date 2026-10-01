package com.ikko.aura.source

/**
 * One way to play a track right now: what a [SourceProvider] answers when asked for an item.
 *
 * The address is a String rather than a Uri so that the code deciding between candidates can be tested on the JVM.
 */
data class PlayableSource(
    val ref: SourceTrackRef,
    val uri: String,
    /** Null when the source does not say. When it does, anything that is not an audio type is never played. */
    val mimeType: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    /** A file or content:// item that can be read at any offset: what the Automix analysis needs. */
    val randomAccess: Boolean,
)
