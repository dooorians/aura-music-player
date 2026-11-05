package com.ikko.aura.player.silence

import android.net.Uri

/**
 * Process-wide switches for silence trimming, read from the audio thread.
 *
 * Plain volatile fields, the way [com.ikko.aura.player.smart.AutomixSettings] mirrors its own:
 * the processor reads them once per stream (at each flush), so a change applies from the next
 * track or seek, never halfway through a buffer.
 */
object SilenceTrim {

    /** Trimming on or off. Off, the processor still runs but passes every frame through untouched. */
    @Volatile
    var enabled: Boolean = true

    /** Threshold and limits. Replace the whole object to change any of them. */
    @Volatile
    var config: SilenceTrimConfig = SilenceTrimConfig()

    /** When the last trailing silence was skipped, on the caller's clock (elapsedRealtime). */
    @Volatile
    private var lastAutoSkipAtMs: Long = NEVER

    /** Notes that the next track change is the trimmer's doing, not the listener's. */
    fun markAutoSkip(nowMs: Long) {
        lastAutoSkipAtMs = nowMs
    }

    /**
     * Whether a track change seen at [nowMs] is one the trimmer started. It is carried out as a
     * seek, which is otherwise what a listener's skip looks like; the sleep timer needs to tell
     * the two apart.
     */
    fun isRecentAutoSkip(nowMs: Long): Boolean =
        lastAutoSkipAtMs != NEVER && nowMs - lastAutoSkipAtMs in 0..AUTO_SKIP_ATTRIBUTION_MS

    /**
     * Local files only: a file or a content URI from the device's own storage. Streams have
     * their own silences dealt with upstream (and their timing is the server's, not ours).
     */
    fun isLocal(uri: Uri?): Boolean = isLocalScheme(uri?.scheme, hasUri = uri != null)

    /** [isLocal] on the scheme alone, so it can be tested without Android's Uri. */
    fun isLocalScheme(scheme: String?, hasUri: Boolean = true): Boolean = when {
        !hasUri -> false
        scheme == null -> true
        else -> scheme.lowercase() in LOCAL_SCHEMES
    }

    private val LOCAL_SCHEMES = setOf("file", "content", "android.resource")
    private const val NEVER = Long.MIN_VALUE
    private const val AUTO_SKIP_ATTRIBUTION_MS = 2_000L
}
