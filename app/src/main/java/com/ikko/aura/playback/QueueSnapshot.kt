package com.ikko.aura.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The queue as it was, written down so it can come back after the process has been killed: which tracks, which one
 * was current, how far into it, and the two modes.
 *
 * Only ids and the three strings a notification needs, never an address: where an item is played from is decided
 * again when the queue comes back (see TrackResolver), and an address written down yesterday may not be good today.
 */
@Serializable
data class QueueSnapshot(
    val items: List<Entry>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    val repeat: Int = 0,
) {
    @Serializable
    data class Entry(
        val id: String,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
    )

    /**
     * What is left of the queue once [alive] has said which items still exist, or null if none does. The current item
     * stays current if it survived, with its position; if not, the next one that survived is, from the start, or the
     * last if nothing follows. The screens mirror the player's queue position for position, so the two must be
     * given the same list.
     */
    fun surviving(alive: (String) -> Boolean): QueueSnapshot? {
        val keptAt = items.indices.filter { alive(items[it].id) }
        if (keptAt.isEmpty()) return null
        val currentSurvived = index in items.indices && alive(items[index].id)
        return copy(
            items = keptAt.map { items[it] },
            index = keptAt.count { it < index }.coerceAtMost(keptAt.lastIndex),
            positionMs = if (currentSurvived) positionMs else 0L,
        )
    }

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Null for nothing written, anything unreadable, or an empty queue: a bad snapshot is no snapshot. */
        fun fromJson(text: String?): QueueSnapshot? {
            if (text.isNullOrBlank()) return null
            val read = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull() ?: return null
            if (read.items.isEmpty()) return null
            return read.copy(
                index = read.index.coerceIn(0, read.items.lastIndex),
                positionMs = read.positionMs.coerceAtLeast(0L),
            )
        }
    }
}
