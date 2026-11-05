package com.ikko.aura.catalog

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey
import androidx.room.Query
import com.ikko.aura.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The full-text index of the catalogue: title, artist and album of every track, local or streamed.
 *
 * External content: the text is read from catalog_tracks, never stored twice, and the triggers Room writes for
 * [contentEntity] keep the index in step with every insert, update and delete there. unicode61 with
 * remove_diacritics folds case and accents on both sides, so "beyonce" finds "Beyoncé" and "ÉDITH" finds "Édith".
 */
@Fts4(
    contentEntity = CatalogTrackEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
    // Prefix indices for the first letters typed: "s*" otherwise merges the lists of every word in s, the slowest
    // query of all and the one sent at the first keystroke.
    prefix = [1, 2],
)
@Entity(tableName = "track_fts")
data class TrackSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val title: String,
    val artist: String,
    val album: String,
)

/** One track whose [field] (one of [TrackSearchSql.TITLE], [TrackSearchSql.ARTIST], [TrackSearchSql.ALBUM]) matched. */
data class SearchHit(val trackKey: String, val field: Int)

object TrackSearchSql {
    const val TITLE = 0
    const val ARTIST = 1
    const val ALBUM = 2

    /**
     * Hits kept per field. The screen lists 30 tracks, 15 albums and 15 artists; 200 per field leaves room for the
     * ones dropped as outside the library while keeping a one-letter query, which matches most of a big library,
     * from turning thousands of rows into objects.
     */
    const val PER_FIELD = 200

    /**
     * A column on the left of MATCH keeps every term of the query to that column: "daft punk" must all be in the
     * artist, not "daft" in the title and "punk" in the album. FTS4 takes a single MATCH per scan, hence the union.
     * Each field is capped on its own, so a one-letter query that floods the titles still leaves room for artists.
     */
    const val HITS =
        "SELECT c.`key` AS trackKey, h.field AS field FROM (" +
            "SELECT * FROM (SELECT docid, 0 AS field FROM track_fts WHERE title MATCH :match LIMIT :perField) " +
            "UNION ALL SELECT * FROM (SELECT docid, 1 AS field FROM track_fts WHERE artist MATCH :match LIMIT :perField) " +
            "UNION ALL SELECT * FROM (SELECT docid, 2 AS field FROM track_fts WHERE album MATCH :match LIMIT :perField)" +
            ") AS h JOIN catalog_tracks AS c ON c.rowid = h.docid WHERE c.inLibrary = 1"
}

@Dao
interface TrackSearchDao {
    @Query(TrackSearchSql.HITS)
    suspend fun hits(match: String, perField: Int): List<SearchHit>
}

/** The library's search index, as the search screen asks it. */
@Singleton
class TrackSearchIndex @Inject constructor(private val dao: TrackSearchDao) {
    /** What matches [query], or null when it cannot be asked (nothing searchable in it, or the index failed). */
    suspend fun hits(query: String): List<SearchHit>? {
        val match = LocalSearch.matchExpression(query) ?: return null
        return runCatchingCancellable { dao.hits(match, TrackSearchSql.PER_FIELD) }.getOrNull()
    }
}

/**
 * 20 → 21, additive only: the search index over the catalogue (the indices the hot reads needed came with 19 → 20).
 *
 * The statements are the ones Room writes for a fresh install (schemas/21.json), so an upgraded database and a new one
 * are the same. 'rebuild' fills the index from the rows catalog_tracks already holds, in this same transaction;
 * from then on the triggers keep it in step.
 */
val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        TrackSearchMigration.STATEMENTS.forEach(db::execSQL)
    }
}

internal object TrackSearchMigration {
    val STATEMENTS = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS `track_fts` USING FTS4(`title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
            "`album` TEXT NOT NULL, tokenize=unicode61 `remove_diacritics=1`, content=`catalog_tracks`, prefix=`1,2`)",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_track_fts_BEFORE_UPDATE BEFORE UPDATE ON `catalog_tracks` " +
            "BEGIN DELETE FROM `track_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_track_fts_BEFORE_DELETE BEFORE DELETE ON `catalog_tracks` " +
            "BEGIN DELETE FROM `track_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_track_fts_AFTER_UPDATE AFTER UPDATE ON `catalog_tracks` " +
            "BEGIN INSERT INTO `track_fts`(`docid`, `title`, `artist`, `album`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`artist`, NEW.`album`); END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_track_fts_AFTER_INSERT AFTER INSERT ON `catalog_tracks` " +
            "BEGIN INSERT INTO `track_fts`(`docid`, `title`, `artist`, `album`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`artist`, NEW.`album`); END",
        "INSERT INTO `track_fts`(`track_fts`) VALUES('rebuild')",
    )
}
