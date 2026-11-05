package com.ikko.aura.data.playlist

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
)

/**
 * The (playlistId, position) index serves every read of one playlist — they are all ordered by position — and the MIN/MAX
 * that place a new entry, which otherwise sorted the playlist's rows each time.
 */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    indices = [androidx.room.Index("trackId"), androidx.room.Index("playlistId", "position")],
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val trackId: String,
    val position: Int,
    val addedAtMs: Long,
)
