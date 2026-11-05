package com.ikko.aura.reco

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** What is learned about one song of the library, kept up to date at every listen. */
@Entity(tableName = "track_stats")
data class TrackStatsEntity(
    @PrimaryKey val trackId: String,
    val playCount: Int = 0,
    val completedCount: Int = 0,
    val earlySkipCount: Int = 0,
    val consecutiveSkips: Int = 0,
    /** Started by the listener (a tap), as opposed to reached through the queue. */
    val manualStartCount: Int = 0,
    /** Rewound to its start, or played again at once. */
    val replayCount: Int = 0,
    val isLiked: Boolean = false,
    val isDisliked: Boolean = false,
    val affinityScore: Double = 0.0,
    val lastPlayedTimestamp: Long = 0L,
    val addedTimestamp: Long = 0L,
    /** Plays by time of day: 05:00-11:59, 12:00-17:59, 18:00-22:59, 23:00-04:59. */
    val playsMorning: Int = 0,
    val playsAfternoon: Int = 0,
    val playsEvening: Int = 0,
    val playsNight: Int = 0,
    /** The artist's key (see [ArtistNames.key]) and the song's genres, so a candidate is scored without joining. */
    val artistKey: String = "",
    val albumKey: String = "",
    val genres: String = "",
)

/** What is known of an artist: the listener's affinity, and what Last.fm says of the sound (kept for 30 days). */
@Entity(tableName = "artist_meta")
data class ArtistMetaEntity(
    @PrimaryKey val artistKey: String,
    val name: String,
    val isBlocked: Boolean = false,
    val affinityScore: Double = 0.0,
    /** The tags, cleaned, as a JSON array of strings. */
    val genresJson: String = "[]",
    val lastFmFetchedTimestamp: Long = 0L,
)

/** The graph of artists Last.fm lists as close, restricted to those of the library: a row is "target is close to source". */
@Entity(
    tableName = "artist_similarity",
    primaryKeys = ["sourceArtistKey", "targetArtistKey"],
    indices = [Index("sourceArtistKey")],
)
data class ArtistSimilarityEntity(
    val sourceArtistKey: String,
    val targetArtistKey: String,
    /** 0.0 to 1.0. */
    val similarityScore: Double,
)

/** How many playlists hold both songs, and in how many of them they stand next to each other. */
data class Cooccurrence(val trackId: String, val shared: Int, val adjacent: Int)

@Dao
interface SmartDao {
    @Query("SELECT * FROM track_stats WHERE trackId = :trackId")
    suspend fun stats(trackId: String): TrackStatsEntity?

    @Query("SELECT * FROM track_stats")
    suspend fun allStats(): List<TrackStatsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putStats(stats: TrackStatsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAllStats(stats: List<TrackStatsEntity>)

    @Query("DELETE FROM track_stats WHERE trackId IN (:ids)")
    suspend fun forgetStats(ids: List<String>)

    @Query("SELECT * FROM artist_meta WHERE artistKey = :key")
    suspend fun artist(key: String): ArtistMetaEntity?

    @Query("SELECT * FROM artist_meta")
    suspend fun allArtists(): List<ArtistMetaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArtist(meta: ArtistMetaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArtists(meta: List<ArtistMetaEntity>)

    @Query("SELECT * FROM artist_similarity")
    suspend fun allSimilarities(): List<ArtistSimilarityEntity>

    @Query("SELECT * FROM artist_similarity WHERE sourceArtistKey = :key ORDER BY similarityScore DESC")
    suspend fun similarTo(key: String): List<ArtistSimilarityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSimilarities(rows: List<ArtistSimilarityEntity>)

    @Query("DELETE FROM artist_similarity WHERE sourceArtistKey = :key")
    suspend fun clearSimilarities(key: String)

    /**
     * The songs that share a local playlist with [trackId], with how many playlists they share and in how many the two
     * stand directly one after the other: the raw material of the playlist co-occurrence score. One indexed join.
     */
    @Query(
        "SELECT b.trackId AS trackId, COUNT(DISTINCT a.playlistId) AS shared, " +
            "COUNT(DISTINCT CASE WHEN ABS(a.position - b.position) = 1 THEN a.playlistId END) AS adjacent " +
            "FROM playlist_tracks a JOIN playlist_tracks b ON a.playlistId = b.playlistId AND a.trackId != b.trackId " +
            "WHERE a.trackId = :trackId GROUP BY b.trackId",
    )
    suspend fun cooccurrence(trackId: String): List<Cooccurrence>
}

/** The smart recommendations' tables; additive only. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS track_stats (trackId TEXT NOT NULL PRIMARY KEY, playCount INTEGER NOT NULL, " +
                "completedCount INTEGER NOT NULL, earlySkipCount INTEGER NOT NULL, consecutiveSkips INTEGER NOT NULL, " +
                "manualStartCount INTEGER NOT NULL, replayCount INTEGER NOT NULL, isLiked INTEGER NOT NULL, isDisliked INTEGER NOT NULL, " +
                "affinityScore REAL NOT NULL, lastPlayedTimestamp INTEGER NOT NULL, addedTimestamp INTEGER NOT NULL, " +
                "playsMorning INTEGER NOT NULL, playsAfternoon INTEGER NOT NULL, playsEvening INTEGER NOT NULL, playsNight INTEGER NOT NULL, " +
                "artistKey TEXT NOT NULL, albumKey TEXT NOT NULL, genres TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS artist_meta (artistKey TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, isBlocked INTEGER NOT NULL, " +
                "affinityScore REAL NOT NULL, genresJson TEXT NOT NULL, lastFmFetchedTimestamp INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS artist_similarity (sourceArtistKey TEXT NOT NULL, targetArtistKey TEXT NOT NULL, " +
                "similarityScore REAL NOT NULL, PRIMARY KEY(sourceArtistKey, targetArtistKey))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artist_similarity_sourceArtistKey ON artist_similarity(sourceArtistKey)")
        // The index of the playlists' own table that the co-occurrence join runs on.
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_trackId ON playlist_tracks(trackId)")
    }
}
