package com.ikko.aura.data.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lists of strings a web service answered (an artist's tags, its neighbours…), kept [ttlMs] in [RemoteCacheDao] under
 * [namespace]. An empty list is a remembered "it does not know", kept like any other answer.
 *
 * Last.fm's answers used to be one key each in a SharedPreferences file. That file only grows (one key per artist, and
 * one per recommended song for its tags), is held whole in memory, and is rewritten whole by every `apply()` — hundreds
 * of times during one generation of the recommendations, each write of the entire file, and each one something a
 * pausing activity waits on. A row per answer is written alone and read by its key.
 *
 * On first use the old file's entries are moved over in one batch ([Legacy]) and the expired rows are pruned.
 */
class InsightCache(
    private val dao: RemoteCacheDao,
    private val namespace: String,
    private val ttlMs: Long,
    private val legacy: Legacy? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The store the cache used to be: raw entries "fetchedAt\tvalue", read once and then emptied. */
    interface Legacy {
        fun entries(): Map<String, String>
        fun clear()
    }

    private val setup = Mutex()
    @Volatile private var ready = false

    /** The answer kept for [key] if it is still fresh, else null. */
    suspend fun get(key: String): List<String>? {
        ensureReady()
        val row = runCatching { dao.get(namespace + key) }.getOrNull() ?: return null
        if (clock() - row.fetchedAtMs >= ttlMs) return null
        return decode(row.value)
    }

    suspend fun put(key: String, values: List<String>) {
        ensureReady()
        runCatching { dao.put(RemoteCacheEntity(namespace + key, encode(values), clock())) }
    }

    private suspend fun ensureReady() {
        if (ready) return
        setup.withLock {
            if (ready) return
            // Off the caller's thread: the old file is read from disk on first access.
            withContext(Dispatchers.IO) { runCatching {
                legacy?.let { old ->
                    val rows = old.entries().mapNotNull { (key, raw) ->
                        val at = raw.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
                        RemoteCacheEntity(namespace + key, raw.substringAfter('\t', ""), at)
                    }
                    rows.chunked(MIGRATION_BATCH).forEach { dao.putAll(it) }
                    old.clear()
                }
                dao.prune(namespace, clock() - ttlMs)
            } }
            ready = true
        }
    }

    companion object {
        private const val SEPARATOR = '\u0001'
        private const val MIGRATION_BATCH = 500

        fun encode(values: List<String>): String = values.joinToString(SEPARATOR.toString())

        fun decode(raw: String): List<String> = raw.split(SEPARATOR).filter { it.isNotEmpty() }
    }
}
