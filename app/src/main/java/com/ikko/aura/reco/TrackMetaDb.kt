package com.ikko.aura.reco

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * What a local file's own tags say beyond what the device's media index keeps: the MusicBrainz id and ISRC that identify the recording,
 * ReplayGain, a tagged tempo, every genre (the index keeps one) and the year the song first came out. Read once per file, kept on the device.
 */
@Entity(tableName = "track_tags")
data class TrackTagsEntity(
    @PrimaryKey val trackId: String,
    /** The [TagScanVersion] it was read under: a newer reader re-reads. */
    val readVersion: Int,
    val mbid: String,
    val isrc: String,
    /** The track's ReplayGain in dB (the album's when the track has none); null when the file has none. */
    val replayGainDb: Double?,
    val bpm: Double,
    /** Normalised genres joined by NUL. */
    val genres: String,
    val originalYear: Int,
)

/** The artist as the catalogues resolved them, kept so a launch does not ask MusicBrainz (one request a second) again. */
@Entity(tableName = "artist_identity")
data class ArtistIdentityEntity(
    @PrimaryKey val artistKey: String,
    val canonicalName: String,
    val mbid: String?,
    val deezerId: Long?,
    val country: String?,
    /** Tags joined by NUL. */
    val tags: String,
    val disambiguation: String?,
    val resolvedAt: Long,
)

data class TagMark(val trackId: String, val readVersion: Int)

object TagScanVersion { const val CURRENT = 1 }

@Dao
interface TrackMetaDao {
    @Query("SELECT * FROM track_tags")
    suspend fun allTags(): List<TrackTagsEntity>

    @Query("SELECT trackId, readVersion FROM track_tags")
    suspend fun marks(): List<TagMark>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTags(rows: List<TrackTagsEntity>)

    @Query("DELETE FROM track_tags WHERE trackId IN (:ids)")
    suspend fun forgetTags(ids: List<String>)

    @Query("SELECT * FROM artist_identity WHERE artistKey = :key")
    suspend fun identity(key: String): ArtistIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putIdentity(row: ArtistIdentityEntity)
}

/**
 * The tags of the files, the artists' identities, the key that lets one recording share its stats under two ids, and the date an artist's
 * score was last fed (so it can fade). Additive only.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS track_tags (trackId TEXT NOT NULL PRIMARY KEY, readVersion INTEGER NOT NULL, mbid TEXT NOT NULL, " +
                "isrc TEXT NOT NULL, replayGainDb REAL, bpm REAL NOT NULL, genres TEXT NOT NULL, originalYear INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS artist_identity (artistKey TEXT NOT NULL PRIMARY KEY, canonicalName TEXT NOT NULL, mbid TEXT, " +
                "deezerId INTEGER, country TEXT, tags TEXT NOT NULL, disambiguation TEXT, resolvedAt INTEGER NOT NULL)",
        )
        db.execSQL("ALTER TABLE track_stats ADD COLUMN canonicalKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE artist_meta ADD COLUMN affinityUpdatedAt INTEGER NOT NULL DEFAULT 0")
    }
}
