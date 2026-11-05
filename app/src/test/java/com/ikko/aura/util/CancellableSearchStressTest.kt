package com.ikko.aura.util

import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Instant search, as the search screen does it: every keystroke cancels the request before it and starts its own, on
 * [Dispatchers.IO], wrapped in [runCatchingCancellable]. Whatever order the answers come back in, only the last query
 * may write the results.
 *
 * The searches are launched on runBlocking's single thread, which stands for the main thread of the view model: the
 * cancel and the write after a request then never run at the same time. (On a pool of threads a cancel can land after
 * the last suspension point and the write still happens: there, a check of the query is needed as well.)
 */
class CancellableSearchStressTest {

    private suspend fun fakeRequest(q: String): List<String> = withContext(Dispatchers.IO) {
        // Earlier queries are slower, so their answers land after the newer ones unless cancellation stops them.
        Thread.sleep((30 - q.length).coerceAtLeast(0).toLong())
        if (q.length % 4 == 0) throw IOException("timeout for $q")
        listOf("$q-1", "$q-2")
    }

    @Test(timeout = 60_000)
    fun `a burst of keystrokes always ends on the last query`() = runBlocking {
        repeat(20) { round ->
            val shown = MutableStateFlow<Pair<String, Any>?>(null)
            var job: Job? = null
            val word = "aurora borealis $round"
            for (end in 1..word.length) {
                val q = word.take(end)
                job?.cancel()
                job = launch {
                    val r = runCatchingCancellable { fakeRequest(q) }
                    shown.value = q to (r.getOrNull() ?: "error")
                }
                delay(1)
            }
            job!!.join()
            assertEquals("round $round", word, shown.value?.first)
        }
    }

    @Test(timeout = 30_000)
    fun `a search cancelled while its request is still on the wire writes nothing`() = runBlocking {
        val shown = MutableStateFlow("untouched")
        val job = launch {
            val r = runCatchingCancellable { withContext(Dispatchers.IO) { Thread.sleep(50); "late" } }
            shown.value = r.getOrDefault("error")
        }
        delay(10)
        job.cancelAndJoin()
        assertEquals("untouched", shown.value)
    }

    /** What the helper did before: a failure was a failure, whether the caller was still there or not. */
    private inline fun <T> previousRunCatchingCancellable(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** A request that is cancelled, then fails (a timeout): `withContext` throws the IOException, not a cancellation. */
    private suspend fun cancelledThenFailing(wrap: suspend (suspend () -> String) -> Result<String>): String = coroutineScopeOf { scope ->
        val shown = MutableStateFlow("untouched")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val job = scope.launch {
            val r = wrap { withContext(Dispatchers.IO) { started.countDown(); release.await(); throw IOException("timeout") } }
            shown.value = r.fold({ "ok" }, { "error of the old query" })
        }
        withContext(Dispatchers.IO) { started.await() }
        job.cancel()
        release.countDown()
        job.join()
        shown.value
    }

    private suspend fun <R> coroutineScopeOf(block: suspend (kotlinx.coroutines.CoroutineScope) -> R): R =
        kotlinx.coroutines.coroutineScope { block(this) }

    @Test(timeout = 30_000)
    fun `a search cancelled and then failing does not write its error over the new one`() = runBlocking {
        assertEquals("untouched", cancelledThenFailing { request -> runCatchingCancellable { request() } })
    }

    @Test(timeout = 30_000)
    fun `with the previous helper the cancelled search wrote its error (the bug)`() = runBlocking {
        assertEquals("error of the old query", cancelledThenFailing { request -> previousRunCatchingCancellable { request() } })
    }
}
