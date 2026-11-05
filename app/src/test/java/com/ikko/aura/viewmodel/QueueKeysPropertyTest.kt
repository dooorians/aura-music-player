package com.ikko.aura.viewmodel

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QueueKeys] driven the way the player screen drives it — rapid inserts, moves, removals and new queues, including
 * out-of-range positions a stale UI can send — against a plain model of the queue. The keys must stay unique, follow
 * their entries, and never throw.
 */
class QueueKeysPropertyTest {

    private data class Entry(val key: Long, val song: String)

    private fun run(seed: Int, steps: Int = 2_000) {
        val random = Random(seed)
        val keys = QueueKeys()
        var queue = keys.fresh(5).mapIndexed { i, k -> Entry(k, "s$i") }
        var keyList = queue.map { it.key }
        val everSeen = HashSet<Long>(keyList)
        repeat(steps) { step ->
            val before = keyList.toSet()
            val size = queue.size
            when (random.nextInt(6)) {
                0 -> { // play next / insert
                    val at = random.nextInt(-2, size + 3)
                    val count = random.nextInt(0, 4)
                    keyList = keys.inserted(keyList, at, count)
                    val pos = at.coerceIn(0, size)
                    val added = keyList.subList(pos, pos + count).map { Entry(it, "n$step") }
                    queue = queue.toMutableList().apply { addAll(pos, added) }
                }
                1 -> { // add to queue
                    val count = random.nextInt(0, 4)
                    keyList = keys.appended(keyList, count)
                    queue = queue + keyList.takeLast(count).map { Entry(it, "a$step") }
                }
                2 -> { // drag in the queue, with a stale index now and then
                    val from = random.nextInt(-1, size + 1)
                    val to = random.nextInt(-3, size + 3)
                    keyList = keys.moved(keyList, from, to)
                    if (from in queue.indices) {
                        queue = queue.toMutableList().apply { add(to.coerceIn(0, lastIndex), removeAt(from)) }
                    }
                }
                3 -> { // swipe away
                    val index = random.nextInt(-1, size + 1)
                    keyList = keys.removed(keyList, index)
                    if (index in queue.indices) queue = queue.toMutableList().apply { removeAt(index) }
                }
                4 -> { // the same song twice
                    if (size > 0) {
                        val dup = queue[random.nextInt(size)].song
                        keyList = keys.appended(keyList, 1)
                        queue = queue + Entry(keyList.last(), dup)
                    }
                }
                5 -> if (random.nextInt(20) == 0) { // a new queue (another playlist)
                    val n = random.nextInt(0, 30)
                    keyList = keys.fresh(n)
                    queue = keyList.mapIndexed { i, k -> Entry(k, "p$step-$i") }
                }
            }
            assertEquals("step $step, seed $seed", queue.map { it.key }, keyList)
            assertEquals("keys unique at step $step", keyList.size, keyList.toSet().size)
            // A key that appears is a new one: one that left the queue is never given to another entry later.
            val appeared = keyList.filter { it !in before }
            assertTrue("reused key at step $step", appeared.none { it in everSeen })
            everSeen += appeared
        }
    }

    @Test
    fun `random edits keep the keys in step with the entries`() {
        repeat(30) { run(it) }
    }

    @Test
    fun `fresh keys are never reused, even after everything was removed`() {
        val keys = QueueKeys()
        val first = keys.fresh(3)
        var list = first
        repeat(3) { list = keys.removed(list, 0) }
        assertTrue(list.isEmpty())
        val second = keys.fresh(3)
        assertTrue(second.none { it in first })
    }

    @Test
    fun `an entry the keys do not cover still gets a key no other entry has`() {
        val keys = listOf(0L, 1L, 2L)
        val all = (0 until 10).map { keys.keyAt(it) }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `separate queues on separate threads do not interfere`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll((0 until 16).map { seed -> Callable { run(100 + seed, 500) } }).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }
}
