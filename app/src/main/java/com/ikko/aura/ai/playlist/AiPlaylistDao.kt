package com.ikko.aura.ai.playlist

import androidx.room.Dao
import androidx.room.Query

/**
 * One song of the library as the assistant weighs it: what its file's tags say (tempo, genres, ReplayGain) and what the
 * app learned of it (the genres the stats keep, the artist's Last.fm tags, the listener's affinity). Read from tables
 * that already exist (track_tags, track_stats, artist_meta): the assistant adds no table and no migration.
 */
data class AiTrackRow(
    val trackId: String,
    /** 0 when the file states none. */
    val bpm: Double,
    /** NUL-joined, as track_tags keeps them. */
    val tagGenres: String,
    val replayGainDb: Double?,
    /** As track_stats keeps them (NUL- or comma-joined). */
    val statGenres: String,
    /** A JSON array of strings, as artist_meta keeps them. */
    val artistGenres: String,
    val affinity: Double,
    val liked: Boolean,
    val playCount: Int,
)

@Dao
interface AiPlaylistDao {

    /**
     * The songs whose tempo fits one of three windows: the one asked for, and its half and double, since a catalogue
     * or a tag states a tempo an octave off as often as not (a ballad tagged 144 is felt at 72). A song with no tempo
     * (bpm 0) is always kept: most libraries tag few of them, and an unknown tempo is no reason to refuse a song. With
     * [anyTempo], the windows are ignored. Disliked songs and blocked artists never come back.
     */
    @Query(
        """
        SELECT t.trackId AS trackId, t.bpm AS bpm, t.genres AS tagGenres, t.replayGainDb AS replayGainDb,
               COALESCE(s.genres, '') AS statGenres, COALESCE(a.genresJson, '[]') AS artistGenres,
               COALESCE(s.affinityScore, 0.0) AS affinity, COALESCE(s.isLiked, 0) AS liked, COALESCE(s.playCount, 0) AS playCount
        FROM track_tags t
        LEFT JOIN track_stats s ON s.trackId = t.trackId
        LEFT JOIN artist_meta a ON a.artistKey = s.artistKey
        WHERE COALESCE(s.isDisliked, 0) = 0
          AND COALESCE(a.isBlocked, 0) = 0
          AND (:anyTempo OR t.bpm <= 0
               OR t.bpm BETWEEN :lo AND :hi
               OR t.bpm BETWEEN :halfLo AND :halfHi
               OR t.bpm BETWEEN :doubleLo AND :doubleHi)
        LIMIT :limit
        """,
    )
    suspend fun candidates(
        anyTempo: Boolean,
        lo: Double,
        hi: Double,
        halfLo: Double,
        halfHi: Double,
        doubleLo: Double,
        doubleHi: Double,
        limit: Int,
    ): List<AiTrackRow>

    /** Songs never to propose, whether or not their file has been read yet. */
    @Query(
        """
        SELECT s.trackId FROM track_stats s LEFT JOIN artist_meta a ON a.artistKey = s.artistKey
        WHERE s.isDisliked = 1 OR COALESCE(a.isBlocked, 0) = 1
        """,
    )
    suspend fun refusedIds(): List<String>

    /** Every song whose tags have been read: the others are taken as songs of unknown tempo and genre. */
    @Query("SELECT trackId FROM track_tags")
    suspend fun taggedIds(): List<String>
}
