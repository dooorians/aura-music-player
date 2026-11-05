package com.ikko.aura.data.playlist

import androidx.room.Dao
import com.ikko.aura.catalog.CatalogDao
import com.ikko.aura.catalog.DownloadDao
import com.ikko.aura.source.remote.SourceConfigDao
import com.ikko.aura.source.remote.SourceConfigEntity
import com.ikko.aura.catalog.YtmDownloadEntity
import com.ikko.aura.catalog.CatalogTrackEntity
import com.ikko.aura.catalog.TrackBindingEntity
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ikko.aura.data.AlbumPlayEntity
import com.ikko.aura.data.AlbumPlaysDao
import com.ikko.aura.data.lyrics.LyricsCacheDao
import com.ikko.aura.data.lyrics.LyricsCacheEntity
import kotlinx.coroutines.flow.Flow

/** A playlist with its entries, loaded by Room in two queries whatever the number of playlists. */
data class PlaylistWithEntries(
    @androidx.room.Embedded val playlist: PlaylistEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "playlistId")
    val entries: List<PlaylistTrackEntity>,
)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAtMs DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    /**
     * Every playlist with its entries, newest first. Replaces one observed query per playlist (N+1): each write to
     * playlist_tracks re-ran all of them and the list was rebuilt N times.
     */
    @androidx.room.Transaction
    @Query("SELECT * FROM playlists ORDER BY createdAtMs DESC")
    fun observePlaylistsWithEntries(): Flow<List<PlaylistWithEntries>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun observePlaylist(playlistId: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeTracksForPlaylist(playlistId: Long): Flow<List<PlaylistTrackEntity>>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    /** Creates it only if there is no playlist with its id yet — for the Favoris playlist, made on demand. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistIfAbsent(playlist: PlaylistEntity)

    @Query("SELECT COALESCE(MIN(position), 1) - 1 FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun firstPosition(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun containsTrack(playlistId: Long, trackId: String): Int

    @Query("SELECT COUNT(*) FROM playlists WHERE id = :playlistId")
    suspend fun playlistExists(playlistId: Long): Int

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: Long)

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistTrack(entry: PlaylistTrackEntity)

    /** One prepared statement bound once per row, instead of one call (and one statement) per entry. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistTracks(entries: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId IN (:trackIds)")
    suspend fun removeTracksFromPlaylist(playlistId: Long, trackIds: Collection<String>)

    /** One transaction: the playlist is never seen empty or half-filled between the two steps. */
    @androidx.room.Transaction
    suspend fun replaceTracks(playlistId: Long, entries: List<PlaylistTrackEntity>) {
        clearPlaylistTracks(playlistId)
        if (entries.isNotEmpty()) insertPlaylistTracks(entries)
    }

    /**
     * Adds [trackId] at the end of the playlist. The position is read and written in one transaction: two adds landing
     * together used to read the same "next" position and share it.
     */
    @androidx.room.Transaction
    suspend fun appendTrack(playlistId: Long, trackId: String, atMs: Long) {
        insertPlaylistTrack(PlaylistTrackEntity(playlistId, trackId, nextPosition(playlistId), atMs))
    }

    /**
     * Puts [trackId] at the top of the playlist, or takes it out if it is there; true when it was added. One
     * transaction, so a double tap cannot read "absent" twice.
     */
    @androidx.room.Transaction
    suspend fun toggleTrackAtTop(playlistId: Long, trackId: String, atMs: Long): Boolean {
        if (containsTrack(playlistId, trackId) > 0) {
            removeTrackFromPlaylist(playlistId, trackId)
            return false
        }
        insertPlaylistTrack(PlaylistTrackEntity(playlistId, trackId, firstPosition(playlistId), atMs))
        return true
    }

    @Query("SELECT * FROM playlist_tracks")
    suspend fun allEntries(): List<PlaylistTrackEntity>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun entriesOf(playlistId: Long): List<PlaylistTrackEntity>
}

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        LyricsCacheEntity::class,
        AlbumPlayEntity::class,
        CatalogTrackEntity::class,
        TrackBindingEntity::class,
        YtmDownloadEntity::class,
        SourceConfigEntity::class,
        com.ikko.aura.reco.ListenEntity::class,
        com.ikko.aura.reco.FeedbackEntity::class,
        com.ikko.aura.reco.HistoryEntity::class,
        com.ikko.aura.reco.RecoItemEntity::class,
        com.ikko.aura.reco.RecoStateEntity::class,
        com.ikko.aura.reco.TrackStatsEntity::class,
        com.ikko.aura.reco.ArtistMetaEntity::class,
        com.ikko.aura.reco.ArtistSimilarityEntity::class,
        com.ikko.aura.reco.VerifiedDiscographyEntity::class,
        com.ikko.aura.reco.TrackTagsEntity::class,
        com.ikko.aura.reco.ArtistIdentityEntity::class,
        com.ikko.aura.reco.SongFeaturesEntity::class,
        com.ikko.aura.data.cache.RemoteCacheEntity::class,
        com.ikko.aura.catalog.TrackSearchEntity::class,
    ],
    version = 21,
    exportSchema = true,
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun lyricsCacheDao(): LyricsCacheDao
    abstract fun albumPlaysDao(): AlbumPlaysDao
    abstract fun catalogDao(): CatalogDao
    abstract fun downloadDao(): DownloadDao
    abstract fun sourceConfigDao(): SourceConfigDao
    abstract fun recoDao(): com.ikko.aura.reco.RecoDao
    abstract fun smartDao(): com.ikko.aura.reco.SmartDao
    abstract fun verifiedDiscographyDao(): com.ikko.aura.reco.VerifiedDiscographyDao
    abstract fun trackMetaDao(): com.ikko.aura.reco.TrackMetaDao
    abstract fun remoteCacheDao(): com.ikko.aura.data.cache.RemoteCacheDao
    abstract fun trackSearchDao(): com.ikko.aura.catalog.TrackSearchDao
}

/**
 * Adds the catalogue: what each track is, and which file holds it. Additive only — no key anywhere else is rewritten,
 * a local track's key being the MediaStore id it has always had.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS catalog_tracks (" +
                "`key` TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, " +
                "album TEXT NOT NULL, durationMs INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS track_bindings (" +
                "sourceId TEXT NOT NULL, itemId TEXT NOT NULL, trackKey TEXT NOT NULL, fingerprint TEXT NOT NULL, " +
                "availability TEXT NOT NULL, lastSeenAt INTEGER NOT NULL, PRIMARY KEY(sourceId, itemId))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_track_bindings_trackKey ON track_bindings(trackKey)")
    }
}

/** Remote covers for songs saved from a streaming source. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE catalog_tracks ADD COLUMN artworkUrl TEXT NOT NULL DEFAULT ''")
    }
}

/** The sources the user added themselves. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS source_configs (" +
                "id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, baseUrl TEXT NOT NULL, " +
                "username TEXT NOT NULL, secret TEXT NOT NULL, salt TEXT NOT NULL, enabled INTEGER NOT NULL, createdAt INTEGER NOT NULL)",
        )
    }
}

/** Songs downloaded from a streaming source: one row per song, with the state of its file. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS ytm_downloads (" +
                "`key` TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL, filePath TEXT NOT NULL, mime TEXT NOT NULL, " +
                "bytes INTEGER NOT NULL, totalBytes INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
        )
    }
}

/** The album a streamed song belongs to. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE catalog_tracks ADD COLUMN albumRef TEXT NOT NULL DEFAULT ''")
    }
}

/** Streamed songs can be known without being in the library. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE catalog_tracks ADD COLUMN inLibrary INTEGER NOT NULL DEFAULT 1")
    }
}

/** Adds the play counts Home's "Redécouvrir" row is built from. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS album_plays (" +
                "albumId INTEGER NOT NULL PRIMARY KEY, " +
                "playCount INTEGER NOT NULL, " +
                "lastPlayedAtMs INTEGER NOT NULL)",
        )
    }
}

/**
 * Adds the lyrics cache. Written out rather than falling back to a destructive
 * migration: this database also holds the user's playlists, and dropping those to make
 * room for a cache that can be refetched would be the wrong way round.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS lyrics_cache (" +
                "trackId TEXT NOT NULL PRIMARY KEY, " +
                "source TEXT NOT NULL, " +
                "lrc TEXT NOT NULL, " +
                "fetchedAtMs INTEGER NOT NULL)",
        )
    }
}
