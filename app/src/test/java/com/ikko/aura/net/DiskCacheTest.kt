package com.ikko.aura.net

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiskCacheTest {
    private val dir = Files.createTempDirectory("diskcache").toFile().apply { deleteOnExit() }
    private var now = 1_700_000_000_000L

    @Test fun `keeps a body with the time it was saved`() {
        val cache = DiskCache(dir, now = { now })
        cache.put("https://a/1", "hello\nworld")
        val entry = cache.get("https://a/1")
        assertEquals("hello\nworld", entry?.body)
        assertEquals(now, entry?.savedAt)
        assertNull(cache.get("https://a/2"))
    }

    @Test fun `drops the least recently used past its size`() {
        val cache = DiskCache(dir, maxBytes = 250, now = { now })
        val body = "x".repeat(100)
        cache.put("a", body)
        now += 10_000; cache.put("b", body)
        now += 10_000; cache.get("a") // read again: now the most recent
        now += 10_000; cache.put("c", body)
        assertNull(cache.get("b"))
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("c"))
    }
}
