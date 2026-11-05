package com.ikko.aura.player

import com.ikko.aura.playback.QueueSnapshot
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueSnapshotFilesTest {
    private val dir: File = Files.createTempDirectory("queue").toFile()

    @After fun cleanUp() { dir.deleteRecursively() }

    private val items = (1..500).map { QueueSnapshot.Entry("id$it", "Title $it", "Artist", "Album") }

    @Test
    fun `a snapshot reads back as it was written`() = runBlocking {
        val files = QueueSnapshotFiles(dir)
        val snapshot = QueueSnapshot(items, index = 42, positionMs = 61_000, shuffle = true, repeat = 2)
        files.write(snapshot)
        assertEquals(snapshot, QueueSnapshotFiles(dir).read())
    }

    @Test
    fun `position ticks do not rewrite the queue`() = runBlocking {
        val files = QueueSnapshotFiles(dir)
        repeat(20) { tick -> files.write(QueueSnapshot(items, index = 3, positionMs = tick * 10_000L)) }
        assertEquals(1, files.itemWrites)
        assertEquals(190_000L, QueueSnapshotFiles(dir).read()?.positionMs)
        // An equal list, rebuilt as a new object, is still not rewritten.
        files.write(QueueSnapshot(items.toList(), index = 4, positionMs = 0))
        assertEquals(1, files.itemWrites)
        files.write(QueueSnapshot(items + QueueSnapshot.Entry("new"), index = 4, positionMs = 0))
        assertEquals(2, files.itemWrites)
    }

    @Test
    fun `a state written for another queue is not trusted`() = runBlocking {
        QueueSnapshotFiles(dir).write(QueueSnapshot(items, index = 400, positionMs = 30_000))
        val oldState = File(dir, "queue_state.json").readText()
        // A crash between the two renames: the items of a newer, shorter queue beside the state of the old one.
        QueueSnapshotFiles(dir).write(QueueSnapshot(items.take(10), index = 0, positionMs = 0))
        File(dir, "queue_state.json").writeText(oldState)
        val read = QueueSnapshotFiles(dir).read()!!
        assertEquals(10, read.items.size)
        assertEquals(0, read.index)
        assertEquals(0L, read.positionMs)
    }

    @Test
    fun `nothing written, an unreadable file or an empty queue is no snapshot`() = runBlocking {
        assertNull(QueueSnapshotFiles(dir).read())
        File(dir, "queue_items.json").writeText("{not json")
        assertNull(QueueSnapshotFiles(dir).read())
        File(dir, "queue_items.json").writeText("[]")
        assertNull(QueueSnapshotFiles(dir).read())
    }

    @Test
    fun `writes landing together never mix their files`() = runBlocking {
        val files = QueueSnapshotFiles(dir)
        (0 until 40).map { n ->
            async(Dispatchers.IO) { files.write(QueueSnapshot(items.take(1 + n % 7), index = 0, positionMs = n.toLong())) }
        }.awaitAll()
        val read = QueueSnapshotFiles(dir).read()!!
        // Whatever write came last, items and state belong together.
        assertEquals(read.items, items.take(read.items.size))
    }
}
