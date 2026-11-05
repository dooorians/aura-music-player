package com.ikko.aura.data.cache

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One answer of a web service, kept on the device so the same question is not asked again before it goes stale.
 * [key] starts with the service's namespace ("lastfm:"), so several services share the table without their keys meeting.
 */
@Entity(tableName = "remote_cache", indices = [Index("fetchedAtMs")])
data class RemoteCacheEntity(
    @PrimaryKey val key: String,
    val value: String,
    val fetchedAtMs: Long,
)

@Dao
interface RemoteCacheDao {
    @Query("SELECT * FROM remote_cache WHERE `key` = :key")
    suspend fun get(key: String): RemoteCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: RemoteCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<RemoteCacheEntity>)

    /** Forgets what [namespace] fetched before [before]: the rows that could only ever be read as stale. */
    @Query("DELETE FROM remote_cache WHERE substr(`key`, 1, length(:namespace)) = :namespace AND fetchedAtMs < :before")
    suspend fun prune(namespace: String, before: Long): Int
}
