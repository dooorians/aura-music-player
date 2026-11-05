package com.ikko.aura.source.remote

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A source the user added: where it is, who they are on it, and whether it is on. [secret] is the password, sealed by
 * [SecretBox] — never written in the clear. [salt] is fixed per source, so the address of a song is the same every
 * time and its bytes can be cached.
 */
@Entity(tableName = "source_configs")
data class SourceConfigEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    val secret: String,
    val salt: String,
    val enabled: Boolean,
    val createdAt: Long,
)

@Dao
interface SourceConfigDao {
    @Query("SELECT * FROM source_configs ORDER BY createdAt")
    fun observeAll(): Flow<List<SourceConfigEntity>>

    @Query("SELECT * FROM source_configs")
    suspend fun all(): List<SourceConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: SourceConfigEntity)

    @Query("UPDATE source_configs SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM source_configs WHERE id = :id")
    suspend fun delete(id: String)
}

/** What kinds of source the app can talk to. Every protocol is one that is written out in this package. */
enum class RemoteKind(val label: String, val hint: String) {
    SUBSONIC(
        "Subsonic / Navidrome",
        "Navidrome, Airsonic, Gonic… Lecture dans la qualité d'origine, FLAC compris.",
    ),
}
