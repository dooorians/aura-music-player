package com.ikko.aura.data.playlist

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DAO's default methods (the transactions) against an in-memory table that keeps the SQL's rules: the
 * (playlistId, trackId) key, IGNORE on conflict, MIN/MAX of position. Room wraps each in a transaction; what is checked
 * here is what they write.
 */
class PlaylistDaoTransactionsTest {

    private class FakePlaylistDao : PlaylistDao {
        val playlists = MutableStateFlow<List<PlaylistEntity>>(emptyList())
        val rows = MutableStateFlow<List<PlaylistTrackEntity>>(emptyList())
        var singleInserts = 0
        var batchInserts = 0

        override fun observePlaylists(): Flow<List<PlaylistEntity>> = playlists
        override fun observePlaylistsWithEntries(): Flow<List<PlaylistWithEntries>> = playlists.map { list ->
            list.map { p -> PlaylistWithEntries(p, rows.value.filter { it.playlistId == p.id }) }
        }
        override fun observePlaylist(playlistId: Long): Flow<PlaylistEntity?> = playlists.map { l -> l.firstOrNull { it.id == playlistId } }
        override fun observeTracksForPlaylist(playlistId: Long): Flow<List<PlaylistTrackEntity>> =
            rows.map { r -> r.filter { it.playlistId == playlistId }.sortedBy { it.position } }
        override suspend fun insertPlaylist(playlist: PlaylistEntity): Long {
            val id = (playlists.value.maxOfOrNull { it.id } ?: 0L) + 1
            playlists.value = playlists.value + playlist.copy(id = id)
            return id
        }
        override suspend fun insertPlaylistIfAbsent(playlist: PlaylistEntity) {
            if (playlists.value.none { it.id == playlist.id }) playlists.value = playlists.value + playlist
        }
        override suspend fun firstPosition(playlistId: Long): Int =
            (rows.value.filter { it.playlistId == playlistId }.minOfOrNull { it.position } ?: 1) - 1
        override suspend fun containsTrack(playlistId: Long, trackId: String): Int =
            rows.value.count { it.playlistId == playlistId && it.trackId == trackId }
        override suspend fun playlistExists(playlistId: Long): Int = playlists.value.count { it.id == playlistId }
        override suspend fun deletePlaylist(playlistId: Long) { playlists.value = playlists.value.filterNot { it.id == playlistId } }
        override suspend fun clearPlaylistTracks(playlistId: Long) { rows.value = rows.value.filterNot { it.playlistId == playlistId } }
        override suspend fun renamePlaylist(playlistId: Long, name: String) {
            playlists.value = playlists.value.map { if (it.id == playlistId) it.copy(name = name) else it }
        }
        override suspend fun nextPosition(playlistId: Long): Int =
            (rows.value.filter { it.playlistId == playlistId }.maxOfOrNull { it.position } ?: -1) + 1
        override suspend fun insertPlaylistTrack(entry: PlaylistTrackEntity) {
            singleInserts++
            insert(entry)
        }
        override suspend fun insertPlaylistTracks(entries: List<PlaylistTrackEntity>) {
            batchInserts++
            entries.forEach(::insert)
        }
        private fun insert(entry: PlaylistTrackEntity) {
            if (rows.value.none { it.playlistId == entry.playlistId && it.trackId == entry.trackId }) rows.value = rows.value + entry
        }
        override suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
            rows.value = rows.value.filterNot { it.playlistId == playlistId && it.trackId == trackId }
        }
        override suspend fun removeTracksFromPlaylist(playlistId: Long, trackIds: Collection<String>) {
            rows.value = rows.value.filterNot { it.playlistId == playlistId && it.trackId in trackIds }
        }
        override suspend fun allEntries(): List<PlaylistTrackEntity> = rows.value
        override suspend fun entriesOf(playlistId: Long): List<PlaylistTrackEntity> =
            rows.value.filter { it.playlistId == playlistId }.sortedBy { it.position }

        fun order(playlistId: Long) = rows.value.filter { it.playlistId == playlistId }.sortedBy { it.position }.map { it.trackId }
    }

    @Test
    fun `replacing a playlist writes its rows in one batch`() = runBlocking {
        val dao = FakePlaylistDao()
        dao.insertPlaylistTracks(listOf(PlaylistTrackEntity(1, "old", 0, 0)))
        dao.replaceTracks(1, (0 until 50).map { PlaylistTrackEntity(1, "t$it", it, 0) })
        assertEquals((0 until 50).map { "t$it" }, dao.order(1))
        assertEquals(0, dao.singleInserts)
        assertEquals(2, dao.batchInserts)
    }

    @Test
    fun `replacing with nothing empties the playlist without an insert`() = runBlocking {
        val dao = FakePlaylistDao()
        dao.insertPlaylistTracks(listOf(PlaylistTrackEntity(1, "a", 0, 0)))
        dao.replaceTracks(1, emptyList())
        assertEquals(emptyList<String>(), dao.order(1))
        assertEquals(1, dao.batchInserts)
    }

    @Test
    fun `a heart goes to the top, a second tap takes it away`() = runBlocking {
        val dao = FakePlaylistDao()
        assertTrue(dao.toggleTrackAtTop(FAVORITES_PLAYLIST_ID, "a", 1))
        assertTrue(dao.toggleTrackAtTop(FAVORITES_PLAYLIST_ID, "b", 2))
        assertEquals(listOf("b", "a"), dao.order(FAVORITES_PLAYLIST_ID))
        assertFalse(dao.toggleTrackAtTop(FAVORITES_PLAYLIST_ID, "b", 3))
        assertEquals(listOf("a"), dao.order(FAVORITES_PLAYLIST_ID))
    }

    @Test
    fun `an added song goes to the end, and the same song twice is kept once`() = runBlocking {
        val dao = FakePlaylistDao()
        dao.appendTrack(4, "a", 0)
        dao.appendTrack(4, "b", 0)
        dao.appendTrack(4, "a", 0)
        assertEquals(listOf("a", "b"), dao.order(4))
        assertEquals(listOf(0, 1), dao.entriesOf(4).map { it.position })
    }

    @Test
    fun `several songs are taken out of a playlist in one statement, the other playlists untouched`() = runBlocking {
        val dao = FakePlaylistDao()
        dao.insertPlaylistTracks(listOf("a", "b", "c").mapIndexed { i, t -> PlaylistTrackEntity(1, t, i, 0) })
        dao.insertPlaylistTracks(listOf(PlaylistTrackEntity(2, "a", 0, 0)))
        dao.removeTracksFromPlaylist(1, listOf("a", "c"))
        assertEquals(listOf("b"), dao.order(1))
        assertEquals(listOf("a"), dao.order(2))
    }
}
