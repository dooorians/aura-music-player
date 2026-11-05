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
 * (playlistId, position) serves every read of a playlist, Favoris included: the rows of one playlist come out of the
 * index already in order, where the primary key (playlistId, trackId) left a sort to do on each emission, and
 * MIN/MAX(position) are read off its ends.
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
