package com.ikko.aura.data

import com.ikko.aura.data.model.AudioFormat
import java.io.File
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioPropertiesCacheTest {

    @get:Rule val folder = TemporaryFolder()

    private fun cache() = AudioPropertiesCache(File(folder.root, "props.tsv"))

    private fun entry(stamp: String, format: AudioFormat = AudioFormat.FLAC, rate: Int = 96_000, depth: Int = 24, kbps: Int = 2_300) =
        AudioPropertiesCache.Entry(stamp, TrackAudioProperties(format, rate, depth, kbps))

    private fun Map<String, AudioPropertiesCache.Entry>.flat() = mapValues { (_, e) -> e.stamp to e.properties }

    @Test
    fun `no file reads as empty`() {
        assertTrue(cache().read().isEmpty())
    }

    @Test
    fun `what is written is read back`() {
        val written = mapOf("1" to entry("100:2000"), "2" to entry("5:6", AudioFormat.MP3, 44_100, 16, 320))
        val c = cache()
        c.write(written)
        assertEquals(written.flat(), c.read().flat())
        // And no temporary file is left behind.
        assertEquals(listOf("props.tsv"), folder.root.list()!!.toList())
    }

    @Test
    fun `a file from another version of the extractor is ignored whole`() {
        File(folder.root, "props.tsv").writeText("aura-audio-properties v2\n1\ts\tFLAC\t1\t2\t3\n")
        assertTrue(cache().read().isEmpty())
    }

    @Test
    fun `broken lines are skipped and the rest is kept`() {
        val c = cache()
        c.write(mapOf("good" to entry("s")))
        val file = File(folder.root, "props.tsv")
        file.appendText("short\tline\n")
        file.appendText("x\ts\tNOT_A_FORMAT\t1\t2\t3\n")
        file.appendText("y\ts\tMP3\tnan\t?\t\n") // numbers unreadable: defaults
        file.appendText("\n")
        val read = c.read()
        assertEquals(setOf("good", "y"), read.keys)
        assertEquals(TrackAudioProperties(AudioFormat.MP3, 0, 16, 0), read.getValue("y").properties)
    }

    @Test
    fun `an id holding a tab cannot shift the columns of its line`() {
        val c = cache()
        c.write(mapOf("a\tb" to entry("s"), "ok" to entry("s")))
        // The broken line is dropped on the way back (7 fields), never misread as another track's data.
        assertEquals(setOf("ok"), c.read().keys)
    }

    @Test
    fun `a binary or truncated file reads as empty instead of throwing`() {
        File(folder.root, "props.tsv").writeBytes(ByteArray(64) { (it * 37).toByte() })
        assertTrue(cache().read().isEmpty())
        File(folder.root, "props.tsv").writeText("")
        assertTrue(cache().read().isEmpty())
    }

    @Test
    fun `writing into a directory that does not exist fails quietly`() {
        val c = AudioPropertiesCache(File(folder.root, "missing/dir/props.tsv"))
        c.write(mapOf("1" to entry("s"))) // must not throw
        assertTrue(c.read().isEmpty())
    }

    /**
     * The scan writes while the next launch (or a forced rescan) may read: the rename-over is what makes that safe. A reader
     * must always get one whole version of the cache, never half of one. (Writers are serialised by the scan itself, which
     * holds [com.ikko.aura.data.LocalMusicRepository]'s isScanning for as long as it runs; they share one temporary file.)
     */
    @Test(timeout = 30_000)
    fun `readers running beside a writer never see a torn file`() {
        val c = cache()
        val a = (0 until 300).associate { "a$it" to entry("sa$it") }
        val b = (0 until 300).associate { "b$it" to entry("sb$it", AudioFormat.AAC, 48_000, 16, 256) }
        c.write(a)
        val barrier = CyclicBarrier(4)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val writer = thread { barrier.await(); repeat(80) { c.write(if (it % 2 == 0) b else a) } }
        val readers = (0 until 3).map {
            thread {
                barrier.await()
                repeat(150) {
                    val keys = c.read().keys
                    if (keys != a.keys && keys != b.keys) failures += "torn read of ${keys.size} keys"
                }
            }
        }
        (readers + writer).forEach { it.join() }
        assertTrue(failures.joinToString(), failures.isEmpty())
        assertEquals(a.keys, c.read().keys) // the last write
    }
}
