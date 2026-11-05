package com.ikko.aura.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCatalogSyncTest {
    private val day = LocalCatalogSync.LAST_SEEN_REFRESH_MS
    private val now = 10 * day

    private fun binding(item: String, key: String = item, fp: String = "f$item", av: String = "AVAILABLE", seen: Long = now) =
        TrackBindingEntity("local", item, key, fp, av, seen)

    private fun track(key: String, title: String = "t$key") = CatalogTrackEntity(key, title, "a", "b", 1000)

    @Test
    fun `an unchanged library writes nothing`() {
        val before = listOf(binding("1", seen = now - 1000), binding("2", seen = now - 1000))
        val after = listOf(binding("1"), binding("2"))
        val plan = LocalCatalogSync.plan(before, after, listOf(track("1"), track("2")), listOf(track("1"), track("2")), now)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a new, a changed and a re-linked file are the only rows written`() {
        val before = listOf(binding("1", seen = now - 1000), binding("2", seen = now - 1000), binding("3", seen = now - 1000))
        val after = listOf(
            binding("1"),
            binding("2", fp = "changed"),
            // 3 was re-indexed as 30: its binding goes, the new id keeps the key.
            binding("30", key = "3"),
            binding("4"),
        )
        val plan = LocalCatalogSync.plan(
            before, after,
            previousTracks = listOf(track("1"), track("2"), track("3")),
            nextTracks = listOf(track("1"), track("2", title = "Renamed"), track("3"), track("4")),
            now = now,
        )
        assertEquals(listOf("3"), plan.removedItemIds)
        assertEquals(listOf("2", "30", "4"), plan.bindings.map { it.itemId })
        assertEquals(listOf("2", "4"), plan.tracks.map { it.key })
    }

    @Test
    fun `a file that went missing is written once, then left alone`() {
        val before = listOf(binding("1", seen = now - 5 * day))
        val missing = listOf(binding("1", av = "MISSING", seen = now - 5 * day))
        assertEquals(1, LocalCatalogSync.plan(before, missing, emptyList(), emptyList(), now).bindings.size)
        // Next scan: still missing, its last-seen date untouched by the reconciler.
        assertTrue(LocalCatalogSync.plan(missing, missing, emptyList(), emptyList(), now + day * 3).isEmpty)
    }

    @Test
    fun `the last-seen date of a file still there is refreshed once a day`() {
        val recent = listOf(binding("1", seen = now - day + 1))
        val stale = listOf(binding("1", seen = now - day))
        val after = listOf(binding("1"))
        assertTrue(LocalCatalogSync.plan(recent, after, emptyList(), emptyList(), now).isEmpty)
        assertEquals(listOf("1"), LocalCatalogSync.plan(stale, after, emptyList(), emptyList(), now).bindings.map { it.itemId })
    }

    @Test
    fun `a catalogue row edited elsewhere is put back as the scan sees it`() {
        val before = listOf(binding("1"))
        val plan = LocalCatalogSync.plan(before, before, listOf(track("1").copy(inLibrary = false)), listOf(track("1")), now)
        assertEquals(listOf(track("1")), plan.tracks)
    }
}
