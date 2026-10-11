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
    private val ytmLibrary: com.ikko.aura.source.ytm.YtmLibrary,
    private val settingsStore: com.ikko.aura.data.SettingsStore,
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
        ytmLibrary.observeKnown(),
    ) { playlist, entries, allTracks, remembered, streamed ->
        if (playlist == null) return@combine null
        // A streamed song is a playlist's own whether or not it was kept in the library.
        val tracksById = (allTracks + streamed).associateBy { it.id }
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

    /**
     * The playlist Aura keeps for [key] (a Home card: "Découverte", "Les plus écoutés"…), as the id of a real playlist
     * that opens, plays and edits like any other. It is filled again from [tracks] once a week; in between, what the
     * listener's plays do to the list is not written into it, so the playlist is the same all week. [tracks] is only
     * read when a fill is due. Made again, at once, if it was deleted.
     */
    suspend fun autoPlaylist(key: String, name: String, nowMs: Long, tracks: () -> List<Track>): Long {
        val known = settingsStore.autoPlaylist(key)
        val alive = known != null && playlistDao.playlistExists(known.first) > 0
        if (known != null && alive && nowMs - known.second < AUTO_PLAYLIST_PERIOD_MS) return known.first
        val id = if (alive) known!!.first else createPlaylist(name)
        val list = tracks()
        // An empty pick (nothing played yet) leaves the old list rather than emptying a playlist the user may use.
        if (list.isNotEmpty()) {
            playlistDao.replaceTracks(id, list.mapIndexed { i, t -> PlaylistTrackEntity(id, t.id, i, nowMs) })
            settingsStore.setAutoPlaylist(key, id, nowMs)
        } else if (!alive) {
            settingsStore.setAutoPlaylist(key, id, 0L)
        }
        return id
    }

    /**
     * Replaces the playlist kept for [key] with [tracks] now — a recommendation renewal — creating it if it is gone.
     * Only the playlist's rows change; nothing that is playing is touched.
     */
    suspend fun replaceAutoPlaylist(key: String, name: String, tracks: List<Track>, nowMs: Long): Long {
        val known = settingsStore.autoPlaylist(key)
        val alive = known != null && playlistDao.playlistExists(known.first) > 0
        val id = if (alive) known!!.first else createPlaylist(name)
        playlistDao.replaceTracks(id, tracks.mapIndexed { i, t -> PlaylistTrackEntity(id, t.id, i, nowMs) })
        settingsStore.setAutoPlaylist(key, id, nowMs)
        return id
    }

    /** The id of the playlist kept for [key], or null if there is none (or it was deleted). */
    suspend fun autoPlaylistId(key: String): Long? =
        settingsStore.autoPlaylist(key)?.first?.takeIf { playlistDao.playlistExists(it) > 0 }

    /** Takes [trackIds] out of the playlists Aura keeps (a blocked artist's songs, a song the listener hid). */
    suspend fun removeFromAutoPlaylists(trackIds: Collection<String>) {
        if (trackIds.isEmpty()) return
        for (key in AUTO_KEYS) {
            val id = autoPlaylistId(key) ?: continue
            trackIds.forEach { playlistDao.removeTrackFromPlaylist(id, it) }
        }
    }

    /** Every entry of every playlist but Favoris and the ones Aura keeps: what the listener put somewhere on purpose. */
    suspend fun userPlaylistEntries(): List<PlaylistTrackEntity> {
        val auto = AUTO_KEYS.mapNotNull { autoPlaylistId(it) }.toSet()
        return playlistDao.allEntries().filter { it.playlistId != FAVORITES_PLAYLIST_ID && it.playlistId !in auto }
    }

    suspend fun favoriteEntries(): List<PlaylistTrackEntity> =
        playlistDao.allEntries().filter { it.playlistId == FAVORITES_PLAYLIST_ID }

    /** Which Home card ("disc", "top", "style"…) this playlist is the list of, or null if it is not one. */
    suspend fun autoKeyOf(playlistId: Long): String? =
        AUTO_KEYS.firstOrNull { settingsStore.autoPlaylist(it)?.first == playlistId }

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

private const val AUTO_PLAYLIST_PERIOD_MS = 7L * 24 * 60 * 60 * 1000

/** The cards that have a playlist of their own. */
private val AUTO_KEYS = listOf("disc", "top", "style", "night", "energy", "new", "chill", "daily")
