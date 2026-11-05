package com.ikko.aura.data.home

import com.ikko.aura.reco.DayPart
import java.time.ZoneId

/** One listen of the history, as the rows of Home need it: what was left, and how. */
data class ListenRow(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val year: Int,
    val outcome: String,
    val endedAtMs: Long,
)

/** An album as listened to: the device's own and those of the sources count the same. */
class AlbumListen(
    val album: SectionAlbum,
    val count: Int,
    val lastAtMs: Long,
    /** Listens by part of the day: morning, afternoon, evening, night. */
    val parts: IntArray,
    /** The release year most of its listens named, 0 when none. */
    val year: Int,
)

object ListeningAlbums {
    private val COUNTED = setOf("COMPLETED", "PARTIAL")

    /**
     * The history grouped by album, most listened first. Only what was really listened to counts (not what was skipped), a library
     * album and an album of a source weigh the same, and a song with no album [resolve] can place is left out.
     */
    fun group(rows: List<ListenRow>, zone: ZoneId = ZoneId.systemDefault(), resolve: (ListenRow) -> SectionAlbum?): List<AlbumListen> {
        class Acc(val album: SectionAlbum) {
            var count = 0; var last = 0L; val parts = IntArray(4); val years = HashMap<Int, Int>()
        }
        val byKey = LinkedHashMap<String, Acc>()
        for (r in rows) {
            if (r.outcome !in COUNTED) continue
            val album = resolve(r) ?: continue
            val acc = byKey.getOrPut(album.sameAs) { Acc(album) }
            acc.count++
            acc.last = maxOf(acc.last, r.endedAtMs)
            acc.parts[DayPart.at(r.endedAtMs, zone).ordinal]++
            if (r.year in 1900..2100) acc.years.merge(r.year, 1, Int::plus)
        }
        return byKey.values.map { AlbumListen(it.album, it.count, it.last, it.parts, it.years.maxByOrNull { y -> y.value }?.key ?: 0) }
            .sortedWith(compareByDescending<AlbumListen> { it.count }.thenByDescending { it.lastAtMs })
    }

    /** Only what was listened to since [sinceMs]: what is "looped" now, not what was a year ago. */
    fun since(rows: List<ListenRow>, sinceMs: Long) = rows.filter { it.endedAtMs >= sinceMs }
}
