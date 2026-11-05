package com.ikko.aura.util

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AbortableIoTest {

    @Test
    fun `a timeout aborts the blocking call instead of waiting for it`() = runBlocking {
        val released = CountDownLatch(1)
        var aborted = false
        val startedAt = System.nanoTime()
        val result = withTimeoutOrNull(200) {
            abortableBlocking(abort = { aborted = true; released.countDown() }) {
                // Stands for a socket read with a long timeout: it only returns once the connection is closed.
                if (!released.await(30, TimeUnit.SECONDS)) error("never aborted")
                throw IOException("socket closed")
            }
        }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        assertNull(result)
        assertTrue(aborted)
        assertTrue("took $tookMs ms", tookMs < 5_000)
    }

    @Test
    fun `a result comes back as it is, without an abort`() = runBlocking {
        var aborted = false
        assertEquals(42, abortableBlocking(abort = { aborted = true }) { 42 })
        assertTrue(!aborted)
    }

    @Test
    fun `the call's own failure reaches the caller unchanged`() = runBlocking {
        try {
            abortableBlocking(abort = {}) { throw IOException("refused") }
            fail("expected the failure")
        } catch (e: IOException) {
            assertEquals("refused", e.message)
        }
    }
}
