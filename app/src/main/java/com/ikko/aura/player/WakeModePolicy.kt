package com.ikko.aura.player

import androidx.media3.common.C

/**
 * Which locks ExoPlayer may hold while a track plays. It takes them only while it is actually playing (playWhenReady, and
 * buffering or ready) and lets them go the moment it pauses, stops, ends or is released — so the choice here is only *what*
 * is held during playback, never *whether* anything outlives it.
 *
 * - A file on the device needs the CPU awake between audio buffers, nothing more: a partial wake lock ([C.WAKE_MODE_LOCAL]).
 * - A stream also needs the Wi-Fi radio kept out of its power-save mode, or it buffers dry with the screen off
 *   ([C.WAKE_MODE_NETWORK]: the same wake lock plus a Wi-Fi lock). Holding that lock for a local file would keep the radio up
 *   for nothing.
 * - No track: nothing at all.
 */
object WakeModePolicy {
    private val LOCAL_SCHEMES = setOf("content", "file", "android.resource", "asset", "rawresource", "data")

    fun forScheme(scheme: String?): Int = when {
        scheme == null -> C.WAKE_MODE_NONE
        scheme.lowercase() in LOCAL_SCHEMES -> C.WAKE_MODE_LOCAL
        else -> C.WAKE_MODE_NETWORK
    }
}
