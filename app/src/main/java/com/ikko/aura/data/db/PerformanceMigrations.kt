package com.ikko.aura.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Indexes for the queries that sorted or scanned a whole table, the one index that duplicated a primary key dropped, and
 * the web-answer cache that used to live in a SharedPreferences file. Additive apart from that one index; no row moves.
 *
 * Each statement matches what Room declares for the entities (see schemas/…/20.json): MigrationSchemaTest checks it.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_19_20_SQL.forEach(db::execSQL)
    }
}

internal val MIGRATION_19_20_SQL = listOf(
    // Every read of one playlist is ordered by position, and adding a song reads its MIN/MAX.
    "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId_position` ON `playlist_tracks` (`playlistId`, `position`)",
    // Home's "écoutés récemment": ORDER BY lastPlayedAtMs DESC LIMIT n.
    "CREATE INDEX IF NOT EXISTS `index_album_plays_lastPlayedAtMs` ON `album_plays` (`lastPlayedAtMs`)",
    // The download loop: the oldest QUEUED/DOWNLOADING row, before each file.
    "CREATE INDEX IF NOT EXISTS `index_ytm_downloads_state_updatedAt` ON `ytm_downloads` (`state`, `updatedAt`)",
    // The history's pruning: WHERE shownAtMs < ?.
    "CREATE INDEX IF NOT EXISTS `index_reco_history_shownAtMs` ON `reco_history` (`shownAtMs`)",
    // A song hidden or blocked is taken out of every generated playlist: WHERE trackId IN (…).
    "CREATE INDEX IF NOT EXISTS `index_reco_items_trackId` ON `reco_items` (`trackId`)",
    // The primary key (sourceArtistKey, targetArtistKey) already serves lookups by sourceArtistKey: this one only cost writes.
    "DROP INDEX IF EXISTS `index_artist_similarity_sourceArtistKey`",
    "CREATE TABLE IF NOT EXISTS `remote_cache` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, `fetchedAtMs` INTEGER NOT NULL, PRIMARY KEY(`key`))",
    "CREATE INDEX IF NOT EXISTS `index_remote_cache_fetchedAtMs` ON `remote_cache` (`fetchedAtMs`)",
)
