package com.ikko.aura.data.home

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningAlbumsTest {
    private fun row(track: String, album: String, outcome: String = "COMPLETED", at: Long = 1_000L, year: Int = 2020) =
        ListenRow(track, "t", "a", album, year, outcome, at)

    private val resolve: (ListenRow) -> SectionAlbum? = { r -> if (r.album.isBlank()) null else SectionAlbum(r.album, "a", ref = r.trackId.takeIf { it.startsWith("ytm:") }) }

    @Test fun `listens of the device and of the sources are ranked together, skips do not count`() {
        val rows = listOf(
            row("1", "Mine"), row("2", "Mine"), row("3", "Mine", "SKIPPED"),
            row("ytm:a", "Online"), row("ytm:b", "Online"), row("ytm:c", "Online"), row("x", ""),
        )
        val out = ListeningAlbums.group(rows, ZoneId.of("UTC"), resolve)
        assertEquals(listOf("Online", "Mine"), out.map { it.album.title })
        assertEquals(listOf(3, 2), out.map { it.count })
    }

    @Test fun `the loop only counts what was listened to lately`() {
        val rows = listOf(row("1", "Old", at = 10), row("2", "New", at = 5_000), row("3", "New", at = 6_000))
        val out = ListeningAlbums.group(ListeningAlbums.since(rows, 1_000), ZoneId.of("UTC"), resolve)
        assertEquals(listOf("New"), out.map { it.album.title })
    }
}
