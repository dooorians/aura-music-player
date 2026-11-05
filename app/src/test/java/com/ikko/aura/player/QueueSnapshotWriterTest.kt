package com.ikko.aura.player

import com.ikko.aura.playback.QueueSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueSnapshotWriterTest {

    private val entries = listOf("a", "b", "c").map { QueueSnapshot.Entry(it, "t$it", "a$it", "b$it") }

    @Test
    fun `an empty player writes nothing, so the saved queue is not erased at start-up`() {
        assertNull(QueueSnapshotWriter.snapshotOf(emptyList(), 0, 0, 0L, false, 0))
    }

    @Test
    fun `items not yet rebuilt for the player's queue are not written`() {
        assertNull(QueueSnapshotWriter.snapshotOf(entries, 4, 1, 1_000L, false, 0))
    }

    @Test
    fun `the state is written as the player reports it`() {
        val snap = QueueSnapshotWriter.snapshotOf(entries, 3, 1, 42_000L, true, 2)!!
        assertEquals(entries, snap.items)
        assertEquals(1, snap.index)
        assertEquals(42_000L, snap.positionMs)
        assertEquals(true, snap.shuffle)
        assertEquals(2, snap.repeat)
    }

    @Test
    fun `an index or position the player reports while between items is brought into range`() {
        val unset = QueueSnapshotWriter.snapshotOf(entries, 3, -1, -9L, false, 0)!!
        assertEquals(0, unset.index)
        assertEquals(0L, unset.positionMs)
        assertEquals(2, QueueSnapshotWriter.snapshotOf(entries, 3, 7, 0L, false, 0)!!.index)
    }

    @Test
    fun `what is written comes back after the process is killed, at the same track and position`() {
        val written = QueueSnapshotWriter.snapshotOf(entries, 3, 2, 93_500L, false, 1)!!
        val restored = QueueSnapshot.fromJson(written.toJson())!!
        assertEquals("c", restored.items[restored.index].id)
        assertEquals(93_500L, restored.positionMs)
    }

    @Test
    fun `a current track deleted while the app was dead resumes the next one from its start`() {
        val written = QueueSnapshotWriter.snapshotOf(entries, 3, 1, 60_000L, false, 0)!!
        val restored = QueueSnapshot.fromJson(written.toJson())!!.surviving { it != "b" }!!
        assertEquals("c", restored.items[restored.index].id)
        assertEquals(0L, restored.positionMs)
    }

    @Test
    fun `a queue whose every file is gone restores nothing`() {
        val written = QueueSnapshotWriter.snapshotOf(entries, 3, 0, 1L, false, 0)!!
        assertNull(QueueSnapshot.fromJson(written.toJson())!!.surviving { false })
    }
}
