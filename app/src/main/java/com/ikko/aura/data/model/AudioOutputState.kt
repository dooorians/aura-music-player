package com.ikko.aura.data.model

import androidx.compose.runtime.Immutable

enum class AudioOutputType { PHONE_SPEAKER, WIRED_HEADSET, BLUETOOTH, USB_DAC }

/**
 * One selectable output. [deviceId] is [android.media.AudioDeviceInfo.getId], which is what
 * [androidx.media3.exoplayer.ExoPlayer.setPreferredAudioDevice] is addressed by.
 */
@Immutable
data class AudioOutput(
    val deviceId: Int,
    val type: AudioOutputType,
    val name: String,
    val supportsHiRes: Boolean = false,
)

@Immutable
data class AudioOutputState(
    val type: AudioOutputType = AudioOutputType.PHONE_SPEAKER,
    val deviceName: String? = null,
    val supportsHiRes: Boolean = false,
    /** Everything currently plugged in or paired, for the picker in the player. */
    val available: List<AudioOutput> = emptyList(),
    /**
     * The output the listener picked, or null when Aura is following the system's own choice —
     * which is the default, and what the route reverts to when the chosen device disappears.
     */
    val selectedDeviceId: Int? = null,
)
