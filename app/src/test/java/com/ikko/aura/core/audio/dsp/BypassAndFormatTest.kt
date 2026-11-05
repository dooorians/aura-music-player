package com.ikko.aura.core.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassAndFormatTest {

    private val bassBoost = DspConfig(bypass = false, filters = DspTestSignals.graphic(6f, 5.5f, 4.5f, 3f, 1.5f, 0f, 0f, 0f, 0f, 0f))

    // ---------------------------------------------------------------------------------------------------------------
    // Bypass

    @Test
    fun `bypassed, the engine leaves every sample exactly as it came`() {
        val params = DspParameters(bassBoost.copy(bypass = true, normalizationEnabled = true, preampDb = 6.0))
        val engine = DspEngine(params).apply { configure(48_000, 2) }
        val signal = DspTestSignals.whiteNoise(0.9f, 48_000, 2)
        val original = signal.copyOf()
        DspTestSignals.processInChunks(engine, signal, 2)
        assertArrayEquals(original, signal, 0f)
        assertTrue(engine.isFullyBypassed)
    }

    @Test
    fun `switching the bypass fades between dry and processed, without a step`() {
        val fs = 48_000
        val amplitude = 0.15
        val config = bassBoost.copy(bypass = true, autoPreamp = false)
        val params = DspParameters(config)
        val engine = DspEngine(params).apply { configure(fs, 1) }
        // A low tone, where the boost makes processed and dry differ most.
        val c = DoubleArray(Biquad.STRIDE)
        val gainDb = config.filters.sumOf { Biquad.design(it, fs, c, 0); Biquad.magnitudeDb(c, 0, 63.0, fs) }
        val gain = DspTestSignals.dbfs(gainDb)
        val signal = DspTestSignals.sine(63.0, amplitude, fs, 1, 2.0)
        val maxNaturalStep = 2 * Math.PI * 63.0 / fs * amplitude * gain
        val chunk = 480
        var start = 0
        while (start < signal.size) {
            if (start == fs / 2) params.update { it.copy(bypass = false) }
            if (start == fs * 3 / 2) params.update { it.copy(bypass = true) }
            val block = signal.copyOfRange(start, start + chunk)
            engine.process(block, chunk)
            System.arraycopy(block, 0, signal, start, chunk)
            start += chunk
        }
        // A click would be a jump of the order of the amplitude; the tone itself never moves more than this per sample.
        for (i in 1 until signal.size) {
            val step = abs(signal[i] - signal[i - 1])
            assertTrue("jump $step at $i (tone's own max ${maxNaturalStep})", step <= maxNaturalStep * 1.5)
        }
        // And it did process in between: the middle carries the boost, the end is the dry tone again.
        val middle = DspTestSignals.peak(signal.copyOfRange(fs, fs + fs / 4))
        val end = DspTestSignals.peak(signal.copyOfRange(signal.size - fs / 4, signal.size))
        assertEquals(amplitude * gain, middle.toDouble(), amplitude * gain * 0.03)
        assertEquals(amplitude, end.toDouble(), 0.001)
    }

    @Test
    fun `flat and unity, processing changes nothing that is under the limiter's knee`() {
        val params = DspParameters(DspConfig(bypass = false))
        val engine = DspEngine(params).apply { configure(44_100, 2) }
        val signal = DspTestSignals.sine(997.0, 0.5, 44_100, 2, 0.5)
        val original = signal.copyOf()
        DspTestSignals.processInChunks(engine, signal, 2)
        assertArrayEquals(original, signal, 0f)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The Media3 processor: formats, rates, frame counts

    private fun processor(config: DspConfig) = DspAudioProcessor(DspParameters(config))

    private fun run(processor: DspAudioProcessor, input: ByteBuffer): ByteBuffer {
        processor.queueInput(input)
        return processor.output
    }

    @Test
    fun `16-bit in, 32-bit float out, at the same rate and channel count`() {
        for (rate in intArrayOf(44_100, 48_000, 96_000, 192_000)) for (channels in 1..2) {
            val p = processor(DspConfig(bypass = true))
            val out = p.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
            assertEquals(rate, out.sampleRate)
            assertEquals(channels, out.channelCount)
            assertEquals(C.ENCODING_PCM_FLOAT, out.encoding)
            assertTrue(p.isActive)
        }
    }

    @Test
    fun `bypassed, a 16-bit stream comes out as the exact float of each sample, one frame per frame`() {
        val p = processor(DspConfig(bypass = true))
        p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val samples = shortArrayOf(0, 1, -1, 16_384, -16_384, Short.MAX_VALUE, Short.MIN_VALUE, 1234, -4321, 77)
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        val output = run(p, input).order(ByteOrder.nativeOrder())
        assertEquals(0, input.remaining())
        assertEquals(samples.size * 4, output.remaining()) // same frame count, four bytes a sample
        for (s in samples) assertEquals(s / 32768f, output.float, 0f)
    }

    @Test
    fun `24-bit, 32-bit and float input are read exactly`() {
        val cases = listOf(
            C.ENCODING_PCM_24BIT to intArrayOf(0, 1, -1, 4_194_304, -8_388_608, 8_388_607),
            C.ENCODING_PCM_32BIT to intArrayOf(0, 1 shl 16, -(1 shl 30), Int.MIN_VALUE, Int.MAX_VALUE, 12345 shl 8),
        )
        for ((encoding, values) in cases) {
            val p = processor(DspConfig(bypass = true))
            p.configure(AudioProcessor.AudioFormat(48_000, 1, encoding))
            p.flush(AudioProcessor.StreamMetadata.DEFAULT)
            val width = if (encoding == C.ENCODING_PCM_24BIT) 3 else 4
            val input = ByteBuffer.allocateDirect(values.size * width).order(ByteOrder.LITTLE_ENDIAN)
            for (v in values) {
                if (width == 3) {
                    input.put((v and 0xFF).toByte()); input.put((v shr 8 and 0xFF).toByte()); input.put((v shr 16).toByte())
                } else input.putInt(v)
            }
            input.flip()
            val output = run(p, input).order(ByteOrder.nativeOrder())
            assertEquals(values.size * 4, output.remaining())
            val scale = if (width == 3) 8_388_608.0 else 2_147_483_648.0
            for (v in values) assertEquals("encoding $encoding value $v", v / scale, output.float.toDouble(), 1e-7)
        }

        val p = processor(DspConfig(bypass = true))
        p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val values = floatArrayOf(0f, 0.5f, -0.25f, 1f, -1f, 1e-6f)
        val input = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        values.forEach { input.putFloat(it) }
        input.flip()
        val output = run(p, input).order(ByteOrder.nativeOrder())
        for (v in values) assertEquals(v, output.float, 0f)
    }

    @Test
    fun `processing keeps the frame count of every buffer, whatever its size`() {
        val p = processor(bassBoost.copy(normalizationEnabled = true))
        p.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        var framesIn = 0L
        var framesOut = 0L
        for (frames in intArrayOf(1, 7, 64, 65, 1024, 4096, 333)) {
            val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
            repeat(frames * 2) { input.putShort((it * 97 % 20_000 - 10_000).toShort()) }
            input.flip()
            framesIn += frames
            val output = run(p, input)
            framesOut += output.remaining() / 8
            val floats = output.order(ByteOrder.nativeOrder()).asFloatBuffer()
            while (floats.hasRemaining()) {
                val v = floats.get()
                assertTrue(v.isFinite() && abs(v) <= 1f)
            }
        }
        assertEquals(framesIn, framesOut)
    }

    @Test
    fun `formats the engine cannot take are refused rather than garbled`() {
        val p = processor(DspConfig())
        val refused = runCatching { p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_8BIT)) }
        assertTrue(refused.exceptionOrNull() is AudioProcessor.UnhandledAudioFormatException)
        val tooMany = runCatching { p.configure(AudioProcessor.AudioFormat(48_000, 12, C.ENCODING_PCM_16BIT)) }
        assertTrue(tooMany.exceptionOrNull() is AudioProcessor.UnhandledAudioFormatException)
    }

    @Test
    fun `a new track takes its tag at the flush, not before`() {
        val params = DspParameters(DspConfig(bypass = false, normalizationEnabled = true, targetLufs = -14.0))
        val p = DspAudioProcessor(params)
        p.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        // A -18 LUFS reference tag of -10 dB, at a -14 target: -6 dB.
        p.onNextTrack(TrackGain(-10.0, TrackGain.REPLAYGAIN_REFERENCE_LUFS))
        p.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val frames = 48_000
        val input = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
        repeat(frames) { input.putShort(16_384) } // 0.5
        input.flip()
        val output = run(p, input).order(ByteOrder.nativeOrder()).asFloatBuffer()
        output.position(frames - 1)
        assertEquals(0.5 * DspTestSignals.dbfs(-6.0), output.get().toDouble(), 1e-3)
    }

    @Test
    fun `the chain puts the DSP last, after Sonic, and leaves durations to Sonic`() {
        val sonic = SonicAudioProcessor()
        val dsp = processor(DspConfig())
        val chain = DspAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(arrayOf(), SilenceSkippingAudioProcessor(), sonic), dsp)
        val processors = chain.audioProcessors
        assertSame(dsp, processors.last())
        assertSame(sonic, processors[processors.size - 2])
        assertEquals(1_000_000L, chain.getMediaDuration(1_000_000L))
        assertEquals(0L, chain.skippedOutputFrameCount)
    }
}
