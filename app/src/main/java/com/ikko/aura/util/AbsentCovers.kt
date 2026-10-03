package com.ikko.aura.util

/**
 * The files whose cover was looked for and not found, for the length of the session.
 *
 * Without it a track with no picture was asked for again every time its row came back on screen, a 30 to 65 ms
 * failed decode each time that also held one of the two decode slots. Cleared whenever a library scan succeeds, so a
 * file that has been given a cover since is looked at again.
 *
 * Keyed by the file, not by the album: tracks of one album share a cover in the cache, and one without a picture
 * must not hide another's that has one.
 */
object AbsentCovers {
    private val files = BoundedKeySet(MAX)

    operator fun contains(file: String): Boolean = files.contains(file)

    fun add(file: String) = files.add(file)

    fun clear() = files.clear()

    private const val MAX = 4096
}

/** A set that forgets the key it has gone longest without being asked about once it holds more than [max]. */
internal class BoundedKeySet(private val max: Int) {
    private val keys = object : LinkedHashMap<String, Unit>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > max
    }

    @Synchronized fun contains(key: String): Boolean = keys[key] != null

    @Synchronized fun add(key: String) { keys[key] = Unit }

    @Synchronized fun clear() = keys.clear()

    val size: Int @Synchronized get() = keys.size
}
