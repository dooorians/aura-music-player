package com.ikko.aura.util

import com.ikko.aura.util.AlbumCovers.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumCoversTest {

    private fun e(album: Long, number: Int, id: Long) = Entry(album, number, id, "content://media/external/audio/media/$id")

    @Test
    fun `the cover of an album comes from its opening track whatever the order the files came in`() {
        val shuffled = listOf(e(1, 3, 30), e(1, 1, 10), e(1, 2, 20))
        assertEquals(
            listOf("content://media/external/audio/media/10", "content://media/external/audio/media/20", "content://media/external/audio/media/30"),
            AlbumCovers.order(shuffled)["1"],
        )
        assertEquals(AlbumCovers.order(shuffled)["1"], AlbumCovers.order(shuffled.reversed())["1"])
    }

    @Test
    fun `tracks without a number come after the numbered ones and are ordered by id`() {
        val order = AlbumCovers.order(listOf(e(1, 0, 5), e(1, 2, 90), e(1, 0, 3))).getValue("1")
        assertEquals(listOf(90L, 3L, 5L), order.map { it.substringAfterLast('/').toLong() })
    }

    @Test
    fun `two tracks of the same number take the lower id first`() {
        val order = AlbumCovers.order(listOf(e(1, 1, 700), e(1, 1, 400))).getValue("1")
        assertEquals(listOf(400L, 700L), order.map { it.substringAfterLast('/').toLong() })
    }

    @Test
    fun `albums are kept apart and an unknown key has no candidates`() {
        val order = AlbumCovers.order(listOf(e(1, 1, 10), e(2, 1, 20)))
        assertEquals(1, order.getValue("1").size)
        assertEquals(1, order.getValue("2").size)
        assertNull(order["3"])
    }

    @Test
    fun `publishing replaces the previous scan`() {
        AlbumCovers.publish(listOf(e(7, 1, 1)))
        assertEquals(1, AlbumCovers.candidates("7")?.size)
        AlbumCovers.publish(listOf(e(8, 1, 2)))
        assertNull(AlbumCovers.candidates("7"))
        AlbumCovers.publish(emptyList())
    }

    @Test
    fun `the cover is the picture most sampled files share, not the opening track's single`() {
        val looks = listOf("a" to 0x0F0F0F0F0F0F0F0FL, "b" to 0x00FF00FF00FF00FFL, "c" to 0x00FF00FF00FF00FFL)
        assertEquals("b", AlbumCovers.vote(looks))
    }

    @Test
    fun `a tie goes to the earliest file of the album`() {
        val looks = listOf("a" to 0x0F0F0F0F0F0F0F0FL, "b" to 0x00FF00FF00FF00FFL)
        assertEquals("a", AlbumCovers.vote(looks))
    }

    @Test
    fun `pictures a few bits apart are one picture and files without one are ignored`() {
        val looks = listOf("a" to null, "b" to 0x00FF00FF00FF00FFL, "c" to 0x00FF00FF00FF00FEL, "d" to 0x0F0F0F0F0F0F0F0FL)
        assertEquals("b", AlbumCovers.vote(looks))
        assertNull(AlbumCovers.vote(listOf("a" to null, "b" to null)))
        assertNull(AlbumCovers.vote(emptyList()))
    }
}
