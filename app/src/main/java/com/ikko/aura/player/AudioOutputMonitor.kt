package com.ikko.aura.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import com.ikko.aura.data.model.AudioOutput
import com.ikko.aura.data.model.AudioOutputState
import com.ikko.aura.data.model.AudioOutputType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the music is coming out of, and what else it could come out of.
 *
 * Both halves live here rather than being split between a reader and a writer because they are
 * the same question: the list the picker shows is the list [refresh] already builds, and a device
 * being unplugged has to both update the label and drop a selection that now points at nothing.
 *
 * A `@Singleton`, and [PlaybackService] runs in the same process, so the service observes
 * [state] directly instead of the selection travelling out through a session command and back.
 */
@Singleton
class AudioOutputMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(AudioOutputState())
    val state: StateFlow<AudioOutputState> = _state.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, null)
        refresh()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    /**
     * Routes playback to [deviceId], or back to whatever the system would choose when null.
     *
     * Applied by [PlaybackService] to both of its players — the idle one included, since it is
     * the one the next transition will start a track on.
     */
    fun select(deviceId: Int?) {
        _state.value = _state.value.copy(selectedDeviceId = deviceId)
        refresh()
    }

    private fun refresh() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in SELECTABLE_TYPES }
            // One entry per device. A single headset is routinely reported twice — once per
            // profile — and a picker listing "Bose QC" three times is worse than useless.
            .distinctBy { it.id }
            .map { device ->
                AudioOutput(
                    deviceId = device.id,
                    type = device.outputType(),
                    name = device.label(),
                    // Only claimed for a route where it means something. The built-in speaker
                    // advertises float PCM at the sink like everything else does, and labelling
                    // a phone speaker "haute résolution" is true of the buffer and false of
                    // anything the listener would understand by it.
                    supportsHiRes = device.outputType() in HI_RES_CAPABLE_TYPES &&
                        device.encodings.any {
                            it == AudioFormat.ENCODING_PCM_24BIT_PACKED ||
                                it == AudioFormat.ENCODING_PCM_32BIT ||
                                it == AudioFormat.ENCODING_PCM_FLOAT
                        },
                )
            }
            .sortedBy { PRIORITY.indexOf(it.type) }

        // A selection survives only as long as the device does. Left pointing at an id that has
        // gone, ExoPlayer silently falls back to the system route while the player screen still
        // claimed the unplugged DAC.
        val selectedId = _state.value.selectedDeviceId?.takeIf { id -> outputs.any { it.deviceId == id } }
        val active = outputs.firstOrNull { it.deviceId == selectedId } ?: outputs.firstOrNull()

        _state.value = AudioOutputState(
            type = active?.type ?: AudioOutputType.PHONE_SPEAKER,
            deviceName = active?.name,
            supportsHiRes = active?.supportsHiRes ?: false,
            available = outputs,
            selectedDeviceId = selectedId,
        )
    }

    private fun AudioDeviceInfo.outputType(): AudioOutputType = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
            AudioOutputType.USB_DAC
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
            AudioOutputType.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
            AudioOutputType.WIRED_HEADSET
        else -> AudioOutputType.PHONE_SPEAKER
    }

    /**
     * Product names are unreliable: a wired headset usually has none, and a built-in speaker
     * reports the phone's model, which reads as another device in a list. Named per type, with
     * the product name only where it identifies something.
     */
    private fun AudioDeviceInfo.label(): String {
        val product = productName?.toString()?.trim().orEmpty()
        return when (outputType()) {
            AudioOutputType.PHONE_SPEAKER -> "Haut-parleur"
            AudioOutputType.WIRED_HEADSET -> product.ifBlank { "Casque filaire" }
            AudioOutputType.BLUETOOTH -> product.ifBlank { "Bluetooth" }
            AudioOutputType.USB_DAC -> product.ifBlank { "DAC USB-C" }
        }
    }

    private companion object {
        val SELECTABLE_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )

        val HI_RES_CAPABLE_TYPES = setOf(AudioOutputType.USB_DAC, AudioOutputType.WIRED_HEADSET)

        /** What the system itself would pick, so the list reads in the order it would route. */
        val PRIORITY = listOf(
            AudioOutputType.USB_DAC,
            AudioOutputType.BLUETOOTH,
            AudioOutputType.WIRED_HEADSET,
            AudioOutputType.PHONE_SPEAKER,
        )
    }
}
