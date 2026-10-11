package com.ikko.aura.reco

import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.PlaylistRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A song of a generated playlist with the reason it is there. */
data class RecoItem(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkUrl: String,
    val albumRef: String,
    val sourceId: String,
    val sourceName: String,
    val reason: Reason?,
    val verified: Boolean,
) {
    val credits: List<String> = ArtistNames.creditsOf(artist, title)
}

data class BlockedArtist(val key: String, val name: String, val atMs: Long)

/** When a playlist was last made and how its last attempt went. */
data class RecoStatus(val generatedAtMs: Long, val attemptedAtMs: Long, val status: String)

/**
 * The recommendations of the app: owns the renewal, the listener's answers and what the UI reads. The renewal itself
 * is [RecoPipeline]; this class adds consent, a single run at a time, and the answers.
 */
@Singleton
class RecommendationEngine @Inject constructor(
    private val dao: RecoDao,
    private val store: RecoStoreImpl,
    private val sources: ExternalSources,
    private val prefs: RecoPreferences,
    private val playlists: PlaylistRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pipeline = RecoPipeline(store, { sources.all() }, log = { android.util.Log.d("Reco", it) })

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val consent: Flow<Boolean> = prefs.consent
    val seedArtists: Flow<List<String>> = prefs.seedArtists

    /** The last generated songs of each playlist, without the artists blocked since. */
    val items: Flow<Map<String, List<RecoItem>>> = kotlinx.coroutines.flow.combine(dao.observeAllItems(), dao.observeBlocked()) { rows, blocked ->
        val keys = blocked.mapTo(HashSet()) { it.subject }
        rows.groupBy { it.kindKey }.mapValues { (_, list) ->
            list.map { r ->
                RecoItem(
                    r.trackId, r.title, r.artist, r.album, r.durationMs, r.artworkUrl, r.albumRef, r.sourceId, r.sourceName,
                    Reason.decode(r.reason), r.verified,
                )
            }.filterNot { ArtistNames.involves(it.credits, it.artist, keys) }
        }
    }.flowOn(Dispatchers.Default)

    val status: Flow<Map<String, RecoStatus>> = dao.observeStates().map { list ->
        list.associate { it.kindKey to RecoStatus(it.generatedAtMs, it.attemptedAtMs, it.status) }
    }

    val blockedArtists: Flow<List<BlockedArtist>> = dao.observeBlocked().map { rows -> rows.map { BlockedArtist(it.subject, it.artist, it.atMs) } }

    /** Renews what is due, when the listener agreed to it. Safe to call as often as wanted. */
    suspend fun refreshIfDue(): RunResult? {
        if (!prefs.consent.first()) return null
        return run(null, force = false)
    }

    /** A renewal now, of [kind] or of all seven, that keeps every preference. */
    suspend fun refreshNow(kind: RecoKind? = null): RunResult? {
        if (!prefs.consent.first()) return null
        return run(kind?.let { setOf(it) }, force = true)
    }

    /**
     * Playlists made by older selection rules are not kept "because a source failed": when the rules change, what they
     * made is discarded and made again (what was learned, and what the listener answered, stays).
     */
    private suspend fun ensureCurrentRules() {
        if (prefs.algorithmVersion() == RULES_VERSION) return
        dao.clearAllItems()
        dao.clearStates()
        prefs.setAlgorithmVersion(RULES_VERSION)
    }

    private suspend fun run(kinds: Set<RecoKind>?, force: Boolean): RunResult = mutex.withLock {
        _refreshing.value = true
        try {
            ensureCurrentRules()
            pipeline.refresh(kinds, force).also { android.util.Log.i("Reco", "renewal (force=$force): $it") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Reco", "renewal failed", e)
            RunResult(RunStatus.SOURCES_FAILED)
        } finally {
            _refreshing.value = false
        }
    }

    fun launchRefreshIfDue() { scope.launch { runCatching { refreshIfDue() } } }

    suspend fun setConsent(on: Boolean) = prefs.setConsent(on)
    suspend fun setSeedArtists(names: List<String>) = prefs.setSeedArtists(names)

    // --- the listener's answers ---

    private suspend fun answer(kind: FeedbackKind, track: Track, context: String?) {
        dao.putFeedback(FeedbackEntity(kind.name, track.id, track.artist, track.title, System.currentTimeMillis(), context))
        playlists.removeFromAutoPlaylists(listOf(track.id))
        dao.removeItems(listOf(track.id))
    }

    /** The ids of the songs the listener liked: the heart in the player. */
    val likedTrackIds: Flow<Set<String>> = dao.observeFeedback().map { rows ->
        rows.filter { it.kind == FeedbackKind.LIKE.name }.mapTo(HashSet()) { it.subject }
    }

    /** A like: a strong, lasting sign of taste — its artist and the song itself weigh in the profile from the next renewal. */
    suspend fun like(track: Track, context: String?) {
        dao.removeFeedback(FeedbackKind.DISLIKE.name, track.id)
        dao.removeFeedback(FeedbackKind.HIDE.name, track.id)
        dao.putFeedback(FeedbackEntity(FeedbackKind.LIKE.name, track.id, track.artist, track.title, System.currentTimeMillis(), context))
    }

    suspend fun unlike(track: Track) = dao.removeFeedback(FeedbackKind.LIKE.name, track.id)

    /** "Je n'aime pas": this song is never proposed again, and its artist is nudged down in [context], not excluded. */
    suspend fun dislike(track: Track, context: String?) = answer(FeedbackKind.DISLIKE, track, context)

    /** Hides a song without saying anything about the artist or the genre. */
    suspend fun hide(track: Track) = answer(FeedbackKind.HIDE, track, null)

    /** "Déjà connu": not a rejection — the song just is not a discovery. */
    suspend fun markKnown(track: Track) = answer(FeedbackKind.KNOWN, track, null)

    /** Excludes [name] from all seven playlists, collaborations included, and takes their songs out of those shown. */
    suspend fun blockArtist(name: String) {
        val key = ArtistNames.key(name)
        if (key.isEmpty()) return
        dao.putFeedback(FeedbackEntity(FeedbackKind.BLOCK_ARTIST.name, key, name.trim(), "", System.currentTimeMillis(), null))
        val blocked = store.blockedArtists()
        val shown = dao.observeAllItems().first()
        val gone = shown.filter { ArtistNames.involves(ArtistNames.creditsOf(it.artist, it.title), it.artist, blocked) }.map { it.trackId }
        playlists.removeFromAutoPlaylists(gone)
        dao.removeItems(gone)
    }

    suspend fun unblockArtist(key: String) = dao.removeFeedback(FeedbackKind.BLOCK_ARTIST.name, key)

    /**
     * Forgets what was learned: listens, likes and dislikes, what was proposed. Kept: the blocked artists, the artists
     * typed in and the consent — those are settings — and the library, the hearts and the playlists, which are the
     * listener's own and keep informing the profile.
     */
    suspend fun resetProfile() {
        dao.clearListens()
        dao.clearOpinions()
        dao.clearHistory()
        dao.clearStates()
        // What was proposed came from the profile that is gone: the playlists are made again from the next renewal.
        dao.clearAllItems()
    }

    private companion object {
        /** Bumped when the rules that choose songs change in a way that makes earlier playlists wrong. */
        const val RULES_VERSION = 4
    }
}
