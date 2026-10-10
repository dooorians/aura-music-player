package com.ikko.aura.reco

import com.ikko.aura.data.AlbumPlaysDao
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.PlaylistRepository
import com.ikko.aura.source.SourceOrder
import com.ikko.aura.source.ytm.YtmLibrary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** The device-side [RecoStore]: Room for what is learned, the library and playlists for what is already there. */
@Singleton
class RecoStoreImpl @Inject constructor(
    private val dao: RecoDao,
    private val musicRepository: MusicRepository,
    private val ytmLibrary: YtmLibrary,
    private val playlists: PlaylistRepository,
    private val albumPlays: AlbumPlaysDao,
    private val genres: LocalGenres,
    private val prefs: RecoPreferences,
    private val sourceOrder: SourceOrder,
) : RecoStore {

    /** The device's files once the first scan has landed (a library still loading would hide duplicates), or what there is after a while. */
    private suspend fun localTracks(): List<Track> =
        kotlinx.coroutines.withTimeoutOrNull(15_000L) { musicRepository.tracks.first { it.isNotEmpty() } } ?: musicRepository.tracks.value

    private suspend fun libraryTracks(): List<Track> = (localTracks() + ytmLibrary.observeKept().first()).distinctBy { it.id }

    override suspend fun profileInputs(nowMs: Long): ProfileInputs {
        val local = localTracks()
        val kept = ytmLibrary.observeKept().first()
        val known = ytmLibrary.known()
        val byId = (local + known + kept).associateBy { it.id }
        val genreOf = genres.byTrackId()
        fun marked(entries: List<com.ikko.aura.data.playlist.PlaylistTrackEntity>) = entries
            .mapNotNull { e -> byId[e.trackId]?.let { MarkedTrack(it.id, it.title, it.artist, e.addedAtMs, it.year) } }
            .distinctBy { it.trackId }
        val albumArtist = musicRepository.albums.value.associate { it.id to it.artist }
        return ProfileInputs(
            listens = dao.listensSince(nowMs - 365L * 86_400_000L, 4_000).map { e ->
                ListenRecord(
                    trackId = e.trackId, title = e.title, artist = e.artist, album = e.album, year = e.year,
                    durationMs = e.durationMs, playedMs = e.playedMs,
                    outcome = runCatching { Outcome.valueOf(e.outcome) }.getOrDefault(Outcome.INTERRUPTED),
                    atMs = e.endedAtMs, context = e.context,
                    extraCredits = e.extra.split('\u0000').filter { it.isNotEmpty() },
                )
            },
            favorites = marked(playlists.favoriteEntries()),
            playlistAdds = marked(playlists.userPlaylistEntries()),
            library = (local + kept).distinctBy { it.id }.map {
                LibraryEntry(it.title, it.artist, it.year, genreOf[it.id], it.durationMs)
            },
            albumPlays = albumPlays.observeAll().first().mapNotNull { p ->
                albumArtist[p.albumId]?.takeIf { it.isNotBlank() }?.let { AlbumPlayRecord(it, p.playCount, p.lastPlayedAtMs) }
            },
            feedback = dao.allFeedback().mapNotNull { f ->
                val kind = runCatching { FeedbackKind.valueOf(f.kind) }.getOrNull() ?: return@mapNotNull null
                FeedbackRecord(kind, f.subject, f.artist, f.title, f.atMs, f.context)
            },
            seedArtists = prefs.seedArtists.first(),
            nowMs = nowMs,
        )
    }

    override suspend fun localIndex(): TrackIdentity.Index =
        TrackIdentity.Index(libraryTracks().map { TrackIdentity.Entry(it.title, it.artist, it.durationMs, album = it.album) })

    override suspend fun blockedArtists(): Set<String> =
        dao.allFeedback().filter { it.kind == FeedbackKind.BLOCK_ARTIST.name }.mapTo(HashSet()) { it.subject }

    override suspend fun hidden(): Set<String> {
        val out = HashSet<String>()
        for (f in dao.allFeedback()) {
            if (f.kind == FeedbackKind.DISLIKE.name || f.kind == FeedbackKind.HIDE.name || f.kind == FeedbackKind.KNOWN.name) {
                out += f.subject
                out += TrackIdentity.key(f.title, f.artist)
            }
        }
        return out
    }

    override suspend fun history(): Map<String, Long> = dao.lastShown().associate { it.identity to it.shownAtMs }

    override suspend fun generatedAt(): Map<RecoKind, Long> =
        dao.states().mapNotNull { s -> RecoKind.ofKey(s.kindKey)?.let { it to s.generatedAtMs } }.toMap()

    override suspend fun sourceOrder(): List<String> = sourceOrder.order.first()

    override suspend fun commit(kind: RecoKind, picks: List<Pick>, nowMs: Long) {
        val tracks = picks.map { it.candidate.toTrack() }
        if (android.util.Log.isLoggable("Reco", android.util.Log.DEBUG)) {
            android.util.Log.d("Reco", "${kind.key}: " + picks.joinToString(" | ") { "${it.candidate.title} — ${it.candidate.artist} [${it.reason.text()}]" })
        }
        // The songs are named by the catalogue so the playlist, the queue and a restart can show them.
        ytmLibrary.remember(tracks)
        playlists.replaceAutoPlaylist(kind.key, kind.title, tracks, nowMs)
        dao.clearItems(kind.key)
        dao.putItems(
            picks.mapIndexed { i, p ->
                val c = p.candidate
                RecoItemEntity(
                    kindKey = kind.key, position = i, trackId = c.id, title = c.title, artist = c.artist, album = c.album,
                    durationMs = c.durationMs, artworkUrl = c.artworkUrl.orEmpty(), albumRef = c.albumRef.orEmpty(),
                    sourceId = c.sourceId, sourceName = c.sourceName, reason = p.reason.encode(),
                    verified = c.playability == Playability.VERIFIED,
                )
            },
        )
        dao.putHistory(picks.map { HistoryEntity(it.candidate.identity, kind.key, nowMs) })
        dao.pruneHistory(nowMs - 120L * 86_400_000L)
        dao.putState(RecoStateEntity(kind.key, generatedAtMs = nowMs, attemptedAtMs = nowMs, status = "ok"))
    }

    override suspend fun keep(kind: RecoKind, status: String, nowMs: Long) {
        val previous = dao.states().firstOrNull { it.kindKey == kind.key }
        dao.putState(RecoStateEntity(kind.key, generatedAtMs = previous?.generatedAtMs ?: 0L, attemptedAtMs = nowMs, status = status))
    }
}
