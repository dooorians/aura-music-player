package com.ikko.aura.core.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import java.util.Random

/** Test signals, interleaved, as the engine takes them. */
internal object DspTestSignals {

    fun sine(frequencyHz: Double, amplitude: Double, sampleRate: Int, channels: Int, seconds: Double, phase: Double = 0.0): FloatArray {
        val frames = (seconds * sampleRate).toInt()
        val out = FloatArray(frames * channels)
        for (i in 0 until frames) {
            val v = (amplitude * sin(2 * PI * frequencyHz * i / sampleRate + phase)).toFloat()
            for (c in 0 until channels) out[i * channels + c] = v
        }
        return out
    }

    fun whiteNoise(amplitude: Float, frames: Int, channels: Int, seed: Long = 42): FloatArray {
        val random = Random(seed)
        return FloatArray(frames * channels) { (random.nextFloat() * 2f - 1f) * amplitude }
    }

    fun square(frequencyHz: Double, amplitude: Float, sampleRate: Int, channels: Int, seconds: Double): FloatArray {
        val frames = (seconds * sampleRate).toInt()
        val period = sampleRate / frequencyHz
        return FloatArray(frames * channels) { idx ->
            val i = idx / channels
            if ((i % period) < period / 2) amplitude else -amplitude
        }
    }

    fun dbfs(db: Double) = 10.0.pow(db / 20.0)

    fun peak(buffer: FloatArray): Float {
        var p = 0f
        for (v in buffer) if (abs(v) > p) p = abs(v)
        return p
    }

    fun allFinite(buffer: FloatArray): Boolean = buffer.all { it.isFinite() }

    /** Runs [buffer] through [engine] in chunks of [chunkFrames], as a sink would hand it over. */
    fun processInChunks(engine: DspEngine, buffer: FloatArray, channels: Int, chunkFrames: Int = 1024) {
        val frames = buffer.size / channels
        val chunk = FloatArray(chunkFrames * channels)
        var start = 0
        while (start < frames) {
            val n = minOf(chunkFrames, frames - start)
            System.arraycopy(buffer, start * channels, chunk, 0, n * channels)
            engine.process(chunk, n)
            System.arraycopy(chunk, 0, buffer, start * channels, n * channels)
            start += n
        }
    }

    fun graphic(vararg gains: Float): List<FilterSpec> = gains.indices.map { IsoBands.spec(it, gains[it]) }

    fun allBands(gainDb: Float): List<FilterSpec> = graphic(*FloatArray(IsoBands.COUNT) { gainDb })
}
