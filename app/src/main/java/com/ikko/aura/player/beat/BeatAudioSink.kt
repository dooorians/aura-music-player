package com.ikko.aura.player.beat

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * An audio sink that lets a [BeatDetector] hear what passes through it, and changes nothing about it: every call goes on
 * to the real sink as it came.
 */
@UnstableApi
class BeatAudioSink(private val sink: AudioSink, private val detector: BeatDetector) : AudioSink by sink {

    // handleBuffer is called again with the same buffer until the sink has taken all of it: it is heard once.
    private var heard: ByteBuffer? = null
    private var heardAt = Long.MIN_VALUE

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (inputFormat.pcmEncoding != Format.NO_VALUE) {
            detector.configure(inputFormat.sampleRate, inputFormat.channelCount, inputFormat.pcmEncoding)
        } else {
            detector.configure(0, 0, C.ENCODING_INVALID)
        }
        sink.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (buffer !== heard || presentationTimeUs != heardAt) {
            heard = buffer
            heardAt = presentationTimeUs
            detector.onBuffer(buffer, presentationTimeUs)
        }
        val taken = sink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (taken) heard = null
        val audible = sink.getCurrentPositionUs(false)
        if (audible != AudioSink.CURRENT_POSITION_NOT_SET) {
            detector.noteAudible(audible / 1_000_000.0, android.os.SystemClock.elapsedRealtimeNanos() / 1e9)
        }
        return taken
    }

    override fun flush() {
        heard = null
        detector.flush()
        sink.flush()
    }
}
