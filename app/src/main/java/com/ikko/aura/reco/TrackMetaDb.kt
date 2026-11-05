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

/** What was measured of a recommended song (tempo, loudness, the tags listeners gave it), kept so it is looked up once. [found]: false when nothing was. */
@Entity(tableName = "song_features")
data class SongFeaturesEntity(
    @PrimaryKey val identity: String,
    val bpm: Double,
    val gainDb: Double?,
    /** Tags joined by NUL. */
    val trackTags: String,
    val found: Boolean,
    val fetchedAt: Long,
    /** What the app heard of the preview ([AudioProbe]); [probed]: 0 not tried yet, 1 heard, 2 tried and could not. */
    val probeBpm: Double = 0.0,
    val probeConf: Double = 0.0,
    val probeRmsDb: Double = 0.0,
    val probeLow: Double = 0.0,
    val probeVar: Double = 0.0,
    val probed: Int = 0,
    /** What ReccoBeats heard ([ReccoFeatures]); [felt]: 0 not asked yet, 1 heard, 2 asked and could not. */
    val feelEnergy: Double = 0.0,
    val feelAcoustic: Double = 0.0,
    val feelValence: Double = 0.0,
    val feelDance: Double = 0.0,
    val feelLoud: Double = 0.0,
    val feelTempo: Double = 0.0,
    val felt: Int = 0,
)

data class TagMark(val trackId: String, val readVersion: Int)

/** v2: the clean-up of BOMs/NULs in values, and the MusicBrainz UFID frame as Picard writes it (owner "http://musicbrainz.org"). */
object TagScanVersion { const val CURRENT = 2 }

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

    @Query("SELECT * FROM song_features WHERE identity IN (:ids)")
    suspend fun songFeatures(ids: List<String>): List<SongFeaturesEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSongFeatures(rows: List<SongFeaturesEntity>)
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

/** What was measured of the songs recommended (see [SongFeaturesEntity]); additive only. */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS song_features (identity TEXT NOT NULL PRIMARY KEY, bpm REAL NOT NULL, gainDb REAL, trackTags TEXT NOT NULL, " +
                "found INTEGER NOT NULL, fetchedAt INTEGER NOT NULL)",
        )
    }
}

/** What the app heard of the previews of the songs recommended; additive only. */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE song_features ADD COLUMN probeBpm REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE song_features ADD COLUMN probeConf REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE song_features ADD COLUMN probeRmsDb REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE song_features ADD COLUMN probeLow REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE song_features ADD COLUMN probeVar REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE song_features ADD COLUMN probed INTEGER NOT NULL DEFAULT 0")
    }
}

/** What a model heard of the previews (energy, acousticness, mood...); additive only. */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (column in listOf("feelEnergy", "feelAcoustic", "feelValence", "feelDance", "feelLoud", "feelTempo")) {
            db.execSQL("ALTER TABLE song_features ADD COLUMN $column REAL NOT NULL DEFAULT 0")
        }
        db.execSQL("ALTER TABLE song_features ADD COLUMN felt INTEGER NOT NULL DEFAULT 0")
    }
}
