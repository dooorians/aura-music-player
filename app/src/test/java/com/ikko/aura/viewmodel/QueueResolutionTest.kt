package com.ikko.aura.viewmodel

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
