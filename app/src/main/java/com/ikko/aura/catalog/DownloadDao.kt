package com.ikko.aura.catalog

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** [state] is one of QUEUED, DOWNLOADING, DONE, FAILED. [filePath] is where the file is (or will be). */
@Entity(tableName = "ytm_downloads")
data class YtmDownloadEntity(
    @PrimaryKey val key: String,
    val state: String,
    val filePath: String,
    val mime: String,
    val bytes: Long,
    val totalBytes: Long,
    val updatedAt: Long,
)

/** A finished download and when it finished (its row's last update), in milliseconds. */
data class DoneTime(val key: String, val updatedAt: Long)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM ytm_downloads")
    fun observeAll(): Flow<List<YtmDownloadEntity>>

    @Query("SELECT `key` FROM ytm_downloads WHERE state = 'DONE'")
    fun observeDoneKeys(): Flow<List<String>>

    @Query("SELECT `key`, updatedAt FROM ytm_downloads WHERE state = 'DONE'")
    fun observeDoneTimes(): Flow<List<DoneTime>>

    @Query("SELECT * FROM ytm_downloads WHERE `key` = :key")
    suspend fun get(key: String): YtmDownloadEntity?

    @Query("SELECT * FROM ytm_downloads WHERE state IN ('QUEUED', 'DOWNLOADING') ORDER BY updatedAt LIMIT 1")
    suspend fun nextPending(): YtmDownloadEntity?

    @Query("SELECT COUNT(*) FROM ytm_downloads WHERE state IN ('QUEUED', 'DOWNLOADING')")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM ytm_downloads")
    suspend fun all(): List<YtmDownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: YtmDownloadEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(rows: List<YtmDownloadEntity>)

    @Query("UPDATE ytm_downloads SET state = 'QUEUED', updatedAt = :now WHERE `key` IN (:keys) AND state = 'FAILED'")
    suspend fun requeueFailed(keys: Collection<String>, now: Long)

    @Query("UPDATE ytm_downloads SET state = 'QUEUED' WHERE state = 'DOWNLOADING'")
    suspend fun requeueInterrupted()

    @Query("DELETE FROM ytm_downloads WHERE `key` IN (:keys)")
    suspend fun delete(keys: Collection<String>)

    @Query("DELETE FROM ytm_downloads")
    suspend fun clear()
}
