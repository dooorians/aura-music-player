package com.ikko.aura.data.playlist

import androidx.room.Dao
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

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAtMs DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

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

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String)
}

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        LyricsCacheEntity::class,
        AlbumPlayEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun lyricsCacheDao(): LyricsCacheDao
    abstract fun albumPlaysDao(): AlbumPlaysDao
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
