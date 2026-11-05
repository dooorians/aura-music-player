package com.ikko.aura.reco

import android.media.MediaDataSource
import android.util.Log
import com.ikko.aura.player.smart.AudioDecoder
import com.ikko.aura.player.smart.TrackFeatures
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the audio of a 30-second preview says of a song, measured by the app's own analyser (the one Automix uses): the tempo it hears and how sure it
 * is of it, how loud the audio really is, how much of its energy sits in the low end, and how much its energy moves from one moment to the next
 * (a sustained pad is steady, a driving beat is not).
 */
data class AudioProbe(
    val bpm: Double,
    /** 0..1: how clearly a beat was heard; below about 0.3 the tempo is a guess. */
    val beatConfidence: Double,
    /** Average level of the audio, dBFS (0 is full scale, a loud master sits near -10, a quiet one near -25). */
    val rmsDb: Double,
    /** 0..1: the share of the energy that is bass. */
    val lowShare: Double,
    /** Spread of the energy over its mean: low for something steady, high for something punchy. */
    val variability: Double,
)

/** A [MediaDataSource] over bytes already downloaded. */
class BytesDataSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(size.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = bytes.size.toLong()
    override fun close() {}
}

/** What was heard of a preview: what a model said of it ([feel]) and, when that was not available, what the app's own analyser measured ([probe]). */
class Heard(val feel: ReccoFeatures?, val probe: AudioProbe?)

/**
 * Downloads a preview once and listens to it. ReccoBeats, which has a model for energy, acousticness and mood, is asked first; the app's own
 * analyser (Automix's) only runs when it could not be, which saves the phone the decoding. Best-effort: anything that goes wrong is "heard
 * nothing", never an error.
 */
@Singleton
class PreviewAnalyzer @Inject constructor(private val recco: RecoBeatsClient) {

    suspend fun listen(previewUrl: String): Heard? = withContext(Dispatchers.IO) {
        val bytes = runCatching { download(previewUrl) }.getOrNull() ?: return@withContext null
        val feel = recco.analyse(bytes)
        val probe = if (feel == null) measure(bytes) else null
        if (feel == null && probe == null) null else Heard(feel, probe)
    }

    private fun measure(bytes: ByteArray): AudioProbe? {
        return runCatching {
            if (!TrackFeatures.available) return@runCatching null
            BytesDataSource(bytes).use { source ->
                val (pcm, _) = AudioDecoder.decodeRegion(source, 0.0, PREVIEW_SECONDS) ?: return@runCatching null
                if (pcm.samples.isEmpty()) return@runCatching null
                val seconds = pcm.samples.size / pcm.sampleRate
                val samples = TrackFeatures.resample(pcm.samples, pcm.sampleRate) ?: return@runCatching null
                val f = TrackFeatures.analyze(samples, seconds) ?: return@runCatching null
                val energy = f.energyCurve.map { it.energy }
                val low = f.lowEnergyCurve.map { it.energy }
                val mean = energy.average().takeIf { energy.isNotEmpty() && it > 0.0 } ?: return@runCatching null
                val variance = energy.sumOf { (it - mean) * (it - mean) } / energy.size
                AudioProbe(
                    bpm = f.bpm, beatConfidence = f.beatConfidence, rmsDb = rmsDb(pcm.samples),
                    lowShare = if (low.isEmpty()) 0.0 else (low.average() / mean).coerceIn(0.0, 1.0),
                    variability = sqrt(variance) / mean,
                )
            }
        }.onFailure { Log.w(TAG, "Preview not analysed", it) }.getOrNull()
    }

    private fun download(url: String): ByteArray? = com.ikko.aura.net.NetGuard.exchange(
        url,
        setup = { it.connectTimeout = 8_000; it.readTimeout = 10_000; it.useCaches = false },
    ) { connection, code ->
        if (code !in 200..299) return@exchange null
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        connection.inputStream.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) return@exchange null
            }
        }
        out.toByteArray()
    }

    private fun rmsDb(samples: FloatArray): Double {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        val rms = sqrt(sum / samples.size)
        return if (rms <= 1e-9) -90.0 else 20.0 * log10(rms)
    }

    private companion object {
        const val TAG = "PreviewAnalyzer"
        const val PREVIEW_SECONDS = 30.0
        const val MAX_BYTES = 2_000_000
    }
}
