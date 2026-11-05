package com.ikko.aura.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSummariesTest {
    private fun row(id: Long, name: String, vararg entries: Pair<String, Int>) = PlaylistWithEntries(
        PlaylistEntity(id, name, createdAtMs = id),
        entries.map { (track, position) -> PlaylistTrackEntity(id, track, position, 0L) },
    )

    @Test
    fun `entries come out in their order, whatever order the relation loaded them in`() {
        val summary = summariesOf(listOf(row(5, "Mix", "c" to 2, "a" to 0, "b" to 1))).single()
        assertEquals(listOf("a", "b", "c"), summary.trackIds)
        assertEquals(3, summary.trackCount)
    }

    @Test
    fun `Favoris comes first and its old duplicates are left out, the rest keeps the query's order`() {
        val rows = listOf(
            row(9, "Newest"),
            row(8, "Tes préférés", "x" to 0),
            row(7, "favoris"),
            row(FAVORITES_PLAYLIST_ID, FAVORITES_PLAYLIST_NAME, "h" to -1),
            row(3, "Oldest"),
        )
        assertEquals(listOf(FAVORITES_PLAYLIST_ID, 9L, 3L), summariesOf(rows).map { it.id })
    }

    @Test
    fun `a playlist with no entries is listed empty`() {
        val summary = summariesOf(listOf(row(1, "Empty"))).single()
        assertEquals(0, summary.trackCount)
        assertEquals(emptyList<String>(), summary.trackIds)
    }
}
