package com.ikko.aura.data.playlist

import androidx.compose.runtime.Immutable
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.model.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Immutable
data class PlaylistSummary(val id: Long, val name: String, val trackCount: Int)

/** See [com.ikko.aura.viewmodel.LibraryUiState]'s doc comment — same issue applies here. */
@Immutable
data class PlaylistDetail(val id: Long, val name: String, val tracks: List<Track>)

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val musicRepository: MusicRepository,
) {
    val playlists: Flow<List<PlaylistSummary>> = playlistDao.observePlaylists().flatMapLatest { entities ->
        if (entities.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(entities.map { entity -> countFlow(entity) }) { it.toList() }
        }
    }

    private fun countFlow(entity: PlaylistEntity) =
        playlistDao.observeTracksForPlaylist(entity.id).map { tracks ->
            PlaylistSummary(entity.id, entity.name, tracks.size)
        }

    fun playlistDetail(playlistId: Long): Flow<PlaylistDetail?> = combine(
        playlistDao.observePlaylist(playlistId),
        playlistDao.observeTracksForPlaylist(playlistId),
        musicRepository.tracks,
    ) { playlist, entries, allTracks ->
        if (playlist == null) return@combine null
        val tracksById = allTracks.associateBy { it.id }
        val tracks = entries.sortedBy { it.position }.mapNotNull { tracksById[it.trackId] }
        PlaylistDetail(playlist.id, playlist.name, tracks)
    }

    suspend fun createPlaylist(name: String): Long =
        playlistDao.insertPlaylist(PlaylistEntity(name = name, createdAtMs = System.currentTimeMillis()))

    suspend fun deletePlaylist(playlistId: Long) = playlistDao.deletePlaylist(playlistId)

    suspend fun renamePlaylist(playlistId: Long, name: String) = playlistDao.renamePlaylist(playlistId, name)

    suspend fun addTrack(playlistId: Long, trackId: String) {
        val position = playlistDao.nextPosition(playlistId)
        playlistDao.insertPlaylistTrack(PlaylistTrackEntity(playlistId, trackId, position, System.currentTimeMillis()))
    }

    suspend fun removeTrack(playlistId: Long, trackId: String) = playlistDao.removeTrackFromPlaylist(playlistId, trackId)
}
