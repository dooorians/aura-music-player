package com.ikko.aura.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stamps are MediaStore's "dateModified:size". */
class ChangedSinceLastScanTest {

    @Test
    fun `an unchanged library has nothing to read again`() {
        val stamps = mapOf("1" to "100:2048", "2" to "200:4096")
        assertEquals(emptySet<String>(), changedSinceLastScan(stamps, stamps))
    }

    @Test
    fun `a new modification date marks the file`() {
        assertEquals(setOf("1"), changedSinceLastScan(mapOf("1" to "100:2048"), mapOf("1" to "150:2048")))
    }

    @Test
    fun `a new size alone marks the file`() {
        assertEquals(setOf("1"), changedSinceLastScan(mapOf("1" to "100:2048"), mapOf("1" to "100:3000")))
    }

    @Test
    fun `new and removed files are not changed files`() {
        val previous = mapOf("gone" to "1:1", "same" to "2:2")
        val current = mapOf("same" to "2:2", "new" to "3:3")
        assertEquals(emptySet<String>(), changedSinceLastScan(previous, current))
    }
}
