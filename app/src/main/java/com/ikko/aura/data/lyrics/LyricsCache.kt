package com.ikko.aura.data.lyrics

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One track's lyrics as they were last resolved, so a song looked up once is never
 * looked up again.
 *
 * Stored as enhanced ("A2") LRC rather than as a parsed structure: it is the format the
 * device's own `.lrc` sidecars already arrive in, so one parser reads both, and it
 * keeps the word timings that make the sweep work.
 *
 * A row with an empty [lrc] is a *miss* that was remembered. Without it, a track no
 * database has — which on a personal library is a good number of them — would go back
 * out to three hosts on every single play. [LyricsRepository.MISS_RETRY_MS] is how long
 * that stands before it is worth asking again, because lyrics do get published.
 */
@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey val trackId: String,
    val source: String,
    val lrc: String,
    val fetchedAtMs: Long,
)

@Dao
interface LyricsCacheDao {
    @Query("SELECT * FROM lyrics_cache WHERE trackId = :trackId")
    suspend fun find(trackId: String): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entry: LyricsCacheEntity)

    @Query("DELETE FROM lyrics_cache WHERE trackId = :trackId")
    suspend fun forget(trackId: String)

    @Query("DELETE FROM lyrics_cache")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM lyrics_cache WHERE lrc != ''")
    suspend fun storedCount(): Int
}
