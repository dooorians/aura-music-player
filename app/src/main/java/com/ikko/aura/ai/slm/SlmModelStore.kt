package com.ikko.aura.ai.slm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A GGUF model the assistant can run, and how to be sure the file is that model. */
data class SlmModelSpec(
    val id: String,
    val displayName: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

object SlmModels {
    /**
     * Qwen 2.5 0.5B Instruct, quantised Q4_K_M by Qwen themselves (Apache-2.0). Chosen over SmolLM2 360M after running
     * both on the prompt of the app (native/slm/host/slm_cli): it reads French requests far better, for 100 MB more.
     */
    val QWEN_2_5_0_5B = SlmModelSpec(
        id = "qwen2.5-0.5b-instruct-q4_k_m",
        displayName = "Qwen 2.5 · 0.5B",
        url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        sizeBytes = 491_400_032L,
        sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
    )

    val DEFAULT = QWEN_2_5_0_5B
}

sealed interface SlmModelState {
    data object Missing : SlmModelState
    /** [fraction] 0..1, or null while the size is not known yet; [verifying] once the bytes are in. */
    data class Downloading(val fraction: Float?, val verifying: Boolean = false) : SlmModelState
    data object Installed : SlmModelState
    data class Failed(val reason: String) : SlmModelState
}

/**
 * The assistant's model on disk. Nothing is downloaded without the listener asking (the sheet's button): it is half a
 * gigabyte. The file goes to no-backup storage (it is not the listener's data, and it can be fetched again), is written
 * to a ".part" file that a later attempt resumes, and becomes the model only once its size and SHA-256 are the ones
 * expected. The processed prompt the engine caches lives in the cache directory: the system may clear it at will.
 */
@Singleton
class SlmModelStore @Inject constructor(@ApplicationContext private val context: Context) {

    val spec: SlmModelSpec = SlmModels.DEFAULT

    private val dir: File get() = File(context.noBackupFilesDir, "slm")
    val modelFile: File get() = File(dir, spec.fileName)
    private val partFile: File get() = File(dir, spec.fileName + ".part")

    private val _state = MutableStateFlow(if (isInstalled()) SlmModelState.Installed else SlmModelState.Missing)
    val state: StateFlow<SlmModelState> = _state.asStateFlow()

    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() == spec.sizeBytes

    /** Where the engine keeps the processed fixed part of the prompt (see [SlmRequest.prefixCachePath]). */
    fun prefixCacheFile(promptKey: String): File = File(File(context.cacheDir, "slm"), "${spec.id}-$promptKey.state")

    fun delete() {
        modelFile.delete()
        partFile.delete()
        File(context.cacheDir, "slm").listFiles()?.forEach { it.delete() }
        _state.value = SlmModelState.Missing
    }

    /** Fetches the model, resuming a previous attempt; true once it is installed. Cancellable. */
    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            _state.value = SlmModelState.Installed
            return@withContext true
        }
        dir.mkdirs()
        val needed = spec.sizeBytes - partFile.length().coerceAtMost(spec.sizeBytes) + FREE_MARGIN
        if (dir.usableSpace in 1 until needed) {
            _state.value = SlmModelState.Failed("Espace insuffisant (${(needed / 1_000_000)} Mo requis)")
            return@withContext false
        }
        _state.value = SlmModelState.Downloading(fraction = partFile.length().toFloat() / spec.sizeBytes)
        val outcome = runCatching { fetch() }
        if (outcome.isFailure) {
            val e = outcome.exceptionOrNull()
            if (e is kotlinx.coroutines.CancellationException) {
                _state.value = SlmModelState.Missing
                throw e
            }
            _state.value = SlmModelState.Failed("Téléchargement interrompu")
            return@withContext false
        }
        _state.value = SlmModelState.Downloading(fraction = 1f, verifying = true)
        if (partFile.length() != spec.sizeBytes || !sha256(partFile).equals(spec.sha256, ignoreCase = true)) {
            partFile.delete()
            _state.value = SlmModelState.Failed("Fichier du modèle invalide")
            return@withContext false
        }
        if (!partFile.renameTo(modelFile)) {
            _state.value = SlmModelState.Failed("Installation impossible")
            return@withContext false
        }
        _state.value = SlmModelState.Installed
        true
    }

    private suspend fun fetch() = withContext(Dispatchers.IO) {
        var have = partFile.length()
        if (have > spec.sizeBytes) {
            partFile.delete()
            have = 0
        }
        if (have == spec.sizeBytes) return@withContext
        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = connection.responseCode
            // A server that ignores the range sends the whole file again: start over rather than append it.
            val append = have > 0 && code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) error("HTTP $code")
            if (!append) have = 0
            connection.inputStream.use { input ->
                RandomAccessFile(partFile, "rw").use { raf ->
                    raf.setLength(have)
                    raf.seek(have)
                    FileOutputStream(raf.fd).use { out ->
                        val buf = ByteArray(BUFFER)
                        var written = have
                        var lastReport = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (written - lastReport >= REPORT_STEP) {
                                lastReport = written
                                _state.value = SlmModelState.Downloading(fraction = (written.toFloat() / spec.sizeBytes).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER = 256 * 1024
        const val REPORT_STEP = 2L * 1024 * 1024
        /** Room left on the disk after the model, so the download never fills it. */
        const val FREE_MARGIN = 200L * 1024 * 1024
    }
}
