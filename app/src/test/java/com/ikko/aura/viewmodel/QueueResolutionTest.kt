package com.ikko.aura.viewmodel

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QueueResolutionTest {

    private suspend fun resolve(ids: List<String>, streamed: List<String>, local: List<String>, onLocal: () -> Unit = {}) =
        resolveQueueEntries(ids, idOf = { it }, streamed = { streamed }, local = { onLocal(); local })

    @Test
    fun `a streamed queue is resolved without ever asking for the local library`() = runBlocking {
        var asked = false
        val byId = resolve(
            ids = listOf("ytm:abc", "sub1:42"),
            streamed = listOf("ytm:abc", "sub1:42", "ytm:other"),
            local = emptyList(),
            onLocal = { asked = true },
        )
        assertFalse("the local library (which waits for the scan) must not be awaited", asked)
        assertEquals(setOf("ytm:abc", "sub1:42", "ytm:other"), byId.keys)
    }

    @Test
    fun `a queue with a local file asks for the library`() = runBlocking {
        var asked = false
        val byId = resolve(
            ids = listOf("ytm:abc", "123"),
            streamed = listOf("ytm:abc"),
            local = listOf("123"),
            onLocal = { asked = true },
        )
        assertEquals(true, asked)
        assertEquals(setOf("ytm:abc", "123"), byId.keys)
    }

    @Test
    fun `an empty queue needs nothing`() = runBlocking {
        var asked = false
        resolve(ids = emptyList(), streamed = emptyList(), local = emptyList(), onLocal = { asked = true })
        assertFalse(asked)
    }
}

class LibraryWaitTest {
    private suspend fun <T> awaitLibrary(scanned: kotlinx.coroutines.flow.Flow<Boolean>, current: () -> List<T>) =
        com.ikko.aura.data.awaitLibrary(scanned, current)


    @Test
    fun `a library whose scan completed empty answers at once instead of waiting for ever`() = runBlocking {
        val scanned = kotlinx.coroutines.flow.MutableStateFlow(true)
        val answer = kotlinx.coroutines.withTimeoutOrNull(1_000) { awaitLibrary(scanned) { emptyList<String>() } }
        assertEquals(emptyList<String>(), answer)
    }

    @Test
    fun `a queue whose local files were all deleted still brings its streamed songs back`() = runBlocking {
        val scanned = kotlinx.coroutines.flow.MutableStateFlow(true)
        val byId = kotlinx.coroutines.withTimeoutOrNull(1_000) {
            resolveQueueEntries(
                ids = listOf("123", "ytm:abc"),
                idOf = { it },
                streamed = { listOf("ytm:abc") },
                local = { awaitLibrary(scanned) { emptyList() } },
            )
        }
        assertEquals(setOf("ytm:abc"), byId?.keys)
    }

    @Test
    fun `a scan that has not completed is still waited for, so nothing is taken for deleted early`() = runBlocking {
        val scanned = kotlinx.coroutines.flow.MutableStateFlow(false)
        val early = kotlinx.coroutines.withTimeoutOrNull(100) { awaitLibrary(scanned) { listOf("123") } }
        assertEquals(null, early)

        val later = async { awaitLibrary(scanned) { listOf("123") } }
        scanned.value = true
        assertEquals(listOf("123"), later.await())
    }
}
