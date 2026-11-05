package com.ikko.aura.player.sleep

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The sleep timer's volume, shared by both players' [SleepFadeProcessor]s. 1 is untouched. */
object SleepFade {
    @Volatile
    var gain: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }
}

/**
 * Applies [SleepFade.gain] to the audio, inside the sink.
 *
 * In the sink rather than through `Player.volume` because the player's volume already belongs to
 * the crossfade, which rides it on both players and puts it back to 1 when it is done: a sleep
 * fade there would be fought over and undone. Here the two multiply instead, and a crossfade that
 * happens during the sleep fade simply happens more quietly.
 *
 * The gain glides toward its target over [RAMP_SECONDS] rather than stepping, so the timer can
 * update it a few times a second without zipper noise. At 1 it is a plain copy.
 */
@UnstableApi
class SleepFadeProcessor : BaseAudioProcessor() {

    private var current = 1f
    private var isFloat = false
    private var channels = 0
    private var stepPerFrame = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if ((encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount < 1) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        isFloat = encoding == C.ENCODING_PCM_FLOAT
        channels = inputAudioFormat.channelCount
        stepPerFrame = 1f / (inputAudioFormat.sampleRate.coerceAtLeast(1) * RAMP_SECONDS)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // Nothing continuous to glide from after a flush.
        current = SleepFade.gain
    }

    override fun onReset() {
        current = 1f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val target = SleepFade.gain
        if (target >= 1f && current >= 1f) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        while (inputBuffer.hasRemaining()) {
            current = rampToward(current, target, stepPerFrame)
            repeat(channels) {
                if (isFloat) {
                    output.putFloat(inputBuffer.float * current)
                } else {
                    output.putShort((inputBuffer.short * current).toInt().toShort())
                }
            }
        }
        output.flip()
    }

    internal companion object {
        const val RAMP_SECONDS = 0.05f

        /** One frame's move from [from] toward [to], at most [step]. */
        fun rampToward(from: Float, to: Float, step: Float): Float = when {
            from < to -> minOf(from + step, to)
            from > to -> maxOf(from - step, to)
            else -> from
        }
    }
}
