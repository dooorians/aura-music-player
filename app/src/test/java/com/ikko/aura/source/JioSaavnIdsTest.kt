package com.ikko.aura.source

import com.ikko.aura.source.jiosaavn.jioSaavnAlbumId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JioSaavnIdsTest {
    @Test
    fun `never 0 and never positive, so it is neither no-album nor a MediaStore id`() {
        listOf("" to "abc", "77" to "abc", "" to "", "9" to "").forEach { (album, song) ->
            val id = jioSaavnAlbumId(album, song)
            assertTrue("$album/$song -> $id", id < 0)
        }
    }

    @Test
    fun `two songs of the same album share the album id`() {
        assertEquals(jioSaavnAlbumId("77", "a"), jioSaavnAlbumId("77", "b"))
    }

    @Test
    fun `songs without an album id each get their own, and not 0`() {
        assertNotEquals(jioSaavnAlbumId("", "a"), jioSaavnAlbumId("", "b"))
    }

    @Test
    fun `the same inputs always give the same id`() {
        assertEquals(jioSaavnAlbumId("77", "a"), jioSaavnAlbumId("77", "a"))
    }
}
