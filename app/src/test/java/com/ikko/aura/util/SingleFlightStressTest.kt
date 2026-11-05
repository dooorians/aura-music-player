package com.ikko.aura.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SingleFlight] under real threads rather than the test scheduler: a list scrolled fast asks for the same covers from
 * many coroutines on [Dispatchers.Default] at once, some of them cancelled half way by rows leaving the screen.
 */
class SingleFlightStressTest {

    @Test(timeout = 60_000)
    fun `thousands of callers on many threads run each key at most once at a time and all get an answer`() = runBlocking {
        val flight = SingleFlight<Int, String>()
        val running = ConcurrentHashMap<Int, AtomicInteger>()
        val overlaps = AtomicInteger()
        val results = (0 until 4_000).map { i ->
            async(Dispatchers.Default) {
                val key = i % 25
                flight.run(key) {
                    val now = running.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
                    if (now > 1) overlaps.incrementAndGet()
                    yield()
                    running.getValue(key).decrementAndGet()
                    "v$key"
                } to key
            }
        }.awaitAll()
        assertEquals(0, overlaps.get())
        results.forEach { (value, key) -> assertEquals("v$key", value) }
        assertEquals("no key may be left in progress", 0, flight.inFlight)
    }

    @Test(timeout = 60_000)
    fun `random cancellations and failures never strand a key`() = runBlocking {
        val flight = SingleFlight<Int, Int>()
        coroutineScope {
            repeat(2_000) { i ->
                val job = launch(Dispatchers.Default) {
                    runCatching {
                        flight.run(i % 10) {
                            delay(java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 3))
                            when (i % 7) {
                                0 -> throw IllegalStateException("boom")
                                1 -> null
                                else -> i
                            }
                        }
                    }
                }
                if (i % 5 == 0) launch(Dispatchers.Default) { delay(1); job.cancel() }
            }
        }
        assertEquals(0, flight.inFlight)
        // And every key still works afterwards.
        withTimeout(5_000) { (0 until 10).forEach { k -> assertEquals(k, flight.run(k) { k }) } }
        assertEquals(0, flight.inFlight)
    }

    @Test(timeout = 60_000)
    fun `a waiter whose owner found nothing does the work itself and gets its own answer`() = runBlocking {
        val flight = SingleFlight<String, String>()
        val calls = AtomicInteger()
        val answers = (0 until 200).map { i ->
            async(Dispatchers.Default) {
                flight.run("cover") {
                    calls.incrementAndGet()
                    delay(1)
                    if (i % 2 == 0) null else "picture"
                }
            }
        }.awaitAll()
        assertTrue(answers.all { it == null || it == "picture" })
        assertTrue("work done by at least one owner", calls.get() >= 1)
        assertEquals(0, flight.inFlight)
    }
}
