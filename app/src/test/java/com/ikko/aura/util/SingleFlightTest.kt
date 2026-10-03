package com.ikko.aura.util

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightTest {

    @Test
    fun `twenty callers of one key run the work once and all get its result`() = runTest {
        val flight = SingleFlight<String, String>()
        val runs = AtomicInteger()
        val results = (1..20).map {
            async { flight.run("album") { runs.incrementAndGet(); delay(50); "cover" } }
        }.awaitAll()
        assertEquals(1, runs.get())
        assertTrue(results.all { it == "cover" })
        assertEquals(0, flight.inFlight)
    }

    @Test
    fun `different keys do not wait for each other`() = runTest {
        val flight = SingleFlight<String, String>()
        val runs = AtomicInteger()
        val results = listOf("a", "b", "c").map { k ->
            async { flight.run(k) { runs.incrementAndGet(); delay(10); k } }
        }.awaitAll()
        assertEquals(3, runs.get())
        assertEquals(listOf("a", "b", "c"), results)
        assertEquals(0, flight.inFlight)
    }

    @Test
    fun `an owner that finds nothing leaves each waiter to try for itself`() = runTest {
        // Two tracks of one album, the first without a picture: the second must not be told "none".
        val flight = SingleFlight<String, String>()
        val first = async { flight.run("album") { delay(20); null } }
        val second = async { flight.run("album") { "cover of the second track" } }
        assertNull(first.await())
        assertEquals("cover of the second track", second.await())
        assertEquals(0, flight.inFlight)
    }

    @Test
    fun `a cancelled owner does not strand its waiters nor leave the key in progress`() = runTest {
        val flight = SingleFlight<String, String>()
        val started = CompletableDeferred<Unit>()
        val owner = launch {
            flight.run("album") { started.complete(Unit); delay(10_000); "never" }
        }
        started.await()
        assertEquals(1, flight.inFlight)
        val waiter = async { flight.run("album") { "by the waiter" } }
        delay(1)
        owner.cancelAndJoin()
        assertEquals("by the waiter", waiter.await())
        assertEquals(0, flight.inFlight)
    }

    @Test
    fun `a cancelled waiter does not disturb the owner`() = runTest {
        val flight = SingleFlight<String, String>()
        val gate = CompletableDeferred<Unit>()
        val owner = async { flight.run("album") { gate.await(); "cover" } }
        delay(1)
        val waiter = launch { flight.run("album") { "unused" } }
        delay(1)
        waiter.cancelAndJoin()
        gate.complete(Unit)
        assertEquals("cover", owner.await())
        assertEquals(0, flight.inFlight)
    }

    @Test
    fun `an owner that throws frees the key and the waiter does the work`() = runTest {
        val flight = SingleFlight<String, String>()
        val gate = CompletableDeferred<Unit>()
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { flight.run("album") { gate.await(); error("provider died") } }
        }
        val waiter = async { flight.run("album") { "recovered" } }
        delay(1)
        gate.complete(Unit)
        assertTrue(owner.await().isFailure)
        assertEquals("recovered", waiter.await())
        assertEquals(0, flight.inFlight)
    }

    @Test
    fun `after a result the key is free for a new run`() = runTest {
        val flight = SingleFlight<String, Int>()
        val runs = AtomicInteger()
        assertEquals(1, flight.run("k") { runs.incrementAndGet() })
        assertEquals(2, flight.run("k") { runs.incrementAndGet() })
        assertEquals(0, flight.inFlight)
    }
}
