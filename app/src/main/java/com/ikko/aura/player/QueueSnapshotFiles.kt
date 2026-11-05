package com.ikko.aura.player

import com.ikko.aura.playback.QueueSnapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The queue kept in two files of its own: the items, rewritten only when the queue changes, and a few bytes of state
 * (which item, how far into it, the modes), rewritten at every tick.
 *
 * It used to be one JSON string in the settings' DataStore, written every ten seconds while music plays. A DataStore
 * rewrites its whole file at each edit, so every tick re-encoded the whole queue (a library-sized queue is hundreds of
 * kilobytes) and rewrote it, with every setting beside it, for a position that had moved by ten seconds.
 *
 * Each file is written to a temporary one and renamed over the old, so a crash mid-write leaves the previous version.
 * The state carries the number of items and a hash of their ids: read against another queue (a crash between the two
 * renames), its position is not trusted.
 */
internal class QueueSnapshotFiles(dir: File) {
    private val itemsFile = File(dir, "queue_items.json")
    private val stateFile = File(dir, "queue_state.json")
    private val lock = Mutex()

    /** The list last written to [itemsFile]: the same list again is not rewritten. */
    private var written: List<QueueSnapshot.Entry>? = null

    @Serializable
    private data class State(
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean = false,
        val repeat: Int = 0,
        val count: Int,
        val idsHash: Int,
    )

    /** How many times the items file was written, for the tests. */
    @Volatile var itemWrites: Int = 0
        private set

    fun exists(): Boolean = itemsFile.exists()

    suspend fun write(snapshot: QueueSnapshot) = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                itemsFile.parentFile?.mkdirs()
                val last = written
                if (last == null || (last !== snapshot.items && last != snapshot.items) || !itemsFile.exists()) {
                    writeAtomically(itemsFile, JSON.encodeToString(ITEMS, snapshot.items))
                    written = snapshot.items
                    itemWrites++
                }
                val state = State(
                    index = snapshot.index,
                    positionMs = snapshot.positionMs,
                    shuffle = snapshot.shuffle,
                    repeat = snapshot.repeat,
                    count = snapshot.items.size,
                    idsHash = idsHash(snapshot.items),
                )
                writeAtomically(stateFile, JSON.encodeToString(State.serializer(), state))
            }
        }
    }

    /** Null for nothing written, anything unreadable, or an empty queue: a bad snapshot is no snapshot. */
    suspend fun read(): QueueSnapshot? = lock.withLock {
        withContext(Dispatchers.IO) {
            val items = runCatching { JSON.decodeFromString(ITEMS, itemsFile.readText()) }.getOrNull()
            if (items.isNullOrEmpty()) return@withContext null
            val state = runCatching { JSON.decodeFromString(State.serializer(), stateFile.readText()) }.getOrNull()
            val matches = state != null && state.count == items.size && state.idsHash == idsHash(items)
            written = items
            QueueSnapshot(
                items = items,
                index = if (matches) state!!.index.coerceIn(0, items.lastIndex) else 0,
                positionMs = if (matches) state!!.positionMs.coerceAtLeast(0L) else 0L,
                shuffle = state?.shuffle ?: false,
                repeat = state?.repeat ?: 0,
            )
        }
    }

    private fun writeAtomically(target: File, text: String) {
        val temp = File(target.path + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            // renameTo does not replace on every filesystem: make room, then move.
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                error("could not write ${target.name}")
            }
        }
    }

    private fun idsHash(items: List<QueueSnapshot.Entry>): Int = items.fold(17) { h, e -> 31 * h + e.id.hashCode() }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val ITEMS = ListSerializer(QueueSnapshot.Entry.serializer())
    }
}
