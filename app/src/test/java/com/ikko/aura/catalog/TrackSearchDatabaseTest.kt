package com.ikko.aura.catalog

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * The search index and the 19 → 20 migration, run on a real SQLite (sqlite-jdbc: FTS4 and unicode61, as on Android).
 *
 * The database is built from Room's own exported schemas, so what is checked is what ships: version 19 as users
 * have it, upgraded by [MIGRATION_19_20], must be the very database Room creates for a fresh install of version 20.
 */
class TrackSearchDatabaseTest {

    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Room turns this on at open (TriggerBasedInvalidationTracker): it makes INSERT OR REPLACE fire the delete
        // triggers, which is what keeps the index clean when the catalogue upserts.
        exec("PRAGMA recursive_triggers = 1")
    }

    @After
    fun close() = db.close()

    // --- Helpers ---------------------------------------------------------------------------------------------------

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun schema(version: Int): JSONObject {
        val dir = listOf("schemas", "app/schemas").map(::File).first { it.isDirectory }
        return JSONObject(File(dir, "com.ikko.aura.data.playlist.AuraDatabase/$version.json").readText()).getJSONObject("database")
    }

    /** What Room runs to create [version] from nothing. */
    private fun create(version: Int) {
        val schema = schema(version)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val name = e.getString("tableName")
            exec(e.getString("createSql").replace("\${TABLE_NAME}", name))
            e.optJSONArray("indices")?.let { idx ->
                for (j in 0 until idx.length()) exec(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
            }
            e.optJSONArray("contentSyncTriggers")?.let { t -> for (j in 0 until t.length()) exec(t.getString(j)) }
        }
        schema.optJSONArray("views")?.let { v ->
            for (i in 0 until v.length()) {
                val view = v.getJSONObject(i)
                exec(view.getString("createSql").replace("\${VIEW_NAME}", view.getString("viewName")))
            }
        }
    }

    /** The migration's view of the connection: only execSQL is ever called on it. */
    private fun supportDb(): SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        when {
            method.name == "execSQL" && args?.size == 1 -> exec(args[0] as String).let { null }
            else -> throw UnsupportedOperationException(method.name)
        }
    } as SupportSQLiteDatabase

    private fun masterOf(conn: Connection): Set<List<String?>> = conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'room_master_table' " +
                "AND name NOT LIKE 'track_fts_%'",
        ).use { rs ->
            buildSet { while (rs.next()) add(listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))) }
        }
    }

    private fun addTrack(key: String, title: String, artist: String, album: String, inLibrary: Boolean = true, replace: Boolean = false) {
        val verb = if (replace) "INSERT OR REPLACE" else "INSERT"
        db.prepareStatement(
            "$verb INTO catalog_tracks (`key`, title, artist, album, durationMs, artworkUrl, inLibrary, albumRef) VALUES (?, ?, ?, ?, 0, '', ?, '')",
        ).use {
            it.setString(1, key); it.setString(2, title); it.setString(3, artist); it.setString(4, album)
            it.setInt(5, if (inLibrary) 1 else 0)
            it.executeUpdate()
        }
    }

    /** The DAO's own query, with the index's own MATCH expression. */
    private fun search(query: String, perField: Int = TrackSearchSql.PER_FIELD): List<SearchHit> {
        val match = LocalSearch.matchExpression(query) ?: return emptyList()
        return db.prepareStatement(TrackSearchSql.HITS).use { st ->
            st.setString(1, match)
            st.setInt(2, perField)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(SearchHit(rs.getString(1), rs.getInt(2))) } }
        }
    }

    private fun keys(query: String, field: Int? = null): Set<String> =
        search(query).filter { field == null || it.field == field }.mapTo(HashSet()) { it.trackKey }

    private fun assertIntegrity() {
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA integrity_check").use { rs -> rs.next(); assertEquals("ok", rs.getString(1)) }
        }
        // Throws (SQLITE_CORRUPT_VTAB) if the index disagrees in any way with catalog_tracks.
        exec("INSERT INTO track_fts(track_fts) VALUES('integrity-check')")
    }

    private fun migrated() {
        create(19)
        addTrack("1", "La Vie en rose", "Édith Piaf", "La Vie en rose")
        addTrack("ytm:abc", "Hymne à l'amour", "Édith Piaf", "")
        exec("INSERT INTO playlists (id, name, createdAtMs) VALUES (1, 'Favoris', 0)")
        exec("INSERT INTO playlist_tracks (playlistId, trackId, position, addedAtMs) VALUES (1, '1', 0, 0)")
        MIGRATION_19_20.migrate(supportDb())
    }

    // --- Migration ---------------------------------------------------------------------------------------------------

    @Test
    fun `version 19 upgraded is exactly a fresh version 20`() {
        migrated()
        val upgraded = masterOf(db)
        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        val upgradedDb = db
        try {
            db = fresh
            create(20)
            assertEquals(masterOf(fresh), upgraded)
        } finally {
            db = upgradedDb
            fresh.close()
        }
    }

    @Test
    fun `the migration keeps every row and indexes the tracks already there`() {
        migrated()
        assertIntegrity()
        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM playlist_tracks").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
        }
        assertEquals(setOf("1", "ytm:abc"), keys("edith", TrackSearchSql.ARTIST))
        assertEquals(setOf("ytm:abc"), keys("hymne amour", TrackSearchSql.TITLE))
    }

    @Test
    fun `the migration can run on a database that already has the index`() {
        migrated()
        // Every statement is IF NOT EXISTS, and 'rebuild' is idempotent: a retried upgrade does not fail.
        MIGRATION_19_20.migrate(supportDb())
        assertIntegrity()
        assertEquals(setOf("1", "ytm:abc"), keys("piaf"))
    }

    @Test
    fun `the new indices are the ones the hot reads use`() {
        migrated()
        fun plan(sql: String): String = db.createStatement().use { st ->
            st.executeQuery("EXPLAIN QUERY PLAN $sql").use { rs -> buildString { while (rs.next()) appendLine(rs.getString("detail")) } }
        }
        val playlist = plan("SELECT * FROM playlist_tracks WHERE playlistId = 1 ORDER BY position ASC")
        assertTrue(playlist, "index_playlist_tracks_playlistId_position" in playlist && "TEMP B-TREE" !in playlist)
        val queue = plan("SELECT * FROM ytm_downloads WHERE state = 'QUEUED' ORDER BY updatedAt LIMIT 1")
        assertTrue(queue, "index_ytm_downloads_state_updatedAt" in queue)
        val recent = plan("SELECT albumId FROM album_plays ORDER BY lastPlayedAtMs DESC LIMIT 5")
        assertTrue(recent, "index_album_plays_lastPlayedAtMs" in recent && "TEMP B-TREE" !in recent)
    }

    // --- The index follows the catalogue ----------------------------------------------------------------------------

    @Test
    fun `insert, update, replace and delete keep the index in step`() {
        create(20)
        addTrack("1", "Yesterday", "The Beatles", "Help!")
        assertEquals(setOf("1"), keys("yesterday"))

        exec("UPDATE catalog_tracks SET title = 'Help' WHERE `key` = '1'")
        assertEquals(emptySet<String>(), keys("yesterday"))
        assertEquals(setOf("1"), keys("help", TrackSearchSql.TITLE))

        // upsertTracks is INSERT OR REPLACE: the old row goes, a new rowid comes, and the index must follow.
        addTrack("1", "Let It Be", "The Beatles", "Let It Be", replace = true)
        assertEquals(emptySet<String>(), keys("help", TrackSearchSql.TITLE))
        assertEquals(setOf("1"), keys("let it be", TrackSearchSql.TITLE))
        assertIntegrity()

        exec("DELETE FROM catalog_tracks WHERE `key` = '1'")
        assertEquals(emptySet<String>(), keys("beatles"))
        assertIntegrity()
    }

    @Test
    fun `songs outside the library are not found`() {
        create(20)
        addTrack("ytm:kept", "Song A", "Artist", "", inLibrary = true)
        addTrack("ytm:met", "Song B", "Artist", "", inLibrary = false)
        assertEquals(setOf("ytm:kept"), keys("artist"))
        exec("UPDATE catalog_tracks SET inLibrary = 1 WHERE `key` = 'ytm:met'")
        assertEquals(setOf("ytm:kept", "ytm:met"), keys("artist"))
    }

    // --- Queries ---------------------------------------------------------------------------------------------------

    @Test
    fun `case and accents are ignored on both sides`() {
        create(20)
        addTrack("1", "Déjà vu", "Beyoncé", "B'Day")
        addTrack("2", "Hoppípolla", "Sigur Rós", "Takk...")
        addTrack("3", "ÇA PLANE POUR MOI", "Plastic Bertrand", "AN 1")
        assertEquals(setOf("1"), keys("beyonce"))
        assertEquals(setOf("1"), keys("BEYONCÉ"))
        assertEquals(setOf("1"), keys("deja vu", TrackSearchSql.TITLE))
        assertEquals(setOf("2"), keys("sigur ros"))
        assertEquals(setOf("2"), keys("hoppipolla"))
        assertEquals(setOf("3"), keys("ça plane"))
        assertEquals(setOf("3"), keys("ca plane"))
    }

    @Test
    fun `a decomposed accent is found like a composed one`() {
        create(20)
        addTrack("nfc", "Café del Mar", "Various", "")
        addTrack("nfd", "Café Society", "Various", "")
        assertEquals(setOf("nfc", "nfd"), keys("cafe", TrackSearchSql.TITLE))
        assertEquals(setOf("nfc", "nfd"), keys("café", TrackSearchSql.TITLE))
        assertEquals(setOf("nfc", "nfd"), keys("café", TrackSearchSql.TITLE))
        assertEquals(setOf("nfd"), keys("café soc", TrackSearchSql.TITLE))
    }

    @Test
    fun `words complete as they are typed`() {
        create(20)
        addTrack("1", "Hey Jude", "The Beatles", "Hey Jude")
        addTrack("2", "Beat It", "Michael Jackson", "Thriller")
        assertEquals(setOf("1", "2"), keys("bea"))
        assertEquals(setOf("1"), keys("beatl"))
        assertEquals(setOf("1"), keys("bea", TrackSearchSql.ARTIST))
        assertEquals(setOf("2"), keys("bea", TrackSearchSql.TITLE))
        // Every word, in the same field: "jude" and "thriller" are never in one field together.
        assertEquals(emptySet<String>(), keys("jude thriller"))
        assertEquals(setOf("2"), keys("michael jack"))
        // Not a part of a word: the screen's scan covers that.
        assertEquals(emptySet<String>(), keys("eatles"))
    }

    @Test
    fun `special characters neither break the query nor go unmatched`() {
        create(20)
        addTrack("1", "Highway to Hell", "AC/DC", "Highway to Hell")
        addTrack("2", "Sweet Child o' Mine", "Guns N' Roses", "Appetite for Destruction")
        addTrack("3", "\"Heroes\"", "David Bowie", "\"Heroes\"")
        addTrack("4", "Song 2", "Blur", "Blur")
        addTrack("5", "OR", "NEAR", "NOT")
        addTrack("6", "東京", "椎名林檎", "")
        assertEquals(setOf("1"), keys("AC/DC"))
        assertEquals(setOf("1"), keys("ac dc"))
        assertEquals(setOf("2"), keys("guns n' roses"))
        assertEquals(setOf("2"), keys("child o'"))
        assertEquals(setOf("3"), keys("\"heroes\""))
        assertEquals(setOf("3"), keys("heroes*"))
        assertEquals(setOf("4"), keys("song 2"))
        assertEquals(setOf("5"), keys("OR"))
        assertEquals(setOf("5"), keys("near"))
        assertEquals(setOf("5"), keys("NOT"))
        assertEquals(setOf("6"), keys("東京"))
        // Each of these used to be FTS syntax: none may throw.
        listOf("-", "\"", "*", "(", ")", "title:", "a OR", "NEAR/3", "^x", "'", "%", "_", "\\", "🎵").forEach { search(it) }
    }

    @Test
    fun `each field is capped on its own`() {
        create(20)
        exec("BEGIN")
        for (i in 0 until 50) addTrack("t$i", "Alpha $i", "Someone", "")
        addTrack("a", "Other", "Alpha Band", "")
        exec("COMMIT")
        val hits = search("alpha", perField = 10)
        assertEquals(10, hits.count { it.field == TrackSearchSql.TITLE })
        assertEquals(setOf("a"), hits.filter { it.field == TrackSearchSql.ARTIST }.mapTo(HashSet()) { it.trackKey })
    }

    @Test
    fun `ten thousand tracks answer in under 5 ms`() {
        create(20)
        val words = listOf(
            "love", "night", "dream", "fire", "heart", "rain", "light", "dance", "summer", "blue", "road", "river",
            "gold", "shadow", "ocean", "city", "star", "moon", "song", "time", "étoile", "cœur", "été", "nuit",
        )
        exec("BEGIN")
        for (i in 0 until 10_000) {
            val w = { k: Int -> words[(i * 31 + k * 17) % words.size] }
            addTrack("k$i", "${w(1)} ${w(2)} $i", "Artist ${i % 800} ${w(3)}", "Album ${i % 1200} ${w(4)}")
        }
        exec("COMMIT")
        assertIntegrity()

        val queries = listOf("love", "nu", "artist 42", "album 7 gold", "etoile", "COEUR", "s", "zzz", "été night")
        repeat(20) { queries.forEach { q -> search(q) } } // warm-up
        for (q in queries) {
            val times = (0 until 30).map { val t = System.nanoTime(); search(q); System.nanoTime() - t }.sorted()
            val medianMs = times[times.size / 2] / 1e6
            println("FTS '$q': ${"%.3f".format(medianMs)} ms (median), ${search(q).size} hits")
            assertTrue("'$q' took $medianMs ms", medianMs < 5.0)
        }
    }
}
