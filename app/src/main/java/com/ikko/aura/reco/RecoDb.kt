package com.ikko.aura.reco

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** One finished listen. Kept on the device only. */
@Entity(tableName = "reco_listens", indices = [Index("endedAtMs")])
data class ListenEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val year: Int,
    val durationMs: Long,
    val playedMs: Long,
    val outcome: String,
    val endedAtMs: Long,
    val context: String?,
    /** Credits a source listed apart from the artist field, joined by NUL. */
    val extra: String,
)

/** An explicit answer: [kind] is a [FeedbackKind], [subject] a track id or, for a block, the artist's name. */
@Entity(tableName = "reco_feedback", primaryKeys = ["kind", "subject"])
data class FeedbackEntity(
    val kind: String,
    val subject: String,
    val artist: String,
    val title: String,
    val atMs: Long,
    val context: String?,
)

/** A song proposed in a playlist, to avoid proposing it again too soon. */
@Entity(tableName = "reco_history", primaryKeys = ["identity", "kindKey"])
data class HistoryEntity(val identity: String, val kindKey: String, val shownAtMs: Long)

/** A playlist as it was last generated: what the page shows next to each song. */
@Entity(tableName = "reco_items", primaryKeys = ["kindKey", "position"])
data class RecoItemEntity(
    val kindKey: String,
    val position: Int,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkUrl: String,
    val albumRef: String,
    val sourceId: String,
    val sourceName: String,
    val reason: String,
    val verified: Boolean,
)

/** When each playlist was last generated, and how the last attempt went. */
@Entity(tableName = "reco_state")
data class RecoStateEntity(
    @androidx.room.PrimaryKey val kindKey: String,
    val generatedAtMs: Long,
    val attemptedAtMs: Long,
    /** "ok", "kept" (a source failed or too few songs: the previous list stayed), "none". */
    val status: String,
)

@Dao
interface RecoDao {
    @Insert
    suspend fun insertListen(entity: ListenEntity)

    @Query("SELECT * FROM reco_listens WHERE endedAtMs >= :since ORDER BY endedAtMs DESC LIMIT :limit")
    suspend fun listensSince(since: Long, limit: Int): List<ListenEntity>

    @Query("DELETE FROM reco_listens WHERE id NOT IN (SELECT id FROM reco_listens ORDER BY endedAtMs DESC LIMIT :keep)")
    suspend fun pruneListens(keep: Int)

    @Query("DELETE FROM reco_listens")
    suspend fun clearListens()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFeedback(entity: FeedbackEntity)

    @Query("DELETE FROM reco_feedback WHERE kind = :kind AND subject = :subject")
    suspend fun removeFeedback(kind: String, subject: String)

    @Query("SELECT * FROM reco_feedback")
    suspend fun allFeedback(): List<FeedbackEntity>

    @Query("SELECT * FROM reco_feedback WHERE kind = 'BLOCK_ARTIST' ORDER BY atMs DESC")
    fun observeBlocked(): Flow<List<FeedbackEntity>>

    @Query("SELECT * FROM reco_feedback")
    fun observeFeedback(): Flow<List<FeedbackEntity>>

    /** Everything the listener told, but the artists they blocked: those are settings, not taste. */
    @Query("DELETE FROM reco_feedback WHERE kind != 'BLOCK_ARTIST'")
    suspend fun clearOpinions()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHistory(entries: List<HistoryEntity>)

    @Query("SELECT identity, MAX(shownAtMs) AS shownAtMs, '' AS kindKey FROM reco_history GROUP BY identity")
    suspend fun lastShown(): List<HistoryEntity>

    @Query("DELETE FROM reco_history WHERE shownAtMs < :before")
    suspend fun pruneHistory(before: Long)

    @Query("DELETE FROM reco_history")
    suspend fun clearHistory()

    @Query("SELECT * FROM reco_items WHERE kindKey = :kindKey ORDER BY position")
    fun observeItems(kindKey: String): Flow<List<RecoItemEntity>>

    @Query("SELECT * FROM reco_items ORDER BY kindKey, position")
    fun observeAllItems(): Flow<List<RecoItemEntity>>

    @Query("DELETE FROM reco_items WHERE kindKey = :kindKey")
    suspend fun clearItems(kindKey: String)

    @Query("DELETE FROM reco_items")
    suspend fun clearAllItems()

    @Query("DELETE FROM reco_items WHERE kindKey = :kindKey AND trackId = :trackId")
    suspend fun removeItem(kindKey: String, trackId: String)

    @Query("DELETE FROM reco_items WHERE trackId IN (:trackIds)")
    suspend fun removeItems(trackIds: List<String>)

    /** Every song proposed from an album, by its page's reference or, with none, by its name and artist. */
    @Query("DELETE FROM reco_items WHERE (albumRef != '' AND albumRef = :ref) OR (album = :title AND artist = :artist)")
    suspend fun removeItemsOfAlbum(ref: String, title: String, artist: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putItems(items: List<RecoItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: RecoStateEntity)

    @Query("SELECT * FROM reco_state")
    fun observeStates(): Flow<List<RecoStateEntity>>

    @Query("SELECT * FROM reco_state")
    suspend fun states(): List<RecoStateEntity>

    @Query("DELETE FROM reco_state")
    suspend fun clearStates()
}

/** The tables of the recommendations; additive only. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS reco_listens (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, trackId TEXT NOT NULL, " +
                "title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, year INTEGER NOT NULL, durationMs INTEGER NOT NULL, " +
                "playedMs INTEGER NOT NULL, outcome TEXT NOT NULL, endedAtMs INTEGER NOT NULL, context TEXT, extra TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reco_listens_endedAtMs ON reco_listens(endedAtMs)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS reco_feedback (kind TEXT NOT NULL, subject TEXT NOT NULL, artist TEXT NOT NULL, " +
                "title TEXT NOT NULL, atMs INTEGER NOT NULL, context TEXT, PRIMARY KEY(kind, subject))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS reco_history (identity TEXT NOT NULL, kindKey TEXT NOT NULL, shownAtMs INTEGER NOT NULL, " +
                "PRIMARY KEY(identity, kindKey))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS reco_items (kindKey TEXT NOT NULL, position INTEGER NOT NULL, trackId TEXT NOT NULL, " +
                "title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, durationMs INTEGER NOT NULL, artworkUrl TEXT NOT NULL, " +
                "albumRef TEXT NOT NULL, sourceId TEXT NOT NULL, sourceName TEXT NOT NULL, reason TEXT NOT NULL, verified INTEGER NOT NULL, " +
                "PRIMARY KEY(kindKey, position))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS reco_state (kindKey TEXT NOT NULL PRIMARY KEY, generatedAtMs INTEGER NOT NULL, " +
                "attemptedAtMs INTEGER NOT NULL, status TEXT NOT NULL)",
        )
    }
}
