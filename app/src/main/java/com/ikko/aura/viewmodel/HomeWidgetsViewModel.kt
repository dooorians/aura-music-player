package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.AlbumPlayEntity
import com.ikko.aura.data.FavoritesStore
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.RecentlyPlayedTracker
import com.ikko.aura.data.SettingsStore
import com.ikko.aura.data.home.CardAmbience
import com.ikko.aura.data.home.HomeSourceData
import com.ikko.aura.data.home.HomeWidget
import com.ikko.aura.data.home.HomeWidgetResolver
import com.ikko.aura.data.home.HomeWidgetRules
import com.ikko.aura.data.home.WidgetContent
import com.ikko.aura.data.home.WidgetKind
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.PlaylistRepository
import com.ikko.aura.data.playlist.PlaylistSummary
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.source.ytm.YtmLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay

/** One "Pour toi" card: what it says, the mood it is dressed in, and what it plays or opens. */
@Immutable
data class ForYouCard(
    val id: String,
    val ambience: CardAmbience,
    val heading: String,
    val kicker: String,
    val artists: String,
    val content: WidgetContent,
)

@Immutable
data class UserPlaylistCard(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val artworkUris: List<android.net.Uri> = emptyList(),
)

@Immutable
data class ForYouUiState(
    /** False until the library has been read: nothing is drawn before that, rather than an empty Home. */
    val loaded: Boolean = false,
    val cards: List<ForYouCard> = emptyList(),
    /** The albums played lately, most recent first. */
    val recent: List<Album> = emptyList(),
    /** What the rows were drawn from: asked again for what takes the place of a refused album when a row's reserve has run out. */
    val rotation: com.ikko.aura.data.home.RotationContext? = null,
    /** Onglet "Reprendre": derniers albums/titres écoutés. */
    val resumeAlbums: List<Album> = emptyList(),
    /** When each album was last played (epoch ms), for the "Il y a 2 h" line of the recents. */
    val lastPlayedAt: Map<Long, Long> = emptyMap(),
    /** Onglet "Favoris": albums favoris + albums des titres favoris + albums les plus écoutés. */
    val favoriteAlbums: List<Album> = emptyList(),
    /** Onglet "Nouveautés": albums les plus récents. */
    val newAlbums: List<Album> = emptyList(),
    /** Onglet "Découvertes": albums à redécouvrir. */
    val discoveryAlbums: List<Album> = emptyList(),
    /** Titres "En boucle" (derniers titres écoutés / favoris / plus écoutés). */
    val quickTracks: List<Track> = emptyList(),
    /** Playlists créées par l'utilisateur. */
    val userPlaylists: List<UserPlaylistCard> = emptyList(),
    /** Every card there is, in the listener's order, hidden ones included: what the organiser shows. */
    val layout: List<CardSlot> = emptyList(),
    /** The rows under the mixes: what is played again and what the external sources offer for it (see [HomeSections]). */
    val sections: List<com.ikko.aura.data.home.HomeSection> = emptyList(),
    /** The draw [sections] were made with: they are kept still once seen, and made again only when this changes. */
    val sectionsSeed: Long = 0L,
)

@Immutable
data class CardSlot(val id: String, val heading: String, val hidden: Boolean)

/**
 * The state of the Home: the "Pour toi" cards, each one a selection of the library the resolver already knows how to
 * make (favourites, what has not been played lately, the most played, the day's mix, albums by the artists you play most),
 * and the albums played lately.
 */
@HiltViewModel
class HomeWidgetsViewModel @Inject constructor(
    repository: MusicRepository,
    recentlyPlayedTracker: RecentlyPlayedTracker,
    favoritesStore: FavoritesStore,
    private val ytmLibrary: YtmLibrary,
    private val playlistRepository: PlaylistRepository,
    private val settingsStore: SettingsStore,
    private val recommendations: com.ikko.aura.reco.RecommendationEngine,
    private val ytmClient: com.ikko.aura.source.ytm.YtmClient,
    private val artistAuthority: com.ikko.aura.reco.authority.ArtistAuthority,
    private val albumRefs: com.ikko.aura.source.ytm.AlbumRefResolver,
    private val albumResolver: com.ikko.aura.source.StreamedAlbumResolver,
    private val smartDao: com.ikko.aura.reco.SmartDao,
    private val enricher: com.ikko.aura.reco.LastFmEnricher,
    private val deezer: com.ikko.aura.data.deezer.DeezerClient,
    private val acoustics: com.ikko.aura.reco.AcousticBridge,
) : ViewModel() {

    /**
     * What draws the rows between the fixed ones (see [com.ikko.aura.data.home.HomeRotation]): chosen once for the session, so Home
     * does not rearrange itself on a recomposition or a trip to another page; [reshuffleSections] draws another set.
     */
    private val rotationSeed = MutableStateFlow(kotlin.random.Random.nextLong())

    /** "Ne plus recommander": the artist is blocked (everywhere, with those that sound like them), and the renewal makes up for it. */
    fun blockArtist(name: String) {
        viewModelScope.launch {
            runCatching { recommendations.blockArtist(name) }
            recommendations.refreshInBackground()
        }
    }

    /** The albums most listened to in each genre of the catalogue, from Deezer's charts, fetched once and in the background. */
    private val chartAlbums = MutableStateFlow<Map<Int, List<com.ikko.aura.data.deezer.DeezerChartAlbum>>>(emptyMap())
    /** The albums of the artists the listener chose, from their discographies: what the rows of Home are made from beside what the engine found. */
    private val chosenAlbums = MutableStateFlow<List<com.ikko.aura.data.deezer.DeezerChartAlbum>>(emptyList())
    /** Set once every chart has been asked for (found or not), and once the styles of the first artists have been looked up. */
    private val chartsDone = MutableStateFlow(false)
    private val tagsDone = MutableStateFlow(false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (id in CHART_GENRES) {
                val albums = runCatching { deezer.chartAlbums(id) }.getOrDefault(emptyList())
                if (albums.isNotEmpty()) chartAlbums.value = chartAlbums.value + (id to albums)
            }
            chartsDone.value = true
        }
        // The artists chosen before their identity was settled get it now (in the background): what follows relies on it.
        recommendations.pinChosenArtists()
        // The albums of the chosen artists, a few at a time (Deezer is asked slowly, see its client).
        viewModelScope.launch(Dispatchers.IO) {
            val names = (recommendations.pickedArtists.first() + recommendations.seedArtists.first())
                .filter { it.isNotBlank() }.distinctBy { com.ikko.aura.reco.ArtistNames.primaryKey(it) }.take(CHOSEN_ARTISTS_ASKED)
            val gate = kotlinx.coroutines.sync.Semaphore(3)
            val found = java.util.Collections.synchronizedList(ArrayList<com.ikko.aura.data.deezer.DeezerArtistProfile>())
            kotlinx.coroutines.coroutineScope {
                names.map { name ->
                    async {
                        gate.withPermit {
                            // The artist of that name, among the namesakes (see ArtistAuthority.deezerArtist): never the first answer of a search.
                            val profile = runCatching { artistAuthority.deezerArtist(com.ikko.aura.reco.ArtistNames.askName(name)) }.getOrNull()
                            profile?.let { found += it }
                            val albums = profile?.let { runCatching { deezer.artistAlbumsWithCovers(it.id, it.name) }.getOrDefault(emptyList()) }.orEmpty()
                            if (albums.isNotEmpty()) chosenAlbums.value = chosenAlbums.value + albums
                        }
                    }
                }.awaitAll()
            }
            chosenDone.value = true
            // Home is shown; the stock keeps growing behind it, which is what takes the place of an album that is refused.
            runCatching { growStock(names, found.toList(), gate) }
        }
    }

    private val chosenDone = MutableStateFlow(false)

    /**
     * More albums for Home, once the first have come in: the artists the listener really plays (the engine's affinity, beyond the ones they
     * chose), then the artists Deezer lists as close to the ones they like most, with their albums. A few artists at a time and published by
     * batch, so Home is not drawn again for every one of them. Nothing here is blocked, refused or owned: that is set aside where the albums are used.
     */
    private suspend fun growStock(
        asked: List<String>,
        chosen: List<com.ikko.aura.data.deezer.DeezerArtistProfile>,
        gate: kotlinx.coroutines.sync.Semaphore,
    ) {
        val blocked = recommendations.blockedArtists.first().mapTo(HashSet()) { it.key }
        val done = asked.mapTo(HashSet()) { com.ikko.aura.reco.ArtistNames.primaryKey(it) }
        val played = runCatching { smartDao.allArtists() }.getOrDefault(emptyList())
            .filter { !it.isBlocked && it.affinityScore > 0.0 && it.artistKey !in done && it.artistKey !in blocked }
            .sortedByDescending { it.affinityScore }.take(PLAYED_ARTISTS_ASKED)
        val playedProfiles = java.util.Collections.synchronizedList(ArrayList<com.ikko.aura.data.deezer.DeezerArtistProfile>())
        played.chunked(STOCK_BATCH).forEach { batch ->
            val albums = kotlinx.coroutines.coroutineScope {
                batch.map { meta ->
                    async {
                        gate.withPermit {
                            val profile = runCatching { artistAuthority.deezerArtist(com.ikko.aura.reco.ArtistNames.askName(meta.name)) }.getOrNull()
                            profile?.let { playedProfiles += it }
                            profile?.let { runCatching { deezer.artistAlbumsWithCovers(it.id, it.name) }.getOrDefault(emptyList()) }.orEmpty()
                        }
                    }
                }.awaitAll().flatten()
            }
            if (albums.isNotEmpty()) chosenAlbums.value = chosenAlbums.value + albums
            fetchTags(batch.map { it.name })
        }
        // The artists close to the ones they like most.
        val seeds = (chosen + playedProfiles).distinctBy { it.id }.sortedByDescending { it.nbFan }.take(RELATED_FROM)
        val known = (done + played.map { it.artistKey } + blocked).toHashSet()
        val related = seeds.flatMap { s -> runCatching { gate.withPermit { deezer.getRelatedArtists(s.id, RELATED_PER_ARTIST) } }.getOrDefault(emptyList()) }
            .distinctBy { it.id }
            .filter { com.ikko.aura.reco.ArtistNames.primaryKey(it.name).let { k -> k.isNotEmpty() && k !in known } }
            .take(RELATED_ARTISTS_ASKED)
        related.chunked(STOCK_BATCH).forEach { batch ->
            val albums = kotlinx.coroutines.coroutineScope {
                batch.map { p -> async { gate.withPermit { runCatching { deezer.artistAlbumsWithCovers(p.id, p.name, limit = RELATED_ALBUMS) }.getOrDefault(emptyList()) } } }.awaitAll().flatten()
            }
            if (albums.isNotEmpty()) chosenAlbums.value = chosenAlbums.value + albums
            fetchTags(batch.map { it.name })
        }
        // The artists of the genres' charts: they fill a row of a style, so each is placed by its own tags first.
        fetchTags(chartAlbums.value.values.flatten().map { it.artist })
    }

    /** An explicit refresh: another set of rows. */
    fun reshuffleSections() {
        sectionsHeld.value = null
        rotationSeed.value = kotlin.random.Random.nextLong()
        refreshCarousels()
        freezeSectionsWhenReady()
    }

    /** The rows of Home once they have settled (the styles and the charts have come in): kept as they are for the whole session. */
    private val sectionsHeld = MutableStateFlow<List<com.ikko.aura.data.home.HomeSection>?>(null)
    private val sectionsSettled = MutableStateFlow(false)
    private var freezeJob: kotlinx.coroutines.Job? = null

    init {
        // Not before what the rows are made from has come in, or they would be drawn once and then again: the charts, at most a few seconds.
        viewModelScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(SETTLE_MS) { combine(chartsDone, tagsDone, chosenDone) { a, b, c -> a && b && c }.first { it } }
            sectionsSettled.value = true
        }
    }

    /** Artist key → the tags Last.fm gives it, for the artists the sources offer: the rows of themes are drawn from them. */
    private val externalTags = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /**
     * Fetches, a few at a time, the tags of artists whose albums Home may offer and whose tags are not known yet: without them an album cannot be
     * placed in a row of a style (and is left out of it, rather than put where it does not belong).
     */
    private suspend fun fetchTags(names: Collection<String>) {
        val missing = names.filter { it.isNotBlank() }.distinctBy { com.ikko.aura.reco.ArtistNames.key(it) }
            .filter { externalTags.value[com.ikko.aura.reco.ArtistNames.key(it)].isNullOrEmpty() }
            .take(TAGS_PER_ROUND)
        if (missing.isEmpty()) return
        val gate = kotlinx.coroutines.sync.Semaphore(8)
        kotlinx.coroutines.coroutineScope {
            missing.chunked(20).forEach { batch ->
                val found = batch.map { n -> async { gate.withPermit { com.ikko.aura.reco.ArtistNames.key(n) to runCatching { enricher.tagsOf(n) }.getOrDefault(emptySet()) } } }.awaitAll()
                externalTags.value = externalTags.value + found
            }
        }
    }

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            recommendations.items.collect { items ->
                val gate = kotlinx.coroutines.sync.Semaphore(8)
                val chosenNames = (recommendations.pickedArtists.first() + recommendations.seedArtists.first()).map { com.ikko.aura.reco.ArtistNames.askName(it) }
                val missing = (chosenNames + items.values.flatten().map { com.ikko.aura.reco.ArtistNames.primary(it.artist) })
                    .filter { it.isNotBlank() }.distinctBy { com.ikko.aura.reco.ArtistNames.key(it) }
                    .filter { com.ikko.aura.reco.ArtistNames.key(it) !in externalTags.value || externalTags.value[com.ikko.aura.reco.ArtistNames.key(it)].isNullOrEmpty() }
                    .take(220)
                kotlinx.coroutines.coroutineScope {
                    missing.chunked(20).forEach { batch ->
                        val found = batch.map { n -> async { gate.withPermit { com.ikko.aura.reco.ArtistNames.key(n) to runCatching { enricher.tagsOf(n) }.getOrDefault(emptySet()) } } }.awaitAll()
                        externalTags.value = externalTags.value + found
                    }
                }
                tagsDone.value = true
            }
        }
    }

    init {
        // What Last.fm says of the library's artists is fetched in the background, a few at a time: the style rows are drawn from it.
        enricher.enrichLibrary()
    }

    /**
     * The page reference of a Home album: the one the source gave, else the album found by its name, else the album its first
     * song is on (looked up from the song, which finds albums whose name the search does not match, "<Hyper-Ego>").
     */
    suspend fun albumRefOf(item: com.ikko.aura.data.home.SectionAlbum): String? {
        item.ref?.let { return it }
        albumRefFor(item.title, item.artist)?.let { return it }
        val track = item.items.firstOrNull()?.let { with(com.ikko.aura.reco.RecoItemTracks) { it.toTrack() } } ?: return null
        val route = albumResolver.routeOf(track) ?: return null
        val path = route.substringBefore('?').split('/')
        return when (path.firstOrNull()) {
            "ytm_album" -> path.getOrNull(1)?.let(android.net.Uri::decode)
            "remote_album" -> if (path.size >= 3) android.net.Uri.decode(path[1]) + ":" + android.net.Uri.decode(path[2]) else null
            else -> null
        }
    }

    /**
     * The page of an external album that came with no reference (JioSaavn gives none): found on YouTube Music by its title and
     * artist, the same record only. Null when that source is off, does not answer or does not have it.
     */
    /**
     * Has the page of a YouTube Music album read before it opens (the client keeps it a few minutes): the page then draws at once,
     * and the opening animation plays on a page that is there rather than on an empty one that is still loading.
     */
    private val preparing = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Boolean>>()

    suspend fun prepareAlbum(ref: String): Boolean {
        if (ref.contains(':')) return true
        // One read at a time for a page, whoever asks (the finger going down on it, then the tap): the second asks wait for the first.
        val job = preparing.getOrPut(ref) {
            viewModelScope.async(Dispatchers.IO) {
                runCatching { ytmClient.album(ref).songs.isNotEmpty() }.getOrDefault(false) ||
                    runCatching { ytmLibrary.downloadedOfAlbum(ref).isNotEmpty() }.getOrDefault(false)
            }.also { d -> d.invokeOnCompletion { preparing.remove(ref) } }
        }
        return job.await()
    }

    private val spacesRegex = Regex("\\s+")

    suspend fun albumRefFor(title: String, artist: String): String? = albumRefs.find(title, artist)

    /**
     * The song of a Home tile that has no album page on the source (a single, a remix): found by its title and artist, the same
     * record only. What the tile plays when there is nothing to open.
     */
    suspend fun songFor(title: String, artist: String): com.ikko.aura.data.model.Track? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching {
            fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            val want = norm(title)
            val credits = com.ikko.aura.reco.ArtistNames.split(artist)
            ytmClient.search("$title $artist").songs
                .firstOrNull { s -> norm(s.title) == want && com.ikko.aura.reco.ArtistNames.anyCompatible(credits, com.ikko.aura.reco.ArtistNames.split(s.artist)) }
                ?.let(com.ikko.aura.source.ytm.YtmLibrary::trackOf)
        }.getOrNull()
    }

    init {
        // Renewals that are due run when the app is opened; and at once when the listener turns the switch on.
        viewModelScope.launch {
            recommendations.consent.collect { on -> if (on) recommendations.launchRefreshIfDue() }
        }
    }

    /** Whether a renewal is running, and a manual renewal of everything. */
    val recoRefreshing: StateFlow<Boolean> = recommendations.refreshing
    fun refreshRecommendations() { reshuffleSections(); viewModelScope.launch { recommendations.refreshNow() } }

    val profileImageUri: StateFlow<String?> = settingsStore.profileImageUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Crée une nouvelle playlist utilisateur et renvoie son identifiant. */
    fun createUserPlaylist(name: String, onCreated: (Long) -> Unit = {}) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = playlistRepository.createPlaylist(trimmed)
            onCreated(id)
        }
    }

    /** Moves a card one place up (-1) or down (+1) in the organiser. */
    fun moveCard(id: String, delta: Int) {
        val ids = uiState.value.layout.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from < 0 || from == to) return
        ids.add(to, ids.removeAt(from))
        viewModelScope.launch { settingsStore.setHomeCardOrder(ids) }
    }

    fun setCardHidden(id: String, hidden: Boolean) {
        val now = uiState.value.layout.filter { it.hidden }.map { it.id }.toMutableSet()
        if (hidden) now += id else now -= id
        viewModelScope.launch { settingsStore.setHomeCardHidden(now) }
    }

    /** Saves the order the listener dropped the cards in. */
    fun setCardOrder(ids: List<String>) {
        viewModelScope.launch { settingsStore.setHomeCardOrder(ids) }
    }

    val uiState: StateFlow<ForYouUiState> = combine(
        repository.tracks,
        repository.albums,
        recentlyPlayedTracker.recentAlbumIds,
        favoritesStore.favoriteAlbumIds,
        recentlyPlayedTracker.playCounts,
        recentlyPlayedTracker.recentTrackIds,
        ytmLibrary.observeDoneKeys(),
        ytmLibrary.observeKept(),
        playlistRepository.playlists,
        settingsStore.homeCardOrder,
        settingsStore.homeCardHidden,
        recommendations.items,
        recommendations.consent,
        recommendations.status,
        rotationSeed,
        externalTags,
        chartAlbums,
        recommendations.blockedArtists,
        recommendations.refusedAlbumKeys,
        recommendations.pickedArtists,
        recommendations.seedArtists,
        chosenAlbums,
    ) { values ->
        @Suppress("UNCHECKED_CAST") val tracks = values[0] as List<Track>
        @Suppress("UNCHECKED_CAST") val albums = values[1] as List<Album>
        @Suppress("UNCHECKED_CAST") val recentAlbumIds = values[2] as List<Long>
        @Suppress("UNCHECKED_CAST") val favoriteIds = values[3] as Set<Long>
        @Suppress("UNCHECKED_CAST") val plays = values[4] as List<AlbumPlayEntity>
        @Suppress("UNCHECKED_CAST") val recentTrackIds = values[5] as List<String>
        @Suppress("UNCHECKED_CAST") val ytmDownloads = values[6] as List<String>
        @Suppress("UNCHECKED_CAST") val kept = values[7] as List<Track>
        @Suppress("UNCHECKED_CAST") val playlists = values[8] as List<PlaylistSummary>
        @Suppress("UNCHECKED_CAST") val order = values[9] as List<String>
        @Suppress("UNCHECKED_CAST") val hidden = values[10] as Set<String>
        @Suppress("UNCHECKED_CAST") val recoItems = values[11] as Map<String, List<com.ikko.aura.reco.RecoItem>>
        val recoConsent = values[12] as Boolean
        @Suppress("UNCHECKED_CAST") val recoStatus = values[13] as Map<String, com.ikko.aura.reco.RecoStatus>
        val seed = values[14] as Long
        @Suppress("UNCHECKED_CAST") val sourceTags = values[15] as Map<String, Set<String>>
        @Suppress("UNCHECKED_CAST") val charts = values[16] as Map<Int, List<com.ikko.aura.data.deezer.DeezerChartAlbum>>
        @Suppress("UNCHECKED_CAST") val blockedNow = values[17] as List<com.ikko.aura.reco.BlockedArtist>
        @Suppress("UNCHECKED_CAST") val refusedAlbums = values[18] as Set<String>
        @Suppress("UNCHECKED_CAST") val chosenArtists = (values[19] as List<String>) + (values[20] as List<String>)
        @Suppress("UNCHECKED_CAST") val chosenPool = values[21] as List<com.ikko.aura.data.deezer.DeezerChartAlbum>

        // Streamed songs count as the library's own once played or kept, as on the old Home.
        // Streamed songs count only as kept ones (not everything ever played from a source), and only the downloaded among
        // them: one whose download was deleted is not a file any more, and its album used to linger in the recents.
        val downloadedKeys = ytmDownloads.toHashSet()
        val allTracks = (tracks + kept.filter { it.id in downloadedKeys }).distinctBy { it.id }
        val allAlbums = (albums + YtmLibrary.albumsOf(kept.filter { it.id in downloadedKeys })).distinctBy { it.id }
        val byId = allTracks.associateBy { it.id }
        val data = HomeSourceData(
            allTracks = allTracks,
            tracksByAlbum = allTracks.groupBy { it.albumId },
            albums = allAlbums,
            favoriteAlbums = allAlbums.filter { it.id in favoriteIds },
            recentTracks = recentTrackIds.mapNotNull { byId[it] },
            recentAlbumIds = recentAlbumIds.toSet(),
            albumPlays = plays.associate { it.albumId to it.playCount },
            playlists = playlists,
            day = System.currentTimeMillis() / MILLIS_PER_DAY,
        )
        val albumsById = allAlbums.associateBy { it.id }
        val ranked = CARDS.sortedBy { spec -> order.indexOf(spec.id).let { if (it < 0) Int.MAX_VALUE else it } }
        val built = ranked.mapNotNull { spec ->
                // "Tes préférés": the songs the listener hearted, in the playlist they live in (which the card opens).
                if (spec.id == "fav") {
                    val liked = playlists.firstOrNull { it.isFavorites }?.trackIds.orEmpty().mapNotNull { byId[it] }
                    if (liked.isEmpty()) return@mapNotNull null
                    return@mapNotNull ForYouCard(
                        spec.id, spec.ambience, spec.heading, spec.kicker, artistsOf(liked),
                        WidgetContent(
                            title = spec.heading, subtitle = null, artwork = emptyList(), tracks = liked.take(MIX_SIZE), hue = 0f,
                            openRoute = AuraDestination.PlaylistPattern.route(com.ikko.aura.data.playlist.FAVORITES_PLAYLIST_ID.toString()),
                        ),
                    )
                }
                // The seven recommended playlists: what the engine made from external sources, when the listener agreed
                // to it and there is something; the selection from the library (below) otherwise.
                val generated = if (recoConsent && com.ikko.aura.reco.RecoKind.ofKey(spec.id) != null) recoItems[spec.id].orEmpty() else emptyList()
                if (generated.isNotEmpty()) {
                    val tracks = generated.map { with(com.ikko.aura.reco.RecoItemTracks) { it.toTrack() } }
                    val playlistId = playlistRepository.autoPlaylistId(spec.id)
                    val updatedAt = recoStatus[spec.id]?.generatedAtMs ?: 0L
                    val kicker = if (updatedAt > 0) "Mis à jour le " + java.time.Instant.ofEpochMilli(updatedAt)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.FRENCH))
                    else spec.kicker
                    return@mapNotNull ForYouCard(
                        spec.id, spec.ambience, spec.heading, kicker, artistsOf(tracks),
                        WidgetContent(
                            title = spec.heading, subtitle = null, artwork = emptyList(), tracks = tracks.take(MIX_SIZE), hue = 0f,
                            openRoute = playlistId?.let { AuraDestination.PlaylistPattern.route(it.toString()) },
                        ),
                    )
                }
                var content = if (spec.pick != null) {
                    val tracks = spec.pick.invoke(data).take(MIX_SIZE)
                    WidgetContent(title = spec.heading, subtitle = null, artwork = emptyList(), tracks = tracks, hue = 0f)
                } else {
                    HomeWidgetResolver.resolve(HomeWidget(spec.id, spec.kind!!), data)
                }
                if (spec.weekly && !content.isEmpty) {
                    // A real playlist, refilled once a week: the card opens it.
                    val id = playlistRepository.autoPlaylist(spec.id, spec.heading, System.currentTimeMillis()) { content.tracks }
                    content = content.copy(openRoute = AuraDestination.PlaylistPattern.route(id.toString()))
                }
                if (content.isEmpty) null else ForYouCard(spec.id, spec.ambience, spec.heading, spec.kicker, artistsOf(content.tracks), content)
        }
        val recentAlbums = recentAlbumIds.mapNotNull { albumsById[it] }.oneOfEach().take(MAX_RECENT)
        val resumeAlbums = (recentAlbums + recentTrackIds.mapNotNull { byId[it]?.albumId?.let(albumsById::get) })
            .oneOfEach()
            .ifEmpty { allAlbums }
            .take(MAX_RECENT)

        val favTrackAlbumIds = playlists.firstOrNull { it.isFavorites }?.trackIds.orEmpty().mapNotNull { byId[it]?.albumId }
        val topPlayedAlbumIds = plays.sortedByDescending { it.playCount }.map { it.albumId }
        val favoriteAlbums = (
            allAlbums.filter { it.id in favoriteIds } +
                favTrackAlbumIds.mapNotNull { albumsById[it] } +
                topPlayedAlbumIds.mapNotNull { albumsById[it] }
            )
            .distinctBy { it.id }
            .ifEmpty { allAlbums }
            .take(MAX_RECENT)

        val newAlbums = (
            allTracks.filter { it.year > 0 }
                .sortedWith(compareByDescending<Track> { it.year }.thenBy { HomeWidgetRules.dailyRank(it.id, data.day) })
                .mapNotNull { albumsById[it.albumId] } +
                allTracks.sortedBy { HomeWidgetRules.dailyRank(it.id, data.day) }.mapNotNull { albumsById[it.albumId] }
            )
            .distinctBy { it.id }
            .ifEmpty { allAlbums }
            .take(MAX_RECENT)

        val discoveryAlbums = (
            HomeWidgetResolver.resolve(HomeWidget("disc", WidgetKind.REDISCOVER), data).tracks
                .mapNotNull { albumsById[it.albumId] } +
                allAlbums.filter { it.id !in data.recentAlbumIds }
                    .sortedBy { HomeWidgetRules.dailyRank(it.id.toString(), data.day) }
            )
            .distinctBy { it.id }
            .ifEmpty { allAlbums }
            .take(MAX_RECENT)

        val autoIds = playlistRepository.autoPlaylistIds()
        val autoHeadings = CARDS.map { it.heading }.toSet() + setOf("Découverte", "Favoris")
        val allTracksAndKeptById = (allTracks + kept).associateBy { it.id }
        val userPlaylists = playlists
            .filter { !it.isFavorites && it.id !in autoIds && it.name !in autoHeadings }
            .map { p ->
                UserPlaylistCard(
                    id = p.id,
                    name = p.name,
                    trackCount = p.trackCount,
                    artworkUris = p.trackIds.mapNotNull { allTracksAndKeptById[it]?.artworkUri }.distinct().take(4),
                )
            }

        val favTracks = playlists.firstOrNull { it.isFavorites }?.trackIds.orEmpty().mapNotNull { byId[it] }
        val topTracks = HomeWidgetResolver.resolve(HomeWidget("top", WidgetKind.TOP_PLAYED), data).tracks
        val quickTracks = (data.recentTracks + favTracks + topTracks + allTracks)
            .distinctBy { it.id }
            .take(MAX_RECENT)

        var rotationOut: com.ikko.aura.data.home.RotationContext? = null
        ForYouUiState(
            loaded = true,
            cards = built.filter { it.id !in hidden },
            recent = recentAlbums,
            resumeAlbums = resumeAlbums,
            lastPlayedAt = plays.associate { it.albumId to it.lastPlayedAtMs },
            favoriteAlbums = favoriteAlbums,
            newAlbums = newAlbums,
            discoveryAlbums = discoveryAlbums,
            quickTracks = quickTracks,
            userPlaylists = userPlaylists,
            layout = built.map { CardSlot(it.id, it.heading, it.id in hidden) },
            sectionsSeed = seed,
            sections = run {
                // The listening, the device's and the sources' alike: "En boucle" and "Replay" count both.
                val knownStreams = runCatching { ytmLibrary.known() }.getOrDefault(emptyList()).associateBy { it.id }
                val listenRows = runCatching { recommendations.listensSince(System.currentTimeMillis() - 365L * MILLIS_PER_DAY) }
                    .getOrDefault(emptyList())
                    .map { com.ikko.aura.data.home.ListenRow(it.trackId, it.title, it.artist, it.album, it.year, it.outcome, it.endedAtMs) }
                // What the sources offer is made only with the listener's consent (see the recommendations' settings); the
                // engine has already ranked the candidates for their taste, so a row may carry a discovery, not only the
                // artists the listener already knows.
                // A style the listener does not want, whatever the artist: none of its albums, wherever they come from.
                fun unwanted(artist: String) = com.ikko.aura.data.home.HomeRotation.isUnwantedStyle(sourceTags[com.ikko.aura.reco.ArtistNames.primaryKey(artist)].orEmpty())
                // The engine has already ranked these for the listener's taste and dropped what has nothing to do with it
                // (see the taste gate and the discography check); a stranger with a reason is a discovery, which is what
                // Home is for. Only a style the listener does not want is kept out here.
                fun ofCloseArtist(artist: String) = !unwanted(artist)
                // For an artist whose real discography is known, only what belongs to it: a search by name answers with the namesake's records too.
                val discographyOf: Map<String, Set<String>> = chosenPool
                    .groupBy { com.ikko.aura.reco.ArtistNames.primaryKey(it.artist) }
                    .mapValues { (_, albums) -> albums.mapTo(HashSet()) { com.ikko.aura.reco.TrackIdentity.baseKey(it.title) } }
                fun ofTheirDiscography(artist: String, album: String, title: String): Boolean {
                    val titles = discographyOf[com.ikko.aura.reco.ArtistNames.primaryKey(artist)] ?: return true
                    return com.ikko.aura.reco.TrackIdentity.baseKey(album) in titles || com.ikko.aura.reco.TrackIdentity.baseKey(title) in titles
                }
                val offered = if (recoConsent) {
                    recoItems.mapValues { (_, list) ->
                        list.filter {
                            !com.ikko.aura.data.home.HomeSections.isExcludedAlbum(it.title) &&
                                !com.ikko.aura.data.home.HomeSections.isExcludedAlbum(it.album) &&
                                ofCloseArtist(it.artist) && ofTheirDiscography(it.artist, it.album, it.title)
                        }
                    }
                } else emptyMap()
                val base = com.ikko.aura.data.home.HomeSections.build(
                    items = offered, library = allAlbums, recentAlbumIds = recentAlbumIds, topPlayedAlbumIds = topPlayedAlbumIds,
                    maxSections = 40,
                )
                fun place(r: com.ikko.aura.data.home.ListenRow): com.ikko.aura.data.home.SectionAlbum? {
                    val track = byId[r.trackId]
                    if (track != null) return albumsById[track.albumId]?.let { com.ikko.aura.data.home.SectionAlbum(it.title, it.artist, album = it) }
                    val stream = knownStreams[r.trackId] ?: return null
                    val ref = stream.remoteAlbumId
                    fun cleanAlbum(s: String) = if (s.equals("YouTube Music", ignoreCase = true) || s.equals("JioSaavn", ignoreCase = true)) "" else s.trim()
                    val realAlbumName = cleanAlbum(stream.album).ifBlank { cleanAlbum(r.album) }
                    val displayAlbum = realAlbumName.ifBlank { stream.title.ifBlank { r.title } }.ifBlank { return null }
                    val item = com.ikko.aura.reco.RecoItem(
                        trackId = stream.id,
                        title = stream.title,
                        artist = stream.artist,
                        album = realAlbumName,
                        durationMs = stream.durationMs,
                        artworkUrl = stream.artworkUri?.toString().orEmpty(),
                        albumRef = ref.orEmpty(),
                        sourceId = stream.id.substringBefore(':'),
                        sourceName = stream.id.substringBefore(':'),
                        reason = null,
                        verified = true,
                    )
                    return com.ikko.aura.data.home.SectionAlbum(
                        title = displayAlbum, artist = stream.artist, ref = ref,
                        artworkUrl = stream.artworkUri?.toString().orEmpty(),
                        items = listOf(item),
                    )
                }
                val now = System.currentTimeMillis()
                val zone = java.time.ZoneId.systemDefault()
                // Which albums of the library are slow (Automix's tempo).
                val slowAlbums = runCatching { slowAlbumsOf(allTracks, albumsById) }.getOrDefault(emptyList())
                val loop = com.ikko.aura.data.home.ListeningAlbums.looped(listenRows, now, zone, ::place).map { it.album }
                val everything = com.ikko.aura.data.home.ListeningAlbums.group(listenRows, zone, ::place)
                val owned = allAlbums.mapTo(HashSet()) { it.title.trim().lowercase() + "\u0000" + it.artist.trim().lowercase() }
                // The styles of the artists the listener cares about, with how much: the rows of genres are drawn from them.
                val tasteTags = HashMap<String, Double>()
                for (meta in runCatching { smartDao.allArtists() }.getOrDefault(emptyList())) {
                    if (meta.affinityScore <= 0.0 || meta.isBlocked) continue
                    val arr = runCatching { org.json.JSONArray(meta.genresJson) }.getOrNull() ?: continue
                    for (i in 0 until arr.length()) tasteTags.merge(arr.getString(i).lowercase(), meta.affinityScore, Double::plus)
                }
                val rotation = com.ikko.aura.data.home.RotationContext(
                        base = base, loop = loop, listens = everything,
                        // What the engine found, and the albums of the artists chosen (their discographies): the pool the rows are drawn from.
                        external = (
                            com.ikko.aura.data.home.HomeSections.externalAlbums(offered) +
                                chosenPool.filter { a ->
                                    !unwanted(a.artist) && !com.ikko.aura.data.home.HomeSections.isExcludedAlbum(a.title) &&
                                        com.ikko.aura.reco.ArtistNames.split(a.artist).none { it in blockedNow.mapTo(HashSet()) { b -> b.key } } &&
                                        com.ikko.aura.reco.AlbumKeys.ofName(a.title, a.artist) !in refusedAlbums
                                }.map { a -> com.ikko.aura.data.home.SectionAlbum(a.title, a.artist, artworkUrl = a.coverXl) }
                            ).distinctBy { it.sameAs }.filter { it.sameAs !in owned },
                        artistTags = sourceTags, recoItems = offered, nowMs = now, seed = seed, zone = zone,
                        slowAlbums = slowAlbums,
                        tasteTags = tasteTags,
                        // What the genres' charts offer, without any album already in the library, of a blocked artist, or refused as a whole.
                        chartAlbums = charts.mapValues { (_, list) ->
                            val blockedKeys = blockedNow.mapTo(HashSet()) { it.key }
                            list.filter { a ->
                                !com.ikko.aura.data.home.HomeSections.isExcludedAlbum(a.title) &&
                                    ofCloseArtist(a.artist) &&
                                    com.ikko.aura.reco.ArtistNames.split(a.artist).none { it in blockedKeys } &&
                                    com.ikko.aura.reco.AlbumKeys.ofName(a.title, a.artist) !in refusedAlbums &&
                                    (a.title.trim().lowercase() + "\u0000" + a.artist.trim().lowercase()) !in owned
                            }.map { a -> com.ikko.aura.data.home.SectionAlbum(a.title, a.artist, artworkUrl = a.coverXl) }
                        },
                        // The library's artists and the ones chosen: the rows "Plus de…" are made for them.
                        knownArtists = (allAlbums.map { it.artist } + chosenArtists).flatMap { name -> com.ikko.aura.reco.ArtistNames.split(name) + com.ikko.aura.reco.ArtistNames.primaryKey(name) }.filter { it.isNotEmpty() }.toSet(),
                )
                rotationOut = rotation
                com.ikko.aura.data.home.HomeRotation.arrange(rotation)
            },
            rotation = rotationOut,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForYouUiState())

    /** The carousels as the listener first saw them: what is played meanwhile does not move them (see [refreshCarousels]). */
    private val frozenCarousels = MutableStateFlow<ForYouUiState?>(null)

    /** Takes the arrangement of Home as it is once settled, and holds it. */
    private fun freezeSectionsWhenReady() {
        freezeJob?.cancel()
        freezeJob = viewModelScope.launch {
            val seed = rotationSeed.value
            sectionsSettled.first { it }
            val live = uiState.first { it.loaded && it.sectionsSeed == seed && it.sections.isNotEmpty() }
            sectionsHeld.value = enrichSectionCovers(live.sections)
        }
    }

    /**
     * External tiles show the album's own cover once it is known, not the thumbnail of the song they were made from
     * (a video frame, a single's art): what the tile promises is the album, so it wears the album's cover. Resolved in
     * the background, a few at a time, and tolerant of failure — without it the tile keeps the song's artwork.
     */
    private suspend fun enrichSectionCovers(sections: List<com.ikko.aura.data.home.HomeSection>): List<com.ikko.aura.data.home.HomeSection> =
        withContext(Dispatchers.IO) {
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            sections.map { section ->
                section.copy(albums = section.albums.map { item ->
                    val ref = item.ref
                    // Only an external album with a YouTube Music reference and no cover yet: local albums already
                    // carry their own, an artist tile shows a photo, and a remote source's own art is already the album's.
                    if (item.album != null || item.isArtist || item.playsItems || ref.isNullOrBlank() || ref.contains(':') || item.albumCoverUrl != null) {
                        item
                    } else {
                        val cover = gate.withPermit { runCatching { ytmClient.album(ref).ref.thumbnailUrl }.getOrNull() }
                        if (cover.isNullOrBlank()) item else item.copy(albumCoverUrl = cover)
                    }
                })
            }
        }

    init { freezeSectionsWhenReady() }

    init {
        viewModelScope.launch {
            uiState.collect { live ->
                if (!live.loaded) return@collect
                val current = frozenCarousels.value
                if (current == null || live.sections.size > current.sections.size) {
                    frozenCarousels.value = live
                }
            }
        }
    }

    /**
     * [uiState] with the Home carousels (the four tabs and their "il y a 2 h") held as they were when last
     * [refreshCarousels]'d: a song played while Home is on screen, or an album opened and left, does not reorder them
     * under the finger or under a returning transition.
     */
    val homeState: StateFlow<ForYouUiState> = combine(
        uiState, frozenCarousels, sectionsHeld, recommendations.blockedArtists, recommendations.refusedAlbumKeys,
    ) { live, held, rows, blocked, refused ->
        // The rows are the ones held, never the live ones: nothing moves in them because of what is played or found meanwhile.
        // Not drawn before they have settled, rather than drawn and then drawn again.
        val sections = rows?.let { com.ikko.aura.data.home.SectionHold.reconcile(
            it, live.sections, blocked.mapTo(HashSet()) { b -> b.key }, refused,
            poolOf = { id -> live.rotation?.let { ctx -> com.ikko.aura.data.home.HomeRotation.pool(id, ctx) }.orEmpty() },
        ) }
            ?: emptyList()
        // Nothing held until there is something to hold: a library still being read must not freeze empty carousels.
        if (held == null || (held.resumeAlbums.isEmpty() && held.favoriteAlbums.isEmpty() && held.newAlbums.isEmpty() && held.discoveryAlbums.isEmpty())) live.copy(sections = sections)
        else live.copy(
            resumeAlbums = held.resumeAlbums,
            lastPlayedAt = held.lastPlayedAt,
            favoriteAlbums = held.favoriteAlbums,
            newAlbums = held.newAlbums,
            discoveryAlbums = held.discoveryAlbums,
            sections = sections,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForYouUiState())

    /**
     * Takes the carousels up to date: called when Home is out of sight (another page, the app in the background). "En boucle" is brought up to date
     * with them: it says what is looped now, which a row held for the whole session cannot.
     */
    fun refreshCarousels() {
        val live = uiState.value
        if (live.loaded) frozenCarousels.value = live
        val loop = live.sections.lastOrNull { it.id == "loop" }
        sectionsHeld.value = sectionsHeld.value?.let { com.ikko.aura.data.home.SectionHold.withLoop(it, loop) }
    }

    /** The albums of the library whose songs, as far as Automix has measured them, are slow: a median under [com.ikko.aura.data.home.HomeRotation.SLOW_BPM] BPM over at least three songs. */
    private suspend fun slowAlbumsOf(tracks: List<Track>, albumsById: Map<Long, Album>): List<com.ikko.aura.data.home.SectionAlbum> {
        val sound = acoustics.of(tracks.filter { !YtmLibrary.isStreamed(it) }.map { it.id })
        if (sound.isEmpty()) return emptyList()
        return tracks.filter { sound[it.id]?.bpm?.let { b -> b > 0.0 } == true }.groupBy { it.albumId }.mapNotNull { (albumId, list) ->
            val bpms = list.mapNotNull { sound[it.id]?.bpm }.sorted()
            val median = bpms[bpms.size / 2]
            val album = albumsById[albumId]
            if (album != null && bpms.size >= SLOW_MIN_SONGS && median < com.ikko.aura.data.home.HomeRotation.SLOW_BPM) {
                com.ikko.aura.data.home.SectionAlbum(album.title, album.artist, album = album)
            } else null
        }
    }


    /** One card. [kind] is a selection the resolver knows; [pick] a selection made here, from the library's songs. */
    private class Spec(
        val id: String,
        val kind: WidgetKind?,
        val ambience: CardAmbience,
        val heading: String,
        val kicker: String,
        val weekly: Boolean = false,
        val pick: ((HomeSourceData) -> List<Track>)? = null,
    )

    /**
     * One entry per album: a streamed album that was then downloaded is on the device as well, as the file's own album, and
     * both turn up in what was played - the stream's under its own id, the file's under the media store's. They are the same
     * record (the same title by the same artist): the first place either holds is kept, as the downloaded one when there is
     * one (it plays without a network and opens the album as the library has it).
     */
    private fun List<Album>.oneOfEach(): List<Album> {
        fun key(a: Album) = a.title.trim().lowercase() + "\u0000" + a.artist.trim().lowercase()
        val bestOf = groupBy(::key).mapValues { (_, same) -> same.firstOrNull { it.remoteId == null } ?: same.first() }
        return distinctBy(::key).map { bestOf.getValue(key(it)) }
    }

    private fun artistsOf(tracks: List<Track>): String {
        val names = tracks.map { it.artist.replace(spacesRegex, " ").trim() }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return ""
        return names.take(3).joinToString(", ") + if (names.size > 3) " et plus" else ""
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val MAX_RECENT = 12
        const val MIX_SIZE = 30
        const val FOUR_MIN_MS = 4L * 60_000L
        const val THREE_MIN_MS = 3L * 60_000L
        // The six moods of the design, in its order, then the three the app already had.
        val CARDS = listOf(
            Spec("fav", WidgetKind.FAVORITE_ALBUMS, CardAmbience.FAVORIS, "Tes préférés", "Ce que tu aimes"),
            // What the listener has never played: albums with no play at all, in a new order each day.
            Spec("disc", null, CardAmbience.DECOUVERTE, "Découvertes", "Une sélection pour toi", weekly = true) { d ->
                d.allTracks.filter { (d.albumPlays[it.albumId] ?: 0) == 0 && it.albumId !in d.recentAlbumIds }
                    .sortedBy { HomeWidgetRules.dailyRank(it.id, d.day) }
            },
            Spec("new", null, CardAmbience.NOUVEAUTES, "Nouveautés", "Fait pour toi", weekly = true) { d ->
                d.allTracks.filter { it.year > 0 }.sortedWith(compareByDescending<Track> { it.year }.thenBy { HomeWidgetRules.dailyRank(it.id, d.day) })
            },
            // Chosen from what the files say (their length), not from guesses about how they sound.
            Spec("energy", null, CardAmbience.ENERGIE, "Ça bouge !", "Ambiance du moment", weekly = true) { d ->
                d.allTracks.filter { it.durationMs in 1L until THREE_MIN_MS }.sortedBy { HomeWidgetRules.dailyRank(it.id, d.day) }
            },
            Spec("chill", null, CardAmbience.CHILL, "Chill", "Ambiance du moment", weekly = true) { d ->
                d.allTracks.filter { it.durationMs in THREE_MIN_MS until FOUR_MIN_MS }.sortedBy { HomeWidgetRules.dailyRank(it.id, d.day) }
            },
            Spec("night", null, CardAmbience.NUIT, "Nuit", "Ambiance du moment", weekly = true) { d ->
                d.allTracks.filter { it.durationMs >= FOUR_MIN_MS }.sortedBy { HomeWidgetRules.dailyRank(it.id, d.day) }
            },
            Spec("top", WidgetKind.TOP_PLAYED, CardAmbience.ENERGIE, "Les plus écoutés", "Ce que tu écoutes le plus", weekly = true),
            Spec("daily", WidgetKind.DAILY_MIX, CardAmbience.NUIT, "Mix du jour", "Ambiance du moment"),
            Spec("style", WidgetKind.FOR_YOU, CardAmbience.FAVORIS, "Dans ton style", "D'après tes écoutes", weekly = true),
        )
    }
}

/** Deezer's ids of the genres whose chart fills a row (see HomeRotation): pop, rock, rap, electro, dance, jazz, funk, metal, reggae, folk, classical, blues, African music. */
private val CHART_GENRES = listOf(132, 152, 116, 106, 113, 169, 464, 144, 466, 98, 153, 2)

/** How many of the chosen artists have their albums looked up. */
private const val CHOSEN_ARTISTS_ASKED = 48

/** The artists the listener plays beyond the chosen ones, how many of the ones they like most are asked for their neighbours, how many each gives, how many neighbours in all, how many albums of each. */
private const val PLAYED_ARTISTS_ASKED = 40
private const val RELATED_FROM = 14
private const val RELATED_PER_ARTIST = 12
private const val RELATED_ARTISTS_ASKED = 60
private const val RELATED_ALBUMS = 12

/** Artists whose albums are loaded together before Home is drawn again. */
private const val STOCK_BATCH = 8

/** The least number of measured songs for an album to count as slow. */
private const val SLOW_MIN_SONGS = 3

/** The artists whose tags are asked for in one go. */
private const val TAGS_PER_ROUND = 160


/** The most that Home waits for its rows to settle before showing them. */
private const val SETTLE_MS = 12_000L
