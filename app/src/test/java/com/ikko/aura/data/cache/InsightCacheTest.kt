package com.ikko.aura.data.cache

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightCacheTest {

    private class FakeDao : RemoteCacheDao {
        val rows = HashMap<String, RemoteCacheEntity>()
        var batches = 0
        var singles = 0
        override suspend fun get(key: String) = rows[key]
        override suspend fun put(row: RemoteCacheEntity) { singles++; rows[row.key] = row }
        override suspend fun putAll(rows: List<RemoteCacheEntity>) { batches++; rows.forEach { this.rows[it.key] = it } }
        override suspend fun prune(namespace: String, before: Long): Int {
            val doomed = rows.values.filter { it.key.startsWith(namespace) && it.fetchedAtMs < before }.map { it.key }
            doomed.forEach(rows::remove)
            return doomed.size
        }
    }

    private class FakeLegacy(val map: MutableMap<String, String>) : InsightCache.Legacy {
        var cleared = false
        override fun entries(): Map<String, String> = map.toMap()
        override fun clear() { cleared = true; map.clear() }
    }

    private var now = 1_000_000L
    private val ttl = 10_000L

    @Test
    fun `an answer is kept until it goes stale`() = runBlocking {
        val cache = InsightCache(FakeDao(), "lastfm:", ttl, clock = { now })
        cache.put("tags:a", listOf("rock", "indie"))
        assertEquals(listOf("rock", "indie"), cache.get("tags:a"))
        now += ttl - 1
        assertEquals(listOf("rock", "indie"), cache.get("tags:a"))
        now += 1
        assertNull(cache.get("tags:a"))
    }

    @Test
    fun `a remembered miss is an empty answer, not an absent one`() = runBlocking {
        val cache = InsightCache(FakeDao(), "lastfm:", ttl, clock = { now })
        cache.put("tags:nobody", emptyList())
        assertEquals(emptyList<String>(), cache.get("tags:nobody"))
        assertNull(cache.get("tags:never-asked"))
    }

    @Test
    fun `the old SharedPreferences entries move over in one batch, and the stale ones are pruned`() = runBlocking {
        val dao = FakeDao()
        dao.rows["other:x"] = RemoteCacheEntity("other:x", "", 0L)
        val legacy = FakeLegacy(
            mutableMapOf(
                "tags_v2:a" to "${now - 100}\trock\u0001pop",
                "similar:b" to "${now - ttl - 1}\tc",
                "mbid:c" to "${now - 5}\t",
                "garbage" to "not a timestamp",
            ),
        )
        val cache = InsightCache(dao, "lastfm:", ttl, legacy, clock = { now })
        assertEquals(listOf("rock", "pop"), cache.get("tags_v2:a"))
        assertEquals(emptyList<String>(), cache.get("mbid:c"))
        assertNull(cache.get("similar:b"))
        assertTrue(legacy.cleared)
        assertEquals(1, dao.batches)
        assertEquals(setOf("lastfm:tags_v2:a", "lastfm:mbid:c", "other:x"), dao.rows.keys)
    }

    @Test
    fun `the migration runs once`() = runBlocking {
        val legacy = FakeLegacy(mutableMapOf("tags:a" to "${now}\tx"))
        val dao = FakeDao()
        val cache = InsightCache(dao, "lastfm:", ttl, legacy, clock = { now })
        cache.get("tags:a")
        legacy.map["tags:b"] = "${now}\ty"
        cache.get("tags:b")
        assertEquals(1, dao.batches)
        assertNull(cache.get("tags:b"))
    }

    @Test
    fun `values round-trip through the encoding`() {
        val values = listOf("hip-hop", "chanson française", "r&b")
        assertEquals(values, InsightCache.decode(InsightCache.encode(values)))
        assertEquals(emptyList<String>(), InsightCache.decode(""))
    }
}
