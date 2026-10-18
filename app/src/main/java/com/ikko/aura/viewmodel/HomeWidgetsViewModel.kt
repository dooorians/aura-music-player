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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    ytmLibrary: YtmLibrary,
    private val playlistRepository: PlaylistRepository,
    private val settingsStore: SettingsStore,
    private val recommendations: com.ikko.aura.reco.RecommendationEngine,
) : ViewModel() {

    init {
        // Renewals that are due run when the app is opened; and at once when the listener turns the switch on.
        viewModelScope.launch {
            recommendations.consent.collect { on -> if (on) recommendations.launchRefreshIfDue() }
        }
    }

    /** Whether a renewal is running, and a manual renewal of everything. */
    val recoRefreshing: StateFlow<Boolean> = recommendations.refreshing
    fun refreshRecommendations() { viewModelScope.launch { recommendations.refreshNow() } }

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
        val recentAlbums = recentAlbumIds.mapNotNull { albumsById[it] }.take(MAX_RECENT)
        val resumeAlbums = (recentAlbums + recentTrackIds.mapNotNull { byId[it]?.albumId?.let(albumsById::get) })
            .distinctBy { it.id }
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
        val autoHeadings = CARDS.map { it.heading }.toSet() + setOf("Découvertes", "Tes préférés")
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
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForYouUiState())

    /** The carousels as the listener first saw them: what is played meanwhile does not move them (see [refreshCarousels]). */
    private val frozenCarousels = MutableStateFlow<ForYouUiState?>(null)

    init {
        viewModelScope.launch { frozenCarousels.compareAndSet(null, uiState.first { it.loaded }) }
    }

    /**
     * [uiState] with the Home carousels (the four tabs and their "il y a 2 h") held as they were when last
     * [refreshCarousels]'d: a song played while Home is on screen, or an album opened and left, does not reorder them
     * under the finger or under a returning transition.
     */
    val homeState: StateFlow<ForYouUiState> = combine(uiState, frozenCarousels) { live, held ->
        // Nothing held until there is something to hold: a library still being read must not freeze empty carousels.
        if (held == null || (held.resumeAlbums.isEmpty() && held.favoriteAlbums.isEmpty() && held.newAlbums.isEmpty() && held.discoveryAlbums.isEmpty())) live
        else live.copy(
            resumeAlbums = held.resumeAlbums,
            lastPlayedAt = held.lastPlayedAt,
            favoriteAlbums = held.favoriteAlbums,
            newAlbums = held.newAlbums,
            discoveryAlbums = held.discoveryAlbums,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForYouUiState())

    /** Takes the carousels up to date: called when Home is out of sight (another page, the app in the background). */
    fun refreshCarousels() {
        val live = uiState.value
        if (live.loaded) frozenCarousels.value = live
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

    private fun artistsOf(tracks: List<Track>): String {
        val names = tracks.map { it.artist.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }.distinct()
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
            Spec("fav", WidgetKind.FAVORITE_ALBUMS, CardAmbience.FAVORIS, "Favoris", "Fait pour toi"),
            Spec("disc", WidgetKind.REDISCOVER, CardAmbience.DECOUVERTE, "Découverte", "À redécouvrir", weekly = true),
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
