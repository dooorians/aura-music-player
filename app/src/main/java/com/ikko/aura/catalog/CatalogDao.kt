package com.ikko.aura.catalog

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "catalog_tracks")
data class CatalogTrackEntity(
    @PrimaryKey val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

@Entity(
    tableName = "track_bindings",
    primaryKeys = ["sourceId", "itemId"],
    indices = [Index("trackKey")],
)
data class TrackBindingEntity(
    val sourceId: String,
    val itemId: String,
    val trackKey: String,
    val fingerprint: String,
    val availability: String,
    val lastSeenAt: Long,
)

@Dao
interface CatalogDao {
    @Query("SELECT * FROM track_bindings WHERE sourceId = :sourceId")
    suspend fun bindingsOf(sourceId: String): List<TrackBindingEntity>

    @Query("SELECT * FROM track_bindings WHERE trackKey = :key")
    suspend fun bindingsFor(key: String): List<TrackBindingEntity>

    @Query("SELECT * FROM catalog_tracks")
    fun observeTracks(): Flow<List<CatalogTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<CatalogTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBindings(bindings: List<TrackBindingEntity>)

    @Query("DELETE FROM track_bindings WHERE sourceId = :sourceId")
    suspend fun clearBindings(sourceId: String)

    @Query("DELETE FROM track_bindings WHERE trackKey IN (:keys)")
    suspend fun deleteBindingsOfKeys(keys: Collection<String>)

    @Query("DELETE FROM catalog_tracks WHERE `key` IN (:keys)")
    suspend fun deleteTracks(keys: Collection<String>)

    /** One transaction, so a crash half-way never leaves a key without its binding. */
    @Transaction
    suspend fun replaceBindings(sourceId: String, bindings: List<TrackBindingEntity>, tracks: List<CatalogTrackEntity>) {
        clearBindings(sourceId)
        insertBindings(bindings)
        upsertTracks(tracks)
    }

    @Transaction
    suspend fun forgetKeys(keys: Collection<String>) {
        deleteBindingsOfKeys(keys)
        deleteTracks(keys)
    }
}
