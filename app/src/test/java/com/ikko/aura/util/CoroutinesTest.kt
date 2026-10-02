package com.ikko.aura.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutinesTest {

    /** The search's own shape: a job per query, cancelled by the next one, writing the state when its request returns. */
    private class Search {
        val state = MutableStateFlow("")
        private var job: Job? = null

        fun CoroutineScopeLike.query(q: String, takes: Long, cancellable: Boolean) {
            job?.cancel()
            job = scope.launch {
                val result = if (cancellable) {
                    runCatchingCancellable { withContext(Dispatchers.IO) { Thread.sleep(takes); "results for $q" } }
                } else {
                    runCatching { withContext(Dispatchers.IO) { Thread.sleep(takes); "results for $q" } }
                }
                state.value = result.getOrElse { "error" }
            }
        }
    }

    private class CoroutineScopeLike(val scope: kotlinx.coroutines.CoroutineScope)

    @Test
    fun `search A slow then search B fast ends on B`() = runBlocking {
        val search = Search()
        with(search) {
            val scope = CoroutineScopeLike(this@runBlocking)
            scope.query("A", takes = 400, cancellable = true)
            delay(50)
            scope.query("B", takes = 20, cancellable = true)
            delay(700) // A's request has long since returned
        }
        assertEquals("results for B", search.state.value)
    }

    @Test
    fun `with plain runCatching the cancelled search A overwrites B (the bug)`() = runBlocking {
        val search = Search()
        with(search) {
            val scope = CoroutineScopeLike(this@runBlocking)
            scope.query("A", takes = 400, cancellable = false)
            delay(50)
            scope.query("B", takes = 20, cancellable = false)
            delay(700)
        }
        assertEquals("error", search.state.value)
    }

    @Test
    fun `a real failure is still returned as a failure`() = runBlocking {
        val result = runCatchingCancellable<String> { throw java.io.IOException("offline") }
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun `cancellation is rethrown, not returned`() = runBlocking {
        var reached = false
        val job = launch {
            runCatchingCancellable { withContext(Dispatchers.IO) { Thread.sleep(200) } }
            reached = true
        }
        delay(30)
        job.cancelAndJoin()
        assertEquals(false, reached)
    }
}
