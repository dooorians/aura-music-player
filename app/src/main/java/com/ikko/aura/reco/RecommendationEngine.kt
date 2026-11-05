package com.ikko.aura.reco

import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.PlaylistRepository
import com.ikko.aura.reco.authority.CompilationTitles
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
    lastFm: com.ikko.aura.lastfm.LastFm,
    private val smart: SmartTracker,
    val authority: com.ikko.aura.reco.authority.ArtistAuthority,
    val verifier: com.ikko.aura.reco.authority.DiscographyVerifier,
    val deezerClient: com.ikko.aura.data.deezer.DeezerClient,
    private val songFeatures: SongFeatureService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pipeline = RecoPipeline(
        store = store,
        sources = { sources.all() },
        log = { android.util.Log.d("Reco", it) },
        insight = lastFm,
        authority = authority,
        verifier = verifier,
        deezerClient = deezerClient,
        features = songFeatures,
    )

    init {
        scope.launch {
            try {
                ensureCurrentRules()
                if (prefs.consent.first()) {
                    refreshNow()
                }
            } catch (e: Exception) {
                android.util.Log.w("Reco", "Startup renewal failed", e)
            }
        }
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val consent: Flow<Boolean> = prefs.consent
    val seedArtists: Flow<List<String>> = prefs.seedArtists

    /** The last generated songs of each playlist, without the artists blocked since. */
    val items: Flow<Map<String, List<RecoItem>>> = kotlinx.coroutines.flow.combine(dao.observeAllItems(), dao.observeBlocked(), dao.observeFeedback()) { rows, blocked, feedback ->
        val keys = blocked.mapTo(HashSet()) { it.subject }
        // What was refused stays out of what is shown, whatever a renewal put back: the albums, and the songs one by one.
        val refusedAlbums = feedback.filter { it.kind == FeedbackKind.DISLIKE_ALBUM.name }.mapTo(HashSet()) { it.subject }
        val refusedSongs = feedback.filter { it.kind == FeedbackKind.DISLIKE.name }.mapTo(HashSet()) { it.subject }
        rows.groupBy { it.kindKey }.mapValues { (_, list) ->
            list.map { r ->
                RecoItem(
                    r.trackId, r.title, r.artist, r.album, r.durationMs, r.artworkUrl, r.albumRef, r.sourceId, r.sourceName,
                    Reason.decode(r.reason), r.verified,
                )
            }.filterNot { ArtistNames.involves(it.credits, it.artist, keys) }
             .filterNot { r -> CompilationTitles.matches(r.album) || CompilationTitles.matches(r.title) }
             .filterNot { r -> r.trackId in refusedSongs || AlbumKeys.of(r.albumRef, r.album, r.artist).any { it in refusedAlbums } }
        }
    }.flowOn(Dispatchers.Default)

    val status: Flow<Map<String, RecoStatus>> = dao.observeStates().map { list ->
        list.associate { it.kindKey to RecoStatus(it.generatedAtMs, it.attemptedAtMs, it.status) }
    }

    /** The albums the listener refused as a whole: what must stay out of every row, whoever offers it. */
    val refusedAlbumKeys: Flow<Set<String>> = dao.observeFeedback().map { rows ->
        rows.filter { it.kind == FeedbackKind.DISLIKE_ALBUM.name }.mapTo(HashSet()) { it.subject }
    }

    /** The albums the listener liked as a whole: the keys each answers to, for the menu to show a liked album as liked. */
    val likedAlbumKeys: Flow<Set<String>> = dao.observeFeedback().map { rows ->
        rows.filter { it.kind == FeedbackKind.LIKE_ALBUM.name }.mapTo(HashSet()) { it.subject }
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
        if (prefs.algorithmVersion() >= RULES_VERSION) return
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

    /** Every listen since [sinceMs], the device's and the sources' alike, newest first. */
    suspend fun listensSince(sinceMs: Long): List<ListenEntity> = dao.listensSince(sinceMs, 6_000)

    /** What "Ton profil" shows: the profile the recommendations are drawn from, most-loved first. */
    suspend fun profileSummary(): TasteSummary = TasteProfileBuilder.build(store.profileInputs(System.currentTimeMillis())).summary()

    /** A renewal that goes on in the background (a screen does not wait for it); the settings show it is running. */
    fun refreshInBackground() { scope.launch { runCatching { refreshNow() } } }

    fun launchRefreshIfDue() { scope.launch { runCatching { refreshIfDue() } } }

    suspend fun setConsent(on: Boolean) = prefs.setConsent(on)
    suspend fun setSeedArtists(names: List<String>) {
        prefs.setSeedArtists(names)
        // An artist taken out of the list is no longer a chosen one.
        val keep = names.mapTo(HashSet()) { ArtistNames.key(it) }
        prefs.setPickedArtists(prefs.pickedArtists.first().filter { ArtistNames.key(it) in keep })
    }

    val pickedArtists: Flow<List<String>> = prefs.pickedArtists

    /**
     * The taste picker's choice: these artists weigh as much as a deliberate like in everything that follows (the profile, the
     * playlists, the Home rows, the queue's next songs), and what was chosen stays in "Artistes que tu aimes".
     */
    suspend fun artistsPicked(names: List<String>) {
        val all = (names + prefs.seedArtists.first()).distinctBy { ArtistNames.key(it) }
        prefs.setSeedArtists(all)
        prefs.setPickedArtists((names + prefs.pickedArtists.first()).distinctBy { ArtistNames.key(it) })
        names.forEach { smart.artistPicked(it) }
        // Who they are exactly is settled now, while the listener is choosing, and kept.
        scope.launch { names.forEach { runCatching { authority.pin(it) } } }
    }

    /** The artists chosen before their identity was settled (an earlier version): settled now, in the background, one at a time. */
    fun pinChosenArtists() {
        scope.launch {
            val all = (prefs.seedArtists.first() + prefs.pickedArtists.first()).distinctBy { ArtistNames.key(it) }
            all.filterNot { authority.isPinned(it) }.forEach { runCatching { authority.pin(it) } }
        }
    }

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
        smart.liked(track)
    }

    suspend fun unlike(track: Track) {
        dao.removeFeedback(FeedbackKind.LIKE.name, track.id)
        smart.unliked(track)
    }

    /** "Je n'aime pas": this song is never proposed again, and its artist is nudged down in [context], not excluded. */
    suspend fun dislike(track: Track, context: String?) {
        // A refusal takes back a like: a song cannot be both.
        dao.removeFeedback(FeedbackKind.LIKE.name, track.id)
        answer(FeedbackKind.DISLIKE, track, context)
        smart.disliked(track)
    }

    /** "Je n'aime pas" on an album: each of its songs is refused, and the album is gone from what is shown. */
    suspend fun dislikeAlbum(ref: String?, title: String, artist: String, tracks: List<Track>, context: String?) {
        // The album itself is refused, and kept so: it does not come back at the next renewal, nor at the next start, whichever songs
        // of it a source offers. It is written under every key it answers to (its reference and its name): a row of Home knows it by
        // one or the other. Each of the songs known is refused too (the queue and the profile learn from them).
        AlbumKeys.of(ref, title, artist).forEach { key ->
            dao.removeFeedback(FeedbackKind.LIKE_ALBUM.name, key)
            dao.putFeedback(FeedbackEntity(FeedbackKind.DISLIKE_ALBUM.name, key, artist, title, System.currentTimeMillis(), context))
        }
        tracks.forEach { dislike(it, context) }
        dao.removeItemsOfAlbum(ref.orEmpty(), title, artist)
        // The artist goes with it: no other album of theirs is proposed either, whoever they are, the library's own artists included
        // (their files stay on the device; only what is proposed changes). They can be let back from the list of blocked artists.
        val name = ArtistNames.primary(artist, beforeAmpersand = true)
        if (name.isNotBlank() && !NOT_AN_ARTIST.matches(ArtistNames.key(name))) blockArtist(name)
    }

    /** "J'aime" on an album: it counts for the album itself, even when its songs cannot be fetched, and for each song known. */
    suspend fun likeAlbum(ref: String?, title: String, artist: String, tracks: List<Track>, context: String?) {
        AlbumKeys.of(ref, title, artist).forEach { key ->
            dao.removeFeedback(FeedbackKind.DISLIKE_ALBUM.name, key)
            dao.putFeedback(FeedbackEntity(FeedbackKind.LIKE_ALBUM.name, key, artist, title, System.currentTimeMillis(), context))
        }
        tracks.forEach { like(it, context) }
        // The songs carry the artist's affinity when there are any; with none, the album does.
        if (tracks.isEmpty()) smart.artistLiked(artist)
    }

    suspend fun unlikeAlbum(ref: String?, title: String, artist: String, tracks: List<Track>) {
        AlbumKeys.of(ref, title, artist).forEach { dao.removeFeedback(FeedbackKind.LIKE_ALBUM.name, it) }
        tracks.forEach { unlike(it) }
    }

    /** Hides a song without saying anything about the artist or the genre. */
    suspend fun hide(track: Track) = answer(FeedbackKind.HIDE, track, null)

    /** "Déjà connu": not a rejection — the song just is not a discovery. */
    suspend fun markKnown(track: Track) = answer(FeedbackKind.KNOWN, track, null)

    /** Excludes [name] from all seven playlists, collaborations included, and takes their songs out of those shown. */
    suspend fun blockArtist(name: String) {
        val key = ArtistNames.key(name)
        if (key.isEmpty()) return
        dao.putFeedback(FeedbackEntity(FeedbackKind.BLOCK_ARTIST.name, key, name.trim(), "", System.currentTimeMillis(), null))
        smart.artistBlocked(name, true)
        val blocked = store.blockedArtists()
        val shown = dao.observeAllItems().first()
        val gone = shown.filter { ArtistNames.involves(ArtistNames.creditsOf(it.artist, it.title), it.artist, blocked) }.map { it.trackId }
        playlists.removeFromAutoPlaylists(gone)
        dao.removeItems(gone)
    }

    suspend fun unblockArtist(key: String) {
        dao.removeFeedback(FeedbackKind.BLOCK_ARTIST.name, key)
        smart.artistBlocked(key, false)
    }

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
        const val RULES_VERSION = 16
        /** What a source puts in the artist field when an album has none: nobody to block. */
        val NOT_AN_ARTIST = Regex("^(?:various(?: artists)?|va|v a|divers|artistes? divers|youtube music|jiosaavn|unknown|unknown artist|artiste inconnu)$")
    }
}
