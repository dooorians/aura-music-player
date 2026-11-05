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
    /** A remote cover; empty for a local track, whose cover is its file. */
    val artworkUrl: String = "",
    /** A streamed song is known (to a playlist, the queue) without being in the library; local files always are. */
    val inLibrary: Boolean = true,
    /** A streamed song's album on its source (a playlist id), if it was met through one. */
    val albumRef: String = "",
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

    /**
     * The catalogue rows of one playlist's entries only, joined on the primary key, for naming the ones that can no
     * longer play. Reading the whole table there meant every row of the catalogue, decoded again at each write to it.
     */
    @Query(
        "SELECT c.* FROM playlist_tracks p INNER JOIN catalog_tracks c ON c.`key` = p.trackId " +
            "WHERE p.playlistId = :playlistId",
    )
    fun observeTracksOfPlaylist(playlistId: Long): Flow<List<CatalogTrackEntity>>

    /** The device's files as the catalogue knows them: their keys are MediaStore ids, with no source prefix. */
    @Query("SELECT * FROM catalog_tracks WHERE `key` NOT LIKE '%:%'")
    suspend fun localTracks(): List<CatalogTrackEntity>

    @Query("SELECT * FROM catalog_tracks WHERE `key` LIKE '%:%' AND inLibrary = 1")
    fun observeSaved(): Flow<List<CatalogTrackEntity>>

    /** Every streamed song the app has met and still keeps, in the library or not. */
    @Query("SELECT * FROM catalog_tracks WHERE `key` LIKE '%:%'")
    fun observeStreamed(): Flow<List<CatalogTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracksIfAbsent(tracks: List<CatalogTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBindingsIfAbsent(bindings: List<TrackBindingEntity>)

    @Query("UPDATE catalog_tracks SET album = :album, albumRef = :ref WHERE `key` = :key AND albumRef = ''")
    suspend fun attachAlbum(key: String, album: String, ref: String)

    @Query("UPDATE catalog_tracks SET inLibrary = :inLibrary WHERE `key` = :key")
    suspend fun setInLibrary(key: String, inLibrary: Boolean)

    /**
     * Streamed songs that are in no library and no playlist and have not been seen for a while: nothing refers to
     * them any more.
     */
    @Query(
        "DELETE FROM catalog_tracks WHERE `key` LIKE '%:%' AND inLibrary = 0 " +
            "AND `key` NOT IN (SELECT trackId FROM playlist_tracks) " +
            "AND `key` NOT IN (SELECT trackKey FROM track_bindings WHERE lastSeenAt > :since)",
    )
    suspend fun pruneStreamed(since: Long)

    @Query("DELETE FROM track_bindings WHERE sourceId != 'local' AND trackKey NOT IN (SELECT `key` FROM catalog_tracks)")
    suspend fun pruneOrphanBindings()

    /** Album pages attached in the past by looser matching, on songs nothing keeps: forgotten, so the strict resolver finds them again. */
    @Query(
        "UPDATE catalog_tracks SET albumRef = '' WHERE albumRef != '' AND inLibrary = 0 AND `key` LIKE '%:%' " +
            "AND `key` NOT IN (SELECT trackId FROM playlist_tracks)",
    )
    suspend fun clearLooseAlbumRefs(): Int

    /** JioSaavn matches made by the old automatic fallback, for songs nothing keeps. */
    @Query(
        "DELETE FROM track_bindings WHERE sourceId = 'jiosaavn' AND trackKey IN " +
            "(SELECT `key` FROM catalog_tracks WHERE inLibrary = 0 AND `key` NOT LIKE 'jiosaavn:%') " +
            "AND trackKey NOT IN (SELECT trackId FROM playlist_tracks)",
    )
    suspend fun deleteFallbackJioSaavnBindings(): Int

    /** Wipes the wrong associations of the past in one transaction; the number of rows touched. */
    @Transaction
    suspend fun purgeStaleLinks(): Int = clearLooseAlbumRefs() + deleteFallbackJioSaavnBindings()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<CatalogTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBindings(bindings: List<TrackBindingEntity>)

    @Query("DELETE FROM track_bindings WHERE sourceId = :sourceId")
    suspend fun clearBindings(sourceId: String)

    @Query("DELETE FROM track_bindings WHERE sourceId = :sourceId AND itemId IN (:itemIds)")
    suspend fun deleteBindings(sourceId: String, itemIds: Collection<String>)

    /** Applies a [CatalogSyncPlan] in one transaction: only the rows that changed are written. */
    @Transaction
    suspend fun applySync(sourceId: String, plan: CatalogSyncPlan) {
        plan.removedItemIds.chunked(SQL_IN_CHUNK).forEach { deleteBindings(sourceId, it) }
        if (plan.bindings.isNotEmpty()) insertBindings(plan.bindings)
        if (plan.tracks.isNotEmpty()) upsertTracks(plan.tracks)
    }

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
    suspend fun rememberItems(tracks: List<CatalogTrackEntity>, bindings: List<TrackBindingEntity>) {
        insertTracksIfAbsent(tracks)
        insertBindingsIfAbsent(bindings)
    }

    @Transaction
    suspend fun saveItem(track: CatalogTrackEntity, binding: TrackBindingEntity) {
        upsertTracks(listOf(track))
        insertBindings(listOf(binding))
    }

    @Transaction
    suspend fun forgetKeys(keys: Collection<String>) {
        keys.chunked(SQL_IN_CHUNK).forEach {
            deleteBindingsOfKeys(it)
            deleteTracks(it)
        }
    }
}

/** Well under SQLite's bound-parameter limit, whatever the size of the selection. */
private const val SQL_IN_CHUNK = 500
