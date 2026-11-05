package com.ikko.aura.core.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The app's equaliser, normaliser and limiter, as a Media3 audio processor.
 *
 * Takes linear PCM in 16, 24 or 32-bit integers or 32-bit float, and always gives 32-bit float out at the same rate
 * and channel count: one output frame per input frame, so the sink's clock and the track's duration are untouched.
 * Float out is the point — the boost the curve adds lives above 0 dBFS until the limiter brings it back, which a
 * 16-bit buffer would have clipped on the way.
 *
 * It stays active whether or not the equaliser is on. Turning it on and off is the engine's crossfade, not a
 * reconfiguration of the sink, which would flush the audio and be heard as a drop-out.
 *
 * Track changes reach it through [onNextTrack], called by [DspAudioSink] when the sink is configured for a new
 * format. The gain is held until the pipeline flushes onto that format — the moment the new track's first frame
 * arrives — so the end of the previous track is not levelled with the next one's tag.
 */
@UnstableApi
class DspAudioProcessor(parameters: DspParameters) : BaseAudioProcessor() {

    private val engine = DspEngine(parameters)

    private var encoding = C.ENCODING_INVALID
    private var scratch = FloatArray(0)

    private var nextTrackPending = false
    private var nextTrackGain: TrackGain? = null

    /** The next configuration is a new track, tagged with [trackGain] if it has one. */
    fun onNextTrack(trackGain: TrackGain?) {
        nextTrackPending = true
        nextTrackGain = trackGain
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in SUPPORTED_ENCODINGS) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        if (inputAudioFormat.channelCount !in 1..MAX_CHANNELS || inputAudioFormat.sampleRate <= 0) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val format = inputAudioFormat
        if (format == AudioProcessor.AudioFormat.NOT_SET) return
        encoding = format.encoding
        if (format.sampleRate != engine.sampleRate || format.channelCount != engine.channels) {
            engine.configure(format.sampleRate, format.channelCount)
        }
        if (nextTrackPending) {
            nextTrackPending = false
            engine.startTrack(nextTrackGain)
        } else {
            engine.flush()
        }
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        scratch = FloatArray(0)
        nextTrackPending = false
        nextTrackGain = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val bytesPerSample = bytesPerSample(encoding)
        if (channels <= 0 || bytesPerSample == 0) return
        val frames = inputBuffer.remaining() / (bytesPerSample * channels)
        if (frames == 0) return
        val samples = frames * channels
        if (scratch.size < samples) scratch = FloatArray(samples)

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        decode(inputBuffer, scratch, samples)
        // Whatever is left over (not a whole frame) is consumed too, as every processor in the chain does.
        inputBuffer.position(inputBuffer.limit())

        engine.process(scratch, frames)

        val output = replaceOutputBuffer(samples * Float.SIZE_BYTES)
        output.asFloatBuffer().put(scratch, 0, samples)
        output.position(samples * Float.SIZE_BYTES)
        output.flip()
    }

    private fun decode(input: ByteBuffer, out: FloatArray, samples: Int) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> for (i in 0 until samples) out[i] = input.short * INV_16
            C.ENCODING_PCM_24BIT -> for (i in 0 until samples) {
                val b0 = input.get().toInt() and 0xFF
                val b1 = input.get().toInt() and 0xFF
                val b2 = input.get().toInt() // sign-carrying top byte
                out[i] = ((b2 shl 16) or (b1 shl 8) or b0) * INV_24
            }
            C.ENCODING_PCM_32BIT -> for (i in 0 until samples) out[i] = (input.int * INV_32).toFloat()
            C.ENCODING_PCM_FLOAT -> for (i in 0 until samples) out[i] = input.float
        }
    }

    companion object {
        const val MAX_CHANNELS = 8

        private val SUPPORTED_ENCODINGS = intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)

        // Powers of two: exact in float, so a 16-bit sample turns into the very value it stood for.
        private const val INV_16 = 1f / 32768f
        private const val INV_24 = 1f / 8388608f
        private const val INV_32 = 1.0 / 2147483648.0

        private fun bytesPerSample(encoding: Int) = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
    }
}
