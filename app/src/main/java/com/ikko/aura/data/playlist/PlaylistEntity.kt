package com.ikko.aura.data.playlist

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackId"], indices = [androidx.room.Index("trackId")])
data class PlaylistTrackEntity(
    val playlistId: Long,
    val trackId: String,
    val position: Int,
    val addedAtMs: Long,
)
