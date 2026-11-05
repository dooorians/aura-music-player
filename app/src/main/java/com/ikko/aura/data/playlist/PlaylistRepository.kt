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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Immutable
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val trackIds: List<String> = emptyList(),
    val coverUris: List<String> = emptyList(),
) {
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
    /** One observed query (with its Room relation) for every playlist and its entries, rather than one per playlist. */
    val playlists: Flow<List<PlaylistSummary>> = playlistDao.observePlaylistsWithEntries()
        .onStart { ensureFavorites() }
        .map(::summariesOf)

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
        playlistDao.toggleTrackAtTop(FAVORITES_PLAYLIST_ID, trackId, System.currentTimeMillis())
    }

    fun playlistDetail(playlistId: Long): Flow<PlaylistDetail?> = combine(
        playlistDao.observePlaylist(playlistId),
        playlistDao.observeTracksForPlaylist(playlistId),
        musicRepository.tracks,
        // Only the playlist's own rows, and only re-read into a new list when one of them changed: a write elsewhere in
        // the catalogue (a scan, a streamed song remembered) no longer rebuilds the screen.
        catalogDao.observeTracksOfPlaylist(playlistId).distinctUntilChanged(),
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
            // One statement per playlist instead of one per track.
            trackIds.chunked(SQL_IN_CHUNK).forEach { playlistDao.removeTracksFromPlaylist(id, it) }
        }
    }

    /** The IDs of all auto-managed playlists. */
    suspend fun autoPlaylistIds(): Set<Long> =
        AUTO_KEYS.mapNotNull { autoPlaylistId(it) }.toSet()

    /** Every entry of every playlist but Favoris and the ones Aura keeps: what the listener put somewhere on purpose. */
    suspend fun userPlaylistEntries(): List<PlaylistTrackEntity> {
        val auto = autoPlaylistIds()
        return playlistDao.allEntries().filter { it.playlistId != FAVORITES_PLAYLIST_ID && it.playlistId !in auto }
    }

    suspend fun favoriteEntries(): List<PlaylistTrackEntity> = playlistDao.entriesOf(FAVORITES_PLAYLIST_ID)

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

    suspend fun addTrack(playlistId: Long, trackId: String) =
        playlistDao.appendTrack(playlistId, trackId, System.currentTimeMillis())

    suspend fun removeTrack(playlistId: Long, trackId: String) = playlistDao.removeTrackFromPlaylist(playlistId, trackId)

    fun playlistCustomArtwork(playlistId: Long): Flow<String?> = settingsStore.playlistCustomArtwork(playlistId)

    val playlistCustomArtworks: Flow<Map<Long, String>> = settingsStore.playlistCustomArtworks

    suspend fun setPlaylistCustomArtwork(playlistId: Long, uri: String?) {
        settingsStore.setPlaylistCustomArtwork(playlistId, uri)
    }
}

private const val AUTO_PLAYLIST_PERIOD_MS = 7L * 24 * 60 * 60 * 1000

/** Well under SQLite's bound-parameter limit, so a long list of ids never makes a statement fail. */
private const val SQL_IN_CHUNK = 500

/**
 * The library's playlist list: entries in their order, Favoris first, the others newest first as the query gave them,
 * and the old duplicates of Favoris ("Tes préférés", a second "Favoris") left out.
 */
internal fun summariesOf(rows: List<PlaylistWithEntries>): List<PlaylistSummary> = rows
    .filter { row ->
        val p = row.playlist
        p.id == FAVORITES_PLAYLIST_ID ||
            (!p.name.equals("Tes préférés", ignoreCase = true) && !p.name.equals(FAVORITES_PLAYLIST_NAME, ignoreCase = true))
    }
    .map { row ->
        PlaylistSummary(
            id = row.playlist.id,
            name = row.playlist.name,
            trackCount = row.entries.size,
            trackIds = row.entries.sortedBy { it.position }.map { it.trackId },
        )
    }
    .sortedByDescending { it.isFavorites }

/** The cards that have a playlist of their own. */
private val AUTO_KEYS = listOf("disc", "top", "style", "night", "energy", "new", "chill", "daily")
