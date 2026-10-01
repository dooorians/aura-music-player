package com.ikko.aura.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueSnapshotTest {

    private fun snap(index: Int, position: Long = 5_000L, vararg ids: String) =
        QueueSnapshot(ids.map { QueueSnapshot.Entry(it, "t$it", "a$it", "b$it") }, index, position, shuffle = true, repeat = 2)

    private val abcde = snap(2, 7_000L, "a", "b", "c", "d", "e")

    @Test
    fun `it survives a round trip through json`() {
        assertEquals(abcde, QueueSnapshot.fromJson(abcde.toJson()))
    }

    @Test
    fun `nothing written, garbage and an empty queue are all no snapshot`() {
        assertNull(QueueSnapshot.fromJson(null))
        assertNull(QueueSnapshot.fromJson(""))
        assertNull(QueueSnapshot.fromJson("not json {"))
        assertNull(QueueSnapshot.fromJson(QueueSnapshot(emptyList(), 0, 0).toJson()))
    }

    @Test
    fun `an index or position out of range is brought back into it`() {
        val read = QueueSnapshot.fromJson(snap(99, -5L, "a", "b").toJson())!!
        assertEquals(1, read.index)
        assertEquals(0L, read.positionMs)
    }

    @Test
    fun `a field it does not know is ignored, so a later version can add one`() {
        val json = """{"items":[{"id":"1","extra":"x"}],"index":0,"positionMs":3,"somethingNew":true}"""
        assertEquals("1", QueueSnapshot.fromJson(json)!!.items.single().id)
    }

    @Test
    fun `with everything alive nothing changes`() {
        assertEquals(abcde, abcde.surviving { true })
    }

    @Test
    fun `items before the current one that are gone move the index down`() {
        val s = abcde.surviving { it != "a" && it != "b" }!!
        assertEquals(listOf("c", "d", "e"), s.items.map { it.id })
        assertEquals(0, s.index)
        assertEquals(7_000L, s.positionMs)
    }

    @Test
    fun `when the current item is gone the next survivor takes over, from the start`() {
        val s = abcde.surviving { it != "c" }!!
        assertEquals(listOf("a", "b", "d", "e"), s.items.map { it.id })
        assertEquals("d", s.items[s.index].id)
        assertEquals(0L, s.positionMs)
    }

    @Test
    fun `when the current item is gone and nothing follows the last survivor takes over`() {
        val s = snap(2, 7_000L, "a", "b", "c").surviving { it != "c" }!!
        assertEquals("b", s.items[s.index].id)
        assertEquals(0L, s.positionMs)
    }

    @Test
    fun `when nothing survives there is no queue`() {
        assertNull(abcde.surviving { false })
    }

    @Test
    fun `the modes are kept`() {
        val s = abcde.surviving { it != "a" }!!
        assertEquals(true, s.shuffle)
        assertEquals(2, s.repeat)
    }
}
