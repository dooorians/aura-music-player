package com.ikko.aura.reco

import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What a model heard of an audio extract, on the scale Spotify's audio features used: [energy], [acousticness], [danceability] and [valence] are 0..1,
 * [loudness] is in dB, [tempo] in beats a minute (often an octave off, like every tempo estimate).
 */
data class ReccoFeatures(
    val energy: Double,
    val acousticness: Double,
    val valence: Double,
    val danceability: Double,
    val loudness: Double,
    val tempo: Double,
)

/** One answer of the service: the status, the body, and how long to wait when it said "too many requests". */
class RecoBeatsAnswer(val status: Int, val body: String, val retryAfterSeconds: Long? = null)

/**
 * ReccoBeats' free audio analysis (https://reccobeats.com/docs/documentation/Analysis/audio-features-extraction): an audio file goes in (MP3, 5 MB at most,
 * the first 30 seconds are heard), energy, acousticness, valence, danceability, loudness and tempo come out. No key, no account; the terms ask not to
 * overload it, so requests are spaced out and a "429" is waited out for as long as it says.
 */
@Singleton
class RecoBeatsClient(
    private val post: (suspend (ByteArray) -> RecoBeatsAnswer)? = null,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor() : this(null)

    private val spacing = Mutex()
    private var lastRequestAt = 0L
    @Volatile private var pausedUntil = 0L

    /** Null when the service refused, did not answer, or is being left alone after a "429". */
    suspend fun analyse(audio: ByteArray): ReccoFeatures? {
        if (audio.isEmpty() || audio.size > MAX_BYTES || clock() < pausedUntil) return null
        spacing.withLock {
            val wait = minIntervalMs - (clock() - lastRequestAt)
            if (wait > 0) delay(wait)
            lastRequestAt = clock()
        }
        val send: suspend (ByteArray) -> RecoBeatsAnswer = post ?: { network(it) }
        val answer = runCatching { send(audio) }.getOrNull() ?: return null
        if (answer.status == 429) {
            pausedUntil = clock() + (answer.retryAfterSeconds ?: DEFAULT_PAUSE_S) * 1_000L
            return null
        }
        return if (answer.status in 200..299) parse(answer.body) else null
    }

    private suspend fun network(audio: ByteArray): RecoBeatsAnswer = withContext(Dispatchers.IO) {
        val boundary = "aura" + UUID.randomUUID().toString().replace("-", "")
        com.ikko.aura.net.NetGuard.exchange(
            ENDPOINT,
            setup = { connection ->
                connection.requestMethod = "POST"
                connection.connectTimeout = 8_000
                connection.readTimeout = 30_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connection.setRequestProperty("User-Agent", "Aura/1.0")
                connection.outputStream.use { out ->
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"audioFile\"; filename=\"preview.mp3\"\r\nContent-Type: audio/mpeg\r\n\r\n".toByteArray())
                    out.write(audio)
                    out.write("\r\n--$boundary--\r\n".toByteArray())
                }
            },
        ) { connection, code ->
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { s -> ByteArrayOutputStream().also { s.copyTo(it) }.toString(Charsets.UTF_8.name()) }.orEmpty()
            RecoBeatsAnswer(code, text, connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
        }
    }

    companion object {
        const val ENDPOINT = "https://api.reccobeats.com/v1/analysis/audio-features"
        const val MAX_BYTES = 5_000_000
        const val MIN_INTERVAL_MS = 300L
        const val DEFAULT_PAUSE_S = 120L

        /** The answer as features; null when it is not what the documentation says (a missing field is no answer). */
        fun parse(body: String): ReccoFeatures? = runCatching {
            val j = JSONObject(body)
            fun field(name: String) = j.getDouble(name).takeIf { it.isFinite() }
            ReccoFeatures(
                energy = field("energy")!!.coerceIn(0.0, 1.0), acousticness = field("acousticness")!!.coerceIn(0.0, 1.0),
                valence = field("valence")!!.coerceIn(0.0, 1.0), danceability = field("danceability")!!.coerceIn(0.0, 1.0),
                loudness = field("loudness")!!, tempo = field("tempo")!!,
            )
        }.getOrNull()
    }
}
