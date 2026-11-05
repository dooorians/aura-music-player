package com.ikko.aura.ai.slm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One message of the conversation given to the model. */
data class SlmMessage(val role: String, val content: String)

data class SlmRequest(
    val modelPath: String,
    val messages: List<SlmMessage>,
    val grammar: String,
    /** Where the processed system prompt and examples are kept between calls; empty for none. */
    val prefixCachePath: String = "",
    val maxTokens: Int = 160,
    val threads: Int = LlamaBridge.THREADS,
    val contextSize: Int = 1024,
)

data class SlmCompletion(
    val ok: Boolean,
    val text: String,
    val error: String = "",
    val cancelled: Boolean = false,
    val prefixCacheHit: Boolean = false,
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val loadMs: Long = 0,
    val promptMs: Long = 0,
    val generateMs: Long = 0,
) {
    val totalMs: Long get() = loadMs + promptMs + generateMs

    companion object {
        /** What nativeGenerate returns: [text bytes, error bytes, "ok,cancelled,cacheHit,prompt,generated,load,prompt,generate"]. */
        internal fun decode(raw: Array<Any?>?): SlmCompletion {
            if (raw == null || raw.size < 3) return SlmCompletion(ok = false, text = "", error = "no answer from the engine")
            val text = (raw[0] as? ByteArray)?.toString(Charsets.UTF_8).orEmpty()
            val error = (raw[1] as? ByteArray)?.toString(Charsets.UTF_8).orEmpty()
            val s = (raw[2] as? String).orEmpty().split(',').map { it.trim().toLongOrNull() ?: 0L }
            fun at(i: Int) = s.getOrElse(i) { 0L }
            return SlmCompletion(
                ok = at(0) == 1L, text = text, error = error, cancelled = at(1) == 1L, prefixCacheHit = at(2) == 1L,
                promptTokens = at(3).toInt(), generatedTokens = at(4).toInt(), loadMs = at(5), promptMs = at(6), generateMs = at(7),
            )
        }
    }
}

/** What runs a completion: the native engine on a phone, a fake in tests. */
fun interface SlmEngine {
    suspend fun complete(request: SlmRequest): SlmCompletion
}

/**
 * The JNI side of the assistant's model (app/src/main/cpp/jni/llama_bridge.cpp → native/slm/slm_engine.cpp).
 *
 * The library is loaded the first time it is needed, never at the app's start. Each call loads the model, answers and
 * frees everything native before it returns (llama_free, llama_model_free): between two requests the assistant holds
 * no memory. Calls are serialised (one model in memory at a time, whatever the UI does) and run on
 * [Dispatchers.Default], with the engine sized to [THREADS] threads at normal priority, so the phone keeps cores for
 * the interface and Media3's audio thread is never starved. Cancelling the calling coroutine stops the computation at
 * its next step.
 */
object LlamaBridge : SlmEngine {

    /** The CPU threads the model may use: the calling Default thread and one of ggml's own. */
    const val THREADS = 2

    /**
     * False when the library is not in the APK (a build without the NDK), cannot be loaded, or would not run here: it is
     * compiled for ARMv8.2-A with the dot-product and half-precision extensions (see native/slm/CMakeLists.txt), and on
     * a core without them (a Cortex-A53) its first instruction of either kind would kill the app. The CPU is asked first.
     */
    val available: Boolean by lazy { cpuSupported(cpuFeatures()) && runCatching { System.loadLibrary("aura_llm") }.isSuccess }

    /** The "Features" lines of /proc/cpuinfo, one per core (empty when it cannot be read). */
    private fun cpuFeatures(): List<String> =
        runCatching { java.io.File("/proc/cpuinfo").readLines().filter { it.trimStart().startsWith("Features") } }.getOrDefault(emptyList())

    /** Whether every core listed has the dot-product (asimddp) and half-precision (asimdhp) instructions. */
    internal fun cpuSupported(featureLines: List<String>): Boolean =
        featureLines.isNotEmpty() && featureLines.all { line ->
            val flags = line.substringAfter(':').trim().split(Regex("\\s+")).toSet()
            "asimddp" in flags && "asimdhp" in flags
        }

    private val mutex = Mutex()

    override suspend fun complete(request: SlmRequest): SlmCompletion {
        if (!available) return SlmCompletion(ok = false, text = "", error = "native engine unavailable")
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                coroutineScope {
                    // The native call blocks this thread; a sibling waits for a cancellation and relays it. On a normal
                    // return the sibling is cancelled too and its cancel is harmless: the next call clears the flag.
                    val relay = launch { try { awaitCancellation() } finally { nativeCancel() } }
                    try {
                        ensureActive()
                        val raw = nativeGenerate(
                            request.modelPath,
                            request.messages.map { it.role }.toTypedArray(),
                            request.messages.map { it.content }.toTypedArray(),
                            request.grammar,
                            request.prefixCachePath,
                            request.maxTokens,
                            request.threads.coerceIn(1, THREADS),
                            request.contextSize,
                        )
                        SlmCompletion.decode(raw)
                    } finally {
                        relay.cancel()
                    }
                }.also { ensureActive() }
            }
        }
    }

    @JvmStatic
    private external fun nativeGenerate(
        modelPath: String,
        roles: Array<String>,
        contents: Array<String>,
        grammar: String,
        prefixCachePath: String,
        maxTokens: Int,
        threads: Int,
        contextSize: Int,
    ): Array<Any?>?

    @JvmStatic
    private external fun nativeCancel()
}
