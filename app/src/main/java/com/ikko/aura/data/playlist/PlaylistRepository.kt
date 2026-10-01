package com.ikko.aura.data.playlist

import androidx.compose.runtime.Immutable
import com.ikko.aura.catalog.CatalogDao
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
import kotlinx.coroutines.flow.onStart

@Immutable
data class PlaylistSummary(val id: Long, val name: String, val trackCount: Int) {
    /** The Favoris playlist: pinned first, and neither renamed nor deleted. */
    val isFavorites: Boolean get() = id == FAVORITES_PLAYLIST_ID
}

/**
 * The Favoris playlist's id: every track given a heart in the player is in it. A real playlist in the same
 * tables as the others, so it opens, plays and lists like them, but at a fixed id, made on first need, pinned at
 * the top and kept from being renamed or deleted. Negative, so it can never be one Room hands out; not -1, which
 * the playlist screen reads as "no id".
 */
const val FAVORITES_PLAYLIST_ID = -2L
const val FAVORITES_PLAYLIST_NAME = "Favoris"

/** See [com.ikko.aura.viewmodel.LibraryUiState]'s doc comment — same issue applies here. */
@Immutable
data class PlaylistDetail(
    val id: Long,
    val name: String,
    val tracks: List<Track>,
    /** Entries whose file is gone for now (not deleted through Aura): listed, not playable, still the playlist's. */
    val unavailable: List<UnavailableEntry> = emptyList(),
)

@Immutable
data class UnavailableEntry(val id: String, val title: String, val artist: String)

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val musicRepository: MusicRepository,
    private val catalogDao: CatalogDao,
) {
    val playlists: Flow<List<PlaylistSummary>> = playlistDao.observePlaylists()
        .onStart { ensureFavorites() }
        .flatMapLatest { entities ->
            if (entities.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(entities.map { entity -> countFlow(entity) }) { it.toList() }
            }
        }
        // Favoris first, whenever it was made; the others newest first, as before.
        .map { list -> list.sortedByDescending { it.isFavorites } }

    /** The ids of the tracks with a heart. */
    val favoriteTrackIds: Flow<Set<String>> = playlistDao.observeTracksForPlaylist(FAVORITES_PLAYLIST_ID)
        .map { entries -> entries.mapTo(mutableSetOf()) { it.trackId } }

    private suspend fun ensureFavorites() {
        playlistDao.insertPlaylistIfAbsent(
            PlaylistEntity(id = FAVORITES_PLAYLIST_ID, name = FAVORITES_PLAYLIST_NAME, createdAtMs = 0L),
        )
    }

    /** Gives [trackId] a heart, or takes it away. The newest heart goes to the top of Favoris. */
    suspend fun toggleFavorite(trackId: String) {
        ensureFavorites()
        if (playlistDao.containsTrack(FAVORITES_PLAYLIST_ID, trackId) > 0) {
            playlistDao.removeTrackFromPlaylist(FAVORITES_PLAYLIST_ID, trackId)
        } else {
            val position = playlistDao.firstPosition(FAVORITES_PLAYLIST_ID)
            playlistDao.insertPlaylistTrack(
                PlaylistTrackEntity(FAVORITES_PLAYLIST_ID, trackId, position, System.currentTimeMillis()),
            )
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
        catalogDao.observeTracks(),
    ) { playlist, entries, allTracks, remembered ->
        if (playlist == null) return@combine null
        val tracksById = allTracks.associateBy { it.id }
        val ordered = entries.sortedBy { it.position }
        val tracks = ordered.mapNotNull { tracksById[it.trackId] }
        // Only once the library has loaded: before that every entry would look gone.
        val unavailable = if (allTracks.isEmpty()) emptyList() else {
            val memory = remembered.associateBy { it.key }
            ordered.filter { it.trackId !in tracksById }
                .mapNotNull { e -> memory[e.trackId]?.let { UnavailableEntry(e.trackId, it.title, it.artist) } }
        }
        PlaylistDetail(playlist.id, playlist.name, tracks, unavailable)
    }

    suspend fun createPlaylist(name: String): Long =
        playlistDao.insertPlaylist(PlaylistEntity(name = name, createdAtMs = System.currentTimeMillis()))

    suspend fun deletePlaylist(playlistId: Long) {
        if (playlistId == FAVORITES_PLAYLIST_ID) return
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun renamePlaylist(playlistId: Long, name: String) {
        if (playlistId == FAVORITES_PLAYLIST_ID) return
        playlistDao.renamePlaylist(playlistId, name)
    }

    suspend fun addTrack(playlistId: Long, trackId: String) {
        val position = playlistDao.nextPosition(playlistId)
        playlistDao.insertPlaylistTrack(PlaylistTrackEntity(playlistId, trackId, position, System.currentTimeMillis()))
    }

    suspend fun removeTrack(playlistId: Long, trackId: String) = playlistDao.removeTrackFromPlaylist(playlistId, trackId)
}
