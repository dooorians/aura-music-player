package com.ikko.aura.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReconcilerTest {

    private fun file(id: String, fp: String = "f$id") = ScannedFile(id, fp, "t$id", "a", "al", 1000)
    private fun bound(id: String, key: String = id, fp: String = "f$id", a: Availability = Availability.AVAILABLE) =
        LocalBinding(id, key, fp, a, 1L)

    @Test fun firstScanUsesMediaStoreIdsAsKeys() {
        val r = LocalReconciler.reconcile(emptyList(), listOf(file("7"), file("9")), 5L)
        assertEquals(mapOf("7" to "7", "9" to "9"), r.keyByItem)
        assertTrue(r.bindings.all { it.availability == Availability.AVAILABLE && it.lastSeenAt == 5L })
    }

    @Test fun keptFileKeepsItsKey() {
        val r = LocalReconciler.reconcile(listOf(bound("7", key = "3")), listOf(file("7")), 5L)
        assertEquals("3", r.keyByItem["7"])
    }

    @Test fun reindexedFileTakesTheOldKey() {
        val r = LocalReconciler.reconcile(listOf(bound("7", fp = "same")), listOf(file("40", fp = "same")), 5L)
        assertEquals(mapOf("40" to "7"), r.keyByItem)
        assertEquals(1, r.bindings.size) // the dead id is not kept next to it
    }

    @Test fun ambiguousFingerprintDoesNotRebind() {
        val prev = listOf(bound("7", fp = "same"), bound("8", fp = "same"))
        val r = LocalReconciler.reconcile(prev, listOf(file("40", fp = "same")), 5L)
        assertEquals("40", r.keyByItem["40"])
        assertEquals(setOf("7", "8"), r.bindings.filter { it.availability == Availability.MISSING }.map { it.itemId }.toSet())
    }

    @Test fun twoArrivalsWithTheSameFingerprintDoNotRebind() {
        val r = LocalReconciler.reconcile(listOf(bound("7", fp = "same")), listOf(file("40", "same"), file("41", "same")), 5L)
        assertEquals("40", r.keyByItem["40"]); assertEquals("41", r.keyByItem["41"])
    }

    @Test fun vanishedFileIsMarkedNotDeleted() {
        val r = LocalReconciler.reconcile(listOf(bound("7"), bound("8")), listOf(file("8")), 5L)
        val seven = r.bindings.single { it.itemId == "7" }
        assertEquals(Availability.MISSING, seven.availability)
        assertEquals(1L, seven.lastSeenAt)
    }

    @Test fun returnedFileIsAvailableAgain() {
        val r = LocalReconciler.reconcile(listOf(bound("7", a = Availability.MISSING)), listOf(file("7")), 9L)
        assertEquals(Availability.AVAILABLE, r.bindings.single().availability)
    }

    @Test fun emptyScanChangesNothing() {
        val prev = listOf(bound("7"))
        assertEquals(prev, LocalReconciler.reconcile(prev, emptyList(), 5L).bindings)
    }

    @Test fun keyHeldByARelinkedFileIsNotReused() {
        // item 40 now holds key "7"; a file that later gets id 7 back (it cannot, but the guard is cheap) gets l_7.
        val r = LocalReconciler.reconcile(listOf(bound("40", key = "7")), listOf(file("40"), file("7")), 5L)
        assertEquals("7", r.keyByItem["40"]); assertEquals("l_7", r.keyByItem["7"])
    }
}
