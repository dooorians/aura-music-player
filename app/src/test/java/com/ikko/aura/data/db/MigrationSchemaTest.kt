package com.ikko.aura.data.db

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks MIGRATION_19_20 against the two schemas Room exported: every index and table version 20 has and 19 had not is
 * created with exactly the SQL Room expects (what its validation compares at the first open after the upgrade), and every
 * index 19 had and 20 has not is dropped. A mismatch here is a crash at launch on the device, after the update.
 */
class MigrationSchemaTest {

    private fun schema(version: Int): JSONObject {
        val dir = listOf("schemas", "app/schemas").map { File(it, "com.ikko.aura.data.playlist.AuraDatabase") }.first { it.isDirectory }
        return JSONObject(File(dir, "$version.json").readText()).getJSONObject("database")
    }

    private fun normalise(sql: String) = sql.replace("IF NOT EXISTS ", "").replace(Regex("\\s+"), " ").trim()

    /** "index name" → its CREATE statement, and "table name" → its CREATE statement, as Room writes them. */
    private fun objects(db: JSONObject): Pair<Map<String, String>, Map<String, String>> {
        val indices = HashMap<String, String>()
        val tables = HashMap<String, String>()
        val entities = db.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            tables[table] = e.getString("createSql").replace("\${TABLE_NAME}", table)
            val idx = e.optJSONArray("indices") ?: continue
            for (j in 0 until idx.length()) {
                val index = idx.getJSONObject(j)
                indices[index.getString("name")] = index.getString("createSql").replace("\${TABLE_NAME}", table)
            }
        }
        return indices to tables
    }

    @Test
    fun `the migration takes version 19 to exactly version 20`() {
        assertEquals(19, MIGRATION_19_20.startVersion)
        assertEquals(20, MIGRATION_19_20.endVersion)
        val (oldIndices, oldTables) = objects(schema(19))
        val (newIndices, newTables) = objects(schema(20))
        val statements = MIGRATION_19_20_SQL.map(::normalise)

        for ((name, sql) in newIndices - oldIndices.keys) {
            assertTrue("index $name is not created as Room declares it:\n$sql", normalise(sql) in statements)
        }
        for (name in oldIndices.keys - newIndices.keys) {
            assertTrue("index $name is not dropped", statements.any { it.startsWith("DROP INDEX") && it.contains("`$name`") })
        }
        for ((name, sql) in newTables - oldTables.keys) {
            assertTrue("table $name is not created as Room declares it:\n$sql", normalise(sql) in statements)
        }
        assertEquals("no table may change shape in this migration", oldTables, newTables.filterKeys { it in oldTables })
        // Nothing else: every statement is accounted for by the schemas.
        val expected = (newIndices - oldIndices.keys).size + (oldIndices.keys - newIndices.keys).size + (newTables - oldTables.keys).size
        assertEquals(expected, statements.size)
    }
}
