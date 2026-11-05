package com.ikko.aura.net

import java.io.File
import java.security.MessageDigest

/**
 * Bodies of past answers, one small file each, kept across launches: what lets the library's lyrics, artist facts and covers
 * come back at once — and in airplane mode at all — instead of asking the network again. Least recently used goes first past
 * [maxBytes]. A file that cannot be read or written is a miss, never an error: this is a cache.
 */
class DiskCache(private val dir: File, private val maxBytes: Long = 16L * 1024 * 1024, private val now: () -> Long = System::currentTimeMillis) {

    class Entry(val body: String, val savedAt: Long)

    fun get(key: String): Entry? = runCatching {
        val file = fileFor(key)
        if (!file.exists()) return null
        val text = file.readText()
        val cut = text.indexOf('\n')
        if (cut < 0) return null
        val savedAt = text.substring(0, cut).toLongOrNull() ?: return null
        file.setLastModified(now())
        Entry(text.substring(cut + 1), savedAt)
    }.getOrNull()

    fun put(key: String, body: String) {
        runCatching {
            dir.mkdirs()
            val file = fileFor(key)
            val part = File(dir, file.name + ".part")
            part.writeText("${now()}\n$body")
            if (!part.renameTo(file)) { file.delete(); part.renameTo(file) }
            file.setLastModified(now())
            trim()
        }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun fileFor(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(dir, hash)
    }
}
