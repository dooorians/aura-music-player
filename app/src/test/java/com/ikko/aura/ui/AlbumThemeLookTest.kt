package com.ikko.aura.ui

import com.ikko.aura.ui.components.sameLook
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumThemeLookTest {
    private val coverA = Any()
    private val coverB = Any()

    @Test
    fun `two tracks of one album share the cover instance and the seed, so same look`() {
        assertTrue(sameLook(coverA, 7, coverA, 7))
    }

    @Test
    fun `another album is another look`() {
        assertFalse(sameLook(coverA, 7, coverB, 9))
    }

    @Test
    fun `same cover with another seed is another look`() {
        assertFalse(sameLook(coverA, 7, coverA, 8))
    }

    @Test
    fun `a theme with no cover is never taken for another`() {
        assertFalse(sameLook(null, 0, null, 0))
    }
}
