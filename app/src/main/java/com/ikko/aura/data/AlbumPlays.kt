package com.ikko.aura.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * How often an album has been played, and when it last was.
 *
 * Persisted because the one question it answers — which albums are you *not* listening to — has
 * no answer at all from a session's worth of history. [RecentlyPlayedTracker] keeps the ordering
 * Home's "écoutés récemment" row needs and loses it on exit, which is fine for a row about the
 * last hour and useless for a row about the last months.
 */
/** Indexed on [lastPlayedAtMs]: Home reads the most recent ones first, with a LIMIT. */
@Entity(tableName = "album_plays", indices = [androidx.room.Index("lastPlayedAtMs")])
data class AlbumPlayEntity(
    @PrimaryKey val albumId: Long,
    val playCount: Int,
    val lastPlayedAtMs: Long,
)

@Dao
interface AlbumPlaysDao {
    @Query("SELECT * FROM album_plays")
    fun observeAll(): Flow<List<AlbumPlayEntity>>

    /** Most recently played first — what Home's "écoutés récemment" row starts from on launch. */
    @Query("SELECT albumId FROM album_plays ORDER BY lastPlayedAtMs DESC LIMIT :limit")
    suspend fun recentAlbumIds(limit: Int): List<Long>

    /**
     * One statement rather than a read and a write: a track change can land while the previous
     * one's write is still in flight, and read-modify-write would lose a play doing it.
     */
    @Query(
        "INSERT INTO album_plays (albumId, playCount, lastPlayedAtMs) VALUES (:albumId, 1, :atMs) " +
            "ON CONFLICT(albumId) DO UPDATE SET playCount = playCount + 1, lastPlayedAtMs = :atMs",
    )
    suspend fun record(albumId: Long, atMs: Long)
}
