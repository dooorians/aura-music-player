package com.ikko.aura.util

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedTest {

    @Test
    fun `results keep the input's order whatever order the work finishes in`() = runTest {
        val out = (1..20).toList().mapBounded(4) { n -> delay((20 - n) * 10L); n * 2 }
        assertEquals((1..20).map { it * 2 }, out)
    }

    @Test
    fun `no more than the limit run at once`() = runTest {
        val running = AtomicInteger()
        var peak = 0
        (1..30).toList().mapBounded(3) {
            val now = running.incrementAndGet()
            synchronized(this@BoundedTest) { peak = maxOf(peak, now) }
            delay(10)
            running.decrementAndGet()
        }
        assertEquals(3, peak)
    }

    @Test
    fun `work overlaps up to the limit`() = runTest {
        val start = testScheduler.currentTime
        (1..9).toList().mapBounded(3) { delay(100) }
        assertEquals(300, testScheduler.currentTime - start)
    }

    @Test
    fun `a failure is rethrown`() = runBlocking {
        try {
            listOf(1, 2, 3).mapBounded(2) { if (it == 2) throw IllegalStateException("boom") else it }
            fail("expected the failure")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun `an empty or single list needs no coroutine`() = runBlocking {
        assertTrue(emptyList<Int>().mapBounded(4) { it }.isEmpty())
        assertEquals(listOf(2), listOf(1).mapBounded(4) { it * 2 })
    }
}
