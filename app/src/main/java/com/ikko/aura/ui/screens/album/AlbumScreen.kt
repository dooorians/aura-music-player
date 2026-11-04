package com.ikko.aura.ui.screens.album

import com.ikko.aura.ui.components.heroPageOut
import com.ikko.aura.ui.components.heroRise
import com.ikko.aura.ui.components.coverArrival
import com.ikko.aura.ui.components.heroTarget
import com.ikko.aura.ui.components.suppressEdgeBackRipple
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ikko.aura.ui.components.rememberPlayerAccent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import com.ikko.aura.source.ytm.DownloadEntry
import com.ikko.aura.source.ytm.DownloadState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Immutable
import com.ikko.aura.ui.components.AuraMenu
import com.ikko.aura.viewmodel.AlbumPage
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.AudioFormat
import com.ikko.aura.data.model.Track
import com.ikko.aura.navigation.streamedArtistRoute
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.AddToPlaylistDialog
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.FillableIcon
import com.ikko.aura.ui.components.AuraButton
import com.ikko.aura.ui.components.AuraIconButton
import com.ikko.aura.ui.components.ButtonTone
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.components.glassMenuAnchor
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LocalPageTheme
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.AlbumUiState
import com.ikko.aura.viewmodel.FileTags
import com.ikko.aura.viewmodel.AlbumViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

internal val PAGE_PADDING = 16.dp
internal val ART_SIZE = 89.dp
internal val ART_SHAPE = AuraShapes.AlbumCoverDetail
internal val ACTION_HEIGHT = 42.dp

/** The white capsule is drawn right to its edge while the dark buttons have a rim inside theirs: 2 dp less for the same height. */
private val PLAY_HEIGHT = 40.dp

/** The play capsule's width: about three round buttons' worth. */
private val PLAY_WIDTH = 144.dp
private val ROW_RADIUS = 20.dp

/** How far the playing row's highlight reaches past the page's margin on each side. */
private val HIGHLIGHT_OUTSET = 8.dp

@Composable
fun AlbumScreen(
    albumId: String,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    albumViewModel: AlbumViewModel = hiltViewModel(),
    isTransitioning: Boolean = false,
) {
    val uiState by albumViewModel.uiState.collectAsStateWithLifecycle()
    val page by albumViewModel.page.collectAsStateWithLifecycle()
    AlbumPageContent(
        uiState = uiState,
        page = page,
        navController = navController,
        playerViewModel = playerViewModel,
        isTransitioning = isTransitioning,
        onFavorite = albumViewModel::toggleFavorite,
    )
}

/** What a streamed album adds to the page: a way to keep it in the library, and to make its songs known first. */
@Immutable
class StreamedAlbumControls(
    val kept: Boolean,
    val onToggleKeep: () -> Unit,
    /** Each song's download, by id. */
    val downloads: Map<String, DownloadEntry>,
    val onDownloadAlbum: () -> Unit,
    val onRemoveAlbumDownloads: () -> Unit,
    val onDownloadTrack: (Track) -> Unit,
    /** Run before the songs are handed to something that only knows their ids (the add-to-playlist dialog). */
    val prepare: suspend () -> Unit,
    /** To the page of the whole album, when this one lists only what is on the device. */
    val onShowFullAlbum: (() -> Unit)? = null,
    /** To the page of what is on the device, when this one lists the whole album. */
    val onShowLocal: (() -> Unit)? = null,
    /** Whether anything of the album is on the device: the "Local" side of the source switch has nothing to show otherwise. */
    val localAvailable: Boolean = true,
)

/**
 * An album's page before the source has answered: the page itself (header, the very buttons, the rows) with what the card it came from
 * knows (the cover, the title, the artist) and grey where the rest will be, so that it is there at touch and the trip of the cover and of
 * the words has its places from the first frame. Opened from somewhere that said nothing, everything is grey.
 */
@Composable
fun AlbumLoadingPage(navController: NavHostController, playerViewModel: PlayerViewModel) {
    val hero = com.ikko.aura.ui.components.LocalHero.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // What the card knows, read once as the page opens: the trip's own record of it.
    val cover = remember { hero.uri }
    val title = remember { hero.srcTitle?.text.orEmpty() }
    val artist = remember { hero.srcSub?.text.orEmpty() }
    // Its colours come from the cover as the real page's do, so that nothing changes colour when the songs arrive.
    val theme by androidx.compose.runtime.produceState<com.ikko.aura.ui.components.AlbumTheme?>(
        if (cover == null) com.ikko.aura.ui.components.AlbumTheme.Neutral else null, cover,
    ) {
        if (cover != null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.ikko.aura.ui.components.AlbumArtCache.load(context, cover, 192, cover.toString())
                ?.let { com.ikko.aura.ui.components.albumThemeFromBitmap(it, cover.toString().hashCode()) }
                ?: com.ikko.aura.ui.components.AlbumTheme.Neutral
        }
    }
    val page = theme?.let { AlbumPage(it, FileTags()) }
    AlbumPageContent(
        uiState = AlbumUiState(title = title, artist = artist, coverUri = cover, loading = true),
        page = page,
        navController = navController,
        playerViewModel = playerViewModel,
        isTransitioning = false,
        onFavorite = {},
    )
}

/**
 * The album page itself, for any album: a local one from [AlbumScreen], a YouTube Music one from its own screen. One
 * layout, so the two cannot drift apart.
 */
@Composable
fun AlbumPageContent(
    uiState: AlbumUiState,
    page: AlbumPage?,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    isTransitioning: Boolean,
    onFavorite: () -> Unit,
    streamed: StreamedAlbumControls? = null,
) {
    var showAddToPlaylist by remember { mutableStateOf(false) }
    val moreMenuState = com.ikko.aura.ui.components.rememberGlassMenuState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // The page of the whole album, from a page that lists only what is on the device: the library's own, or a source's.
    val links: com.ikko.aura.viewmodel.AlbumLinksViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    // An album of the library switches source in place: the page stays, its songs become the whole album's (fetched here), and back.
    var showOnline by remember { mutableStateOf(false) }
    var onlineTracks by remember { mutableStateOf<List<Track>?>(null) }
    val localInput = uiState
    val uiState = if (streamed == null && showOnline) onlineTracks?.let { localInput.copy(tracks = it) } ?: localInput else localInput
    val knownRef = localInput.tracks.firstNotNullOfOrNull { it.remoteAlbumId }
    val fullAlbum: (() -> Unit)? = when {
        streamed != null -> streamed.onShowFullAlbum
        localInput.title.isNotBlank() -> { {
            showOnline = true
            if (onlineTracks == null) scope.launch {
                // The page of the album on the source: known from its songs, or found by its title and its artist.
                val ref = knownRef ?: links.onlineRefOf(localInput.title, localInput.artist)
                val found = ref?.let { runCatching { playerViewModel.streamedAlbumTracks(it) }.getOrDefault(emptyList()) }.orEmpty()
                if (found.isNotEmpty()) onlineTracks = found
                else {
                    showOnline = false
                    android.widget.Toast.makeText(context, "Album introuvable en ligne", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } }
        else -> null
    }

    // Narrowed to the two things a row cares about. Collecting the whole player state here would
    // recompose the page twice a second for the progress bar's sake. Seeded from the current state:
    // starting from "nothing playing" made the playing row light up a beat after the page appeared.
    val nowPlaying by remember(playerViewModel) {
        playerViewModel.uiState.map { it.currentTrack?.id to it.isPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = playerViewModel.uiState.value.let { it.currentTrack?.id to it.isPlaying },
    )

    // Nothing of the page is drawn until its colours and tags are ready — a few frames, inside the
    // transition that brought us here — so it appears whole instead of assembling itself: buttons
    // changing colour, a caption arriving and pushing the list down, were what read as flicker.
    val current = page
    if (current == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val theme = current.theme
    val fileTags = current.tags

    // The background behind this page is lit with this album, not with what is playing.
    val pageTheme = LocalPageTheme.current
    val localPageThemeState = remember(theme) { mutableStateOf<com.ikko.aura.ui.components.AlbumTheme?>(theme) }
    DisposableEffect(theme) {
        pageTheme.value = theme
        onDispose { if (pageTheme.value === theme) pageTheme.value = null }
    }

    val accent = rememberUpdatedState(theme.accent)
    val accentSoft = rememberUpdatedState(theme.accentSoft)

    val density = LocalDensity.current
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val hero = com.ikko.aura.ui.components.LocalHero.current

    // Not while the player is open over this page: its own back closes the player, and this one would leave the page too
    // (and play the page's return) from under it.
    val sheetCard = com.ikko.aura.LocalSheetCard.current
    androidx.activity.compose.BackHandler(enabled = sheetCard.owner == null || sheetCard.leavingSideways) {
        // The whole album, opened from the page of what is downloaded of it, has no trip of its own to close: the one
        // that is open belongs to the page under it (closing it here would hide that page for good).
        val under = navController.previousBackStackEntry?.destination?.route.orEmpty()
        val overAlbum = under.startsWith("album/") || under.startsWith("ytm_album/") || under.startsWith("remote_album/")
        if (hero.canClose && !overAlbum) hero.startClosing()
        navController.popBackStack()
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalPageTheme provides localPageThemeState) {
    MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
        GradientScreenScaffold(
            applyStatusBarInset = false,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .suppressEdgeBackRipple(),
            ) {
                val groundArrival = com.ikko.aura.ui.components.rememberHeroGround()
                AlbumGround(ground = theme.solid, listState = listState, arrival = groundArrival)
                run {

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .heroPageOut(),
                    contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    item(key = "header") {
                        AlbumHeader(
                            onArtist = {
                                uiState.tracks.firstOrNull()?.let { t ->
                                    navController.navigate(
                                        if (com.ikko.aura.source.ytm.YtmLibrary.isStreamed(t)) t.streamedArtistRoute()
                                        else AuraDestination.ArtistPattern.route(t.artistId.toString()),
                                    )
                                }
                            },
                            uiState = uiState,
                            fileTags = fileTags,
                            accentSoft = accentSoft,
                            onPlayAll = {
                                if (uiState.tracks.isNotEmpty()) {
                                    playerViewModel.playQueue(uiState.tracks, 0)
                                }
                            },
                            onShuffle = {
                                if (uiState.tracks.isNotEmpty()) {
                                    playerViewModel.playShuffled(uiState.tracks)
                                }
                            },
                            onFavorite = onFavorite,
                            // The source switch: this page lists what is on the device (local) or the whole album (online).
                            online = if (uiState.loading) true else if (streamed != null) streamed.onShowFullAlbum == null else showOnline,
                            onlineAvailable = fullAlbum != null || (streamed != null && streamed.onShowFullAlbum == null),
                            localAvailable = streamed?.localAvailable ?: true,
                            onSource = { toOnline ->
                                if (uiState.loading) Unit
                                else if (toOnline) fullAlbum?.invoke()
                                else if (streamed == null) showOnline = false
                                else streamed.onShowLocal?.invoke()
                            },
                            hasDownload = streamed != null,
                            downloadSlot = {
                                if (streamed != null) {
                                    AlbumDownloadChip(uiState.tracks, streamed)
                                }
                            },
                            onMore = { if (!uiState.loading) moreMenuState.open() },
                            moreAnchor = Modifier.glassMenuAnchor(moreMenuState),
                            moreMenu = {
                                AlbumMoreMenu(
                                    state = moreMenuState,
                                    isFavorite = uiState.isFavorite,
                                    onFavorite = onFavorite,
                                    onAddToPlaylist = {
                                        if (streamed != null) scope.launch { streamed.prepare(); showAddToPlaylist = true }
                                        else showAddToPlaylist = true
                                    },
                                    onPlayNext = { playerViewModel.playNext(uiState.tracks) },
                                    streamed = streamed,
                                    downloadedAll = streamed != null && uiState.tracks.isNotEmpty() &&
                                        uiState.tracks.all { streamed.downloads[it.id]?.state == DownloadState.DONE },
                                    onDeleteFromLibrary = { menu.delete(uiState.tracks) },
                                    // The library page lists what is downloaded; the source's page lists the whole album.
                                    onShowFullAlbum = fullAlbum,
                                )
                            },
                        )
                    }
                    if (uiState.loading) items(SKELETON_ROWS) { index ->
                        // The rows' own measures (a row is 140 design units tall, the number and the time at its two ends).
                        val u = com.ikko.aura.ui.components.designUnit()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = u * 140f).padding(horizontal = u * 40f).heroRise(index, rows = true),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.ikko.aura.ui.components.SkeletonBar(Modifier.width(u * 24f).height(u * 28f))
                            Spacer(Modifier.width(u * 56f))
                            com.ikko.aura.ui.components.SkeletonBar(Modifier.weight(1f).fillMaxWidth(0.6f).height(u * 34f))
                            Spacer(Modifier.width(u * 120f))
                            com.ikko.aura.ui.components.SkeletonBar(Modifier.width(u * 90f).height(u * 26f))
                        }
                    }
                    itemsIndexed(uiState.tracks, key = { _, track -> track.id }) { index, track ->
                        Box(Modifier.heroRise(index, rows = true)) {
                        AlbumTrackRow(
                            number = index + 1,
                            track = track,
                            albumArtist = uiState.artist,
                            isCurrent = nowPlaying.first == track.id,
                            isPlaying = nowPlaying.second,
                            accent = accent,
                            onClick = {
                                playerViewModel.playQueue(uiState.tracks, index)
                            },
                            onLongClick = { menu.show(track) },
                            trailing = streamed?.let { controls ->
                                { DownloadBadge(controls.downloads[track.id], accent) { controls.onDownloadTrack(track) } }
                            },
                        )
                        }
                    }
                }

                }
            }
        }

        if (showAddToPlaylist) {
            AddToPlaylistDialog(
                trackIds = uiState.tracks.map { it.id },
                onDismiss = { showAddToPlaylist = false },
            )
        }
    }
    }
}

/**
 * The album's "more" menu: favourite, then playlist and play-next, then what removes things. For an album from another
 * source the download is in it too, and "remove from the library" is that source's own keep/unkeep.
 */
@Composable
private fun AlbumMoreMenu(
    state: com.ikko.aura.ui.components.GlassMenuState,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    streamed: StreamedAlbumControls?,
    downloadedAll: Boolean,
    onDeleteFromLibrary: () -> Unit,
    onShowFullAlbum: (() -> Unit)? = null,
) {
    var confirmRemoveDownloads by remember { mutableStateOf(false) }
    // The design's album menu (760 px wide, the page's colour at 30 %).
    com.ikko.aura.ui.components.DesignMenu(state = state, width = 304.dp, shade = 0.30f) {
        val go: (() -> Unit) -> () -> Unit = { action -> { state.close(); action() } }
        com.ikko.aura.ui.components.DesignMenuRow(
            if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
            go(onFavorite),
            icon = if (isFavorite) CupertinoIcons.StarFilled else CupertinoIcons.Star,
        )
        com.ikko.aura.ui.components.DesignMenuRow("Ajouter à la playlist", go(onAddToPlaylist), icon = CupertinoIcons.PlaylistAdd)
        com.ikko.aura.ui.components.DesignMenuRow("Lire ensuite", go(onPlayNext), icon = CupertinoIcons.Queue)
        if (onShowFullAlbum != null) {
            com.ikko.aura.ui.components.DesignMenuRow("Voir l'album complet", go(onShowFullAlbum), icon = CupertinoIcons.Album)
        }
        com.ikko.aura.ui.components.DesignMenuDivider()
        if (streamed != null) {
            if (downloadedAll) {
                com.ikko.aura.ui.components.DesignMenuRow("Supprimer le téléchargement", go { confirmRemoveDownloads = true }, icon = CupertinoIcons.Close)
            } else {
                com.ikko.aura.ui.components.DesignMenuRow("Télécharger l'album", go { streamed.onDownloadAlbum() }, icon = CupertinoIcons.ArrowDownCircle)
            }
            com.ikko.aura.ui.components.DesignMenuRow(
                if (streamed.kept) "Supprimer de la bibliothèque" else "Ajouter à la bibliothèque",
                go { streamed.onToggleKeep() },
                icon = if (streamed.kept) CupertinoIcons.Delete else CupertinoIcons.Add,
                danger = streamed.kept,
            )
        } else {
            com.ikko.aura.ui.components.DesignMenuRow("Supprimer de la bibliothèque", go(onDeleteFromLibrary), icon = CupertinoIcons.Delete, danger = true)
        }
    }
    if (confirmRemoveDownloads && streamed != null) {
        AlertDialog(
            onDismissRequest = { confirmRemoveDownloads = false },
            title = { Text("Supprimer l'album téléchargé ?") },
            text = { Text("Les fichiers de cet album seront effacés de l'appareil et ses titres retirés de la bibliothèque.") },
            confirmButton = { TextButton(onClick = { confirmRemoveDownloads = false; streamed.onRemoveAlbumDownloads() }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmRemoveDownloads = false }) { Text("Annuler") } },
        )
    }
}


/**
 * What turns the background behind this page into an album page: the aurora shows through at the top,
 * and a gradient over it gives way to one flat, dark shade of the record below.
 *
 * It rides up with the header as the list scrolls — the aurora is on a surface of its own behind the
 * window and cannot move, so the ground is what slides over it, and by the time the header has gone
 * the whole screen is the flat colour. Drawn as three fills that never change, so scrolling costs
 * the translation of one layer.
 */
@Composable
internal fun AlbumGround(ground: Color, listState: LazyListState, arrival: androidx.compose.runtime.State<Float>? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Arrives in 460 ms after a trip, leaves in 380 on the way back; always there otherwise.
                alpha = arrival?.value ?: 1f
            }
            .drawWithCache {
                // One gradient over the whole page, from the clear aurora under the header to the record's shade at the
                // foot: no flat band. It stays put while the rows scroll over it.
                val fadeStart = GROUND_CLEAR_DP.dp.toPx().coerceAtMost(size.height * 0.5f)
                val brush = Brush.verticalGradient(
                    0f to ground.copy(alpha = 0f),
                    0.35f to ground.copy(alpha = 0.32f),
                    0.7f to ground.copy(alpha = 0.7f),
                    1f to ground.copy(alpha = 0.92f),
                    startY = fadeStart,
                    endY = size.height,
                )
                onDrawBehind {
                    drawRect(
                        brush = brush,
                        topLeft = Offset(0f, fadeStart),
                        size = Size(size.width, size.height - fadeStart),
                    )
                }
            },
    )
}

/** Where the aurora is fully clear, and where the flat colour is fully there, from the top. */
private const val GROUND_CLEAR_DP = 205
private const val GROUND_SOLID_DP = 395

/** Scrolled past the header: far enough that the flat colour covers the whole screen. */
private const val GROUND_TRAVEL_PX = 2400f

/** How far the list has scrolled, in pixels, for anything that should ride along with the header. */
private fun LazyListState.headerScrollPx(fallback: Float): Float =
    if (firstVisibleItemIndex == 0) firstVisibleItemScrollOffset.toFloat() else fallback

/**
 * Keeps the clock and battery readable once rows scroll up under the status bar: nothing at rest,
 * the resting colour fading in as the header leaves.
 */
private val BITCHORD_FADE_HEIGHT = 180.dp
private const val BITCHORD_STOPS = 16

/**
 * Exactly as BitChord's progressive top fade scrim (BottomFadeScrim rotated 180°):
 * 16 stops with EaseInCubic easing from top to bottom over 180.dp.
 */
@Composable
fun ProgressiveTopScrim(
    strength: Float,
    modifier: Modifier = Modifier,
    pageColor: Color = Color.Black,
    height: androidx.compose.ui.unit.Dp = BITCHORD_FADE_HEIGHT,
) {
    if (strength <= 0.001f) return
    val brush = remember(pageColor) {
        Brush.verticalGradient(
            colorStops = Array(BITCHORD_STOPS) { i ->
                val t = i / (BITCHORD_STOPS - 1f)
                t to pageColor.copy(alpha = androidx.compose.animation.core.EaseInCubic.transform(1f - t))
            },
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { alpha = strength.coerceIn(0f, 1f) }
            .background(brush),
    )
}

@Composable
internal fun StatusBarScrim(
    solid: Color,
    listState: LazyListState,
    arrival: androidx.compose.runtime.State<Float>? = null,
) {
    // No-op: the top fade scrim is now rendered once at the app root in MainLayout.
}

private const val SCRIM_RAMP_PX = 260f

/** Rows that come with the trip, one after another; the rest are simply there. */
private const val ROWS_THAT_RISE = 8


/**
 * Positioned to align with the top menu line of Home and Library (~statusBar + 54.dp),
 * giving the Aurora room to breathe under the status bar instead of crowding the clock.
 */
internal val HEADER_TOP_BELOW_STATUS = 54.dp

private const val SKELETON_ROWS = 7

@Composable
private fun AlbumHeader(
    uiState: AlbumUiState,
    fileTags: FileTags,
    accentSoft: State<Color>,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onFavorite: () -> Unit,
    online: Boolean,
    onlineAvailable: Boolean,
    localAvailable: Boolean,
    onSource: (toOnline: Boolean) -> Unit,
    onMore: () -> Unit,
    moreMenu: @Composable () -> Unit = {},
    moreAnchor: Modifier = Modifier,
    downloadSlot: @Composable () -> Unit = {},
    hasDownload: Boolean = false,
    onArtist: () -> Unit = {},
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val meta = remember(uiState.tracks, fileTags) { albumMeta(uiState.tracks, fileTags) }

    // As the design: the cover on the left, the title / artist (in the accent) / count beside it, then one row of actions.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = com.ikko.aura.ui.components.designUnit() * 40f,
                end = com.ikko.aura.ui.components.designUnit() * 40f,
                top = statusTop + HEADER_TOP_BELOW_STATUS,
                bottom = com.ikko.aura.ui.components.designUnit() * 24f,
            ),
    ) {
        val u = com.ikko.aura.ui.components.designUnit()
        val accentInk = rememberPlayerAccent()
        Row(verticalAlignment = Alignment.CenterVertically) {
            val activeHero = com.ikko.aura.ui.components.LocalHero.current
            val tripKey = activeHero.key?.takeIf { it.startsWith("album:") && activeHero.matchesTarget(it) }
            val firstAlbumId = uiState.tracks.firstOrNull()?.albumId
            val heroKey = tripKey
                ?: if (firstAlbumId != null) "album:$firstAlbumId"
                else activeHero.key?.takeIf { it.startsWith("album:") } ?: "album:unknown"
            val heroDensity = androidx.compose.ui.platform.LocalDensity.current
            val heroTitleStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = com.ikko.aura.ui.theme.AuraFontFamily, fontWeight = FontWeight.ExtraBold,
                fontSize = com.ikko.aura.ui.components.designSp(66f), lineHeight = com.ikko.aura.ui.components.designSp(76f),
                letterSpacing = com.ikko.aura.ui.components.designSp(66f * -0.02f),
            )
            val heroSubStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = com.ikko.aura.ui.theme.AuraFontFamily, fontWeight = FontWeight.Bold,
                fontSize = com.ikko.aura.ui.components.designSp(40f), lineHeight = com.ikko.aura.ui.components.designSp(48f),
            )
            AlbumArt(
                uri = uiState.tracks.firstOrNull()?.artworkUri ?: uiState.coverUri,
                contentDescription = uiState.title,
                shape = com.ikko.aura.ui.theme.AuraShapes.AlbumCover,
                modifier = Modifier
                    .heroTarget(heroKey, radiusPx = with(heroDensity) { 8.dp.toPx() })
                    // The cover does not fly (see COVERS_FLY), but it must not be set in place before the rest either:
                    // its own arrival fades and rises it with the words, on its first composition (see coverArrival).
                    .coverArrival(heroKey)
                    .size(u * 240f),
                targetSizeDp = u * 240f,
                cacheKey = uiState.tracks.firstOrNull()?.albumId?.toString(),
            )
            Spacer(Modifier.width(u * 40f))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u * 6f)) {
              if (uiState.loading && uiState.title.isBlank()) {
                // A page that is still being fetched, from a place that did not say what it is: the lines' own heights, in grey.
                Box(Modifier.height(u * 76f), contentAlignment = Alignment.CenterStart) { com.ikko.aura.ui.components.SkeletonBar(Modifier.fillMaxWidth(0.7f).height(u * 44f)) }
                Box(Modifier.height(u * 48f), contentAlignment = Alignment.CenterStart) { com.ikko.aura.ui.components.SkeletonBar(Modifier.fillMaxWidth(0.45f).height(u * 30f)) }
                Box(Modifier.height(u * 40f), contentAlignment = Alignment.CenterStart) { com.ikko.aura.ui.components.SkeletonBar(Modifier.fillMaxWidth(0.3f).height(u * 24f)) }
              } else {
                val titleWidth = remember { androidx.compose.runtime.mutableIntStateOf(0) }
                Text(
                    text = uiState.title.trim(),
                    // The very TextStyle the hero's copy uses. Built from loose parameters, the text is merged with the
                    // theme's defaults while the copy is not, and the two measure a hair apart: the gap accumulates over
                    // the words and the last letters slide during the trip (the bug seen from Home and the library). One
                    // style for both, and the copy lands exactly on the text.
                    style = heroTitleStyle.copy(color = Color.White),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        // The column the words are laid out in, handed to the copy. The bounds the title paints are only
                        // its widest line, a hair narrower than that column, and a copy laid out on that hair broke its
                        // last word onto another line than the page's - the jump seen on the way in and on the way back.
                        .onGloballyPositioned { titleWidth.intValue = it.size.width }
                        .heroTarget(heroKey, text = com.ikko.aura.ui.components.HeroText(uiState.title.trim(), androidx.compose.ui.geometry.Rect.Zero, heroTitleStyle, Color.White, maxLines = 2, widthPx = titleWidth.intValue)),
                )
                Text(
                    text = uiState.artist.trim(),
                    style = heroSubStyle.copy(color = androidx.compose.ui.graphics.lerp(accentInk, Color.White, 0.55f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .heroTarget(heroKey, isSub = true, text = com.ikko.aura.ui.components.HeroText(uiState.artist.trim(), androidx.compose.ui.geometry.Rect.Zero, heroSubStyle, androidx.compose.ui.graphics.lerp(accentInk, Color.White, 0.55f)))
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onArtist),
                )
                if (uiState.loading) {
                    Box(Modifier.height(u * 40f).heroRise(0), contentAlignment = Alignment.CenterStart) { com.ikko.aura.ui.components.SkeletonBar(Modifier.fillMaxWidth(0.3f).height(u * 24f)) }
                } else Text(
                    text = meta.ifEmpty { "${uiState.tracks.size} titre${if (uiState.tracks.size > 1) "s" else ""}" },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = com.ikko.aura.ui.components.designSp(32f),
                    lineHeight = com.ikko.aura.ui.components.designSp(40f),
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    modifier = Modifier.heroRise(0),
                )
              }
            }
        }

        Spacer(Modifier.height(u * 40f))

        com.ikko.aura.ui.components.ActionBar(
            online = online,
            onSource = onSource,
            onlineAvailable = onlineAvailable,
            localAvailable = localAvailable,
            onShuffle = onShuffle,
            onPlay = onPlayAll,
            modifier = Modifier.heroRise(0, rows = true),
            favorite = uiState.isFavorite,
            onFavorite = onFavorite,
            // The "+" opens the page's menu: add to a playlist, play next, download, the whole album...
            add = {
                Box(moreAnchor) {
                    com.ikko.aura.ui.components.AddButton(onClick = onMore)
                    moreMenu()
                }
            },
        )
    }
}

internal data class AlbumButtonColors(
    val primaryFill: Color,
    val primaryInk: Color,
    val secondaryFill: Color,
    val secondaryBorder: Color,
    val secondaryContent: Color,
)

@Composable
internal fun rememberAlbumButtonColors(): AlbumButtonColors {
    val pageTheme = com.ikko.aura.ui.theme.LocalPageTheme.current.value
    val fallbackAccent = rememberPlayerAccent()
    return remember(pageTheme, fallbackAccent) {
        if (pageTheme != null && pageTheme !== com.ikko.aura.ui.components.AlbumTheme.Neutral) {
            val hsl = FloatArray(3)
            androidx.core.graphics.ColorUtils.colorToHSL(pageTheme.accent.toArgb(), hsl)
            val deepInk = Color(
                androidx.core.graphics.ColorUtils.HSLToColor(
                    floatArrayOf(hsl[0], hsl[1].coerceIn(0.55f, 0.90f), 0.13f),
                ),
            )
            // Deep dark onyx-velvet (much darker than the Aurora & solid ground so it never blends in),
            // paired with a soft tonal contour in the album's own hue (never white).
            val deepVelvet = androidx.compose.ui.graphics.lerp(Color(0xFF07080C), pageTheme.solid, 0.35f)
            val tonalBorder = androidx.compose.ui.graphics.lerp(pageTheme.accent, pageTheme.accentSoft, 0.45f).copy(alpha = 0.32f)
            AlbumButtonColors(
                primaryFill = androidx.compose.ui.graphics.lerp(pageTheme.accentSoft, Color.White, 0.45f),
                primaryInk = deepInk,
                secondaryFill = deepVelvet,
                secondaryBorder = tonalBorder,
                secondaryContent = androidx.compose.ui.graphics.lerp(pageTheme.accentSoft, Color.White, 0.55f),
            )
        } else {
            AlbumButtonColors(
                primaryFill = Color(0xFFF2F2F7),
                primaryInk = Color(0xFF0E0E12),
                secondaryFill = Color(0xFF121318),
                secondaryBorder = Color.White.copy(alpha = 0.16f),
                secondaryContent = Color(0xFFF2F2F7),
            )
        }
    }
}

/** The page's action row: [center] on the page's middle line, [left] ending beside it and [right] starting beside it. */
@Composable
internal fun ActionRow(
    left: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    center: @Composable () -> Unit,
    right: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            content = left,
        )
        Spacer(Modifier.width(6.dp))
        center()
        Spacer(Modifier.width(6.dp))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
            verticalAlignment = Alignment.CenterVertically,
            content = right,
        )
    }
}

private const val ACTION_BTN_HEIGHT_U = 94f

@Composable
internal fun PlayAllButton(onClick: () -> Unit, label: String = "Lire", modifier: Modifier = Modifier, inkOverride: Color? = null) {
    val colors = rememberAlbumButtonColors()
    val ink = inkOverride ?: colors.primaryInk
    val u = com.ikko.aura.ui.components.designUnit()
    Row(
        modifier = modifier
            .height(u * ACTION_BTN_HEIGHT_U)
            .clip(CircleShape)
            .background(colors.primaryFill)
            .clickable(onClick = onClick)
            .padding(horizontal = u * 14f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(CupertinoIcons.Play, contentDescription = null, tint = ink, modifier = Modifier.size(u * 32f))
        Spacer(Modifier.width(u * 12f))
        Text(text = label, fontSize = com.ikko.aura.ui.components.designSp(34f), lineHeight = com.ikko.aura.ui.components.designSp(40f), fontWeight = FontWeight.ExtraBold, color = ink, maxLines = 1)
    }
}

@Composable
internal fun SecondaryPillButton(
    onClick: () -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    val colors = rememberAlbumButtonColors()
    val u = com.ikko.aura.ui.components.designUnit()
    Row(
        modifier = modifier
            .height(u * ACTION_BTN_HEIGHT_U)
            .clip(CircleShape)
            .background(colors.secondaryFill)
            .border(1.dp, colors.secondaryBorder, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = u * 14f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.secondaryContent, modifier = Modifier.size(u * 36f))
        Spacer(Modifier.width(u * 12f))
        Text(
            text = label,
            color = colors.secondaryContent,
            fontSize = com.ikko.aura.ui.components.designSp(34f),
            lineHeight = com.ikko.aura.ui.components.designSp(40f),
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ShuffleButton(onClick: () -> Unit) {
    val colors = rememberAlbumButtonColors()
    CircleAction(onClick = onClick) {
        Icon(CupertinoIcons.Shuffle, contentDescription = "Aléatoire", tint = colors.secondaryContent, modifier = Modifier.size(com.ikko.aura.ui.components.designUnit() * 38f))
    }
}

/**
 * Matte velvet round button tinted by the cover's palette, with a soft tonal contour so it stands out from the ground.
 */
@Composable
internal fun CircleAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = rememberAlbumButtonColors()
    val u = com.ikko.aura.ui.components.designUnit()
    Box(
        modifier = Modifier
            .size(u * ACTION_BTN_HEIGHT_U)
            .clip(CircleShape)
            .background(colors.secondaryFill)
            .border(1.dp, colors.secondaryBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumTrackRow(
    number: Int,
    track: Track,
    albumArtist: String,
    isCurrent: Boolean,
    isPlaying: Boolean,
    accent: State<Color>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    /** Replaces the track number: a playlist shows each song's cover there instead. */
    leading: (@Composable () -> Unit)? = null,
    /** An album page keeps its rows inset 12 dp inside the page margin (the highlight then fills the margin); 0 aligns them on it. */
    inset: Dp = 0.dp,
    /** A line under the artist: why a recommended song is in the playlist. */
    extraLine: String? = null,
) {
    // "Wild And Alone (feat. PinkPantheress)" is the title and its credit run together; the page
    // sets them apart, the credit small beneath the title.
    val (shownTitle, subtitle) = remember(track.id, albumArtist) {
        val split = splitFeaturing(track.title)
        val credit = split.second?.let { "feat. $it" }
            ?: trackSubtitle(albumArtist, track.artist)?.takeUnless { featuringIsInTitle(track.title, it) }
        split.first to credit
    }
    val currentTint by remember { derivedStateOf { lerp(accent.value, Color.White, 0.30f) } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The row (its press mark and the playing row's highlight) reaches HIGHLIGHT_OUTSET past the page's margin on each
            // side, with rounded corners; its content sits back on the margin, on the cover's and the buttons' line.
            .padding(horizontal = com.ikko.aura.ui.components.designUnit() * 40f - HIGHLIGHT_OUTSET)
            .clip(RoundedCornerShape(ROW_RADIUS))
            .then(
                if (isCurrent) Modifier.drawWithCache {
                    onDrawBehind { drawRect(accent.value.copy(alpha = 0.16f)) }
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // As tall as a row of an external album, whose download button (32 dp) sets its height: local rows match it.
            .heightIn(min = com.ikko.aura.ui.components.designUnit() * (if (leading != null) 170f else 140f))
            .padding(horizontal = HIGHLIGHT_OUTSET + inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(com.ikko.aura.ui.components.designUnit() * 30f))
        } else Box(modifier = Modifier.width(com.ikko.aura.ui.components.designUnit() * 56f), contentAlignment = Alignment.CenterStart) {
            if (isCurrent) {
                PlayingBars(color = currentTint, animate = isPlaying)
            } else {
                Text(
                    text = number.toString(),
                    fontSize = com.ikko.aura.ui.components.designSp(32f), lineHeight = com.ikko.aura.ui.components.designSp(40f), fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = com.ikko.aura.ui.theme.TABULAR_NUMBERS),
                    color = Color.White.copy(alpha = 0.78f),
                )
            }
        }
        if (leading == null) Spacer(Modifier.width(com.ikko.aura.ui.components.designUnit() * 24f))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shownTitle,
                fontSize = com.ikko.aura.ui.components.designSp(40f),
                lineHeight = com.ikko.aura.ui.components.designSp(48f),
                fontWeight = FontWeight.Bold,
                color = if (isCurrent) currentTint else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = com.ikko.aura.ui.components.designSp(32f), lineHeight = com.ikko.aura.ui.components.designSp(40f),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (extraLine != null) {
                Text(
                    text = extraLine,
                    fontSize = com.ikko.aura.ui.components.designSp(28f), lineHeight = com.ikko.aura.ui.components.designSp(34f),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(com.ikko.aura.ui.components.designUnit() * 24f))
        val formattedDuration = remember(track.durationMs) { formatTrackDuration(track.durationMs) }
        Text(
            text = formattedDuration,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = com.ikko.aura.ui.theme.TABULAR_NUMBERS,
                fontSize = com.ikko.aura.ui.components.designSp(30f), lineHeight = com.ikko.aura.ui.components.designSp(36f), fontWeight = FontWeight.SemiBold),
            color = Color.White.copy(alpha = 0.78f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(com.ikko.aura.ui.components.designUnit() * 90f),
            maxLines = 1,
        )
        if (trailing != null) {
            Box(modifier = Modifier.size(com.ikko.aura.ui.components.designUnit() * 88f), contentAlignment = Alignment.Center) { trailing() }
        }
    }
}

/** A song's download as a small mark: an arrow to start it, a ring while it runs, a check once it is a file. */
@Composable
private fun DownloadBadge(entry: DownloadEntry?, accent: State<Color>, onDownload: () -> Unit) {
    when (entry?.state) {
        DownloadState.DONE -> Icon(CupertinoIcons.Check, contentDescription = "Téléchargé", tint = accent.value, modifier = Modifier.size(18.dp))
        DownloadState.DOWNLOADING -> CircularProgressIndicator(
            progress = { entry.progress.coerceIn(0.03f, 1f) },
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = accent.value,
            trackColor = Color.White.copy(alpha = 0.15f),
        )
        DownloadState.QUEUED -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = AuraColors.TextTertiary,
            trackColor = Color.White.copy(alpha = 0.15f),
        )
        else -> IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
            Icon(CupertinoIcons.ArrowDownCircle, contentDescription = "Télécharger", tint = AuraColors.TextTertiary, modifier = Modifier.size(20.dp))
        }
    }
}

/** The album's download as one icon (in the header's capsule): its arrow, its progress ring, its check. */
@Composable
internal fun AlbumDownloadChip(tracks: List<Track>, controls: StreamedAlbumControls) {
    val states = tracks.map { controls.downloads[it.id] }
    val done = states.count { it?.state == DownloadState.DONE }
    val busy = states.any { it?.state == DownloadState.QUEUED || it?.state == DownloadState.DOWNLOADING }
    val whole = tracks.isNotEmpty() && done == tracks.size
    var confirmRemove by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable { if (whole) confirmRemove = true else if (!busy) controls.onDownloadAlbum() },
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> {
                val progress = states.sumOf { (it?.progress ?: 0f).toDouble() }.toFloat() / tracks.size.coerceAtLeast(1)
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0.03f, 1f) },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
            }
            whole -> Icon(CupertinoIcons.Check, contentDescription = "Téléchargé", tint = rememberPlayerAccent(), modifier = Modifier.size(20.dp))
            else -> Icon(CupertinoIcons.ArrowDownCircle, contentDescription = "Télécharger l'album", tint = rememberPlayerAccent(), modifier = Modifier.size(22.dp))
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Supprimer l'album téléchargé ?") },
            text = { Text("Les fichiers de cet album seront effacés de l'appareil et ses titres retirés de la bibliothèque.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; controls.onRemoveAlbumDownloads() }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Annuler") } },
        )
    }
}

/**
 * Three bars, the way a "now playing" marker is drawn everywhere: moving while the music is, frozen at
 * a resting shape when it is paused.
 *
 * Driven by a coarse clock that only runs while it is moving — twenty steps a second is plenty for a
 * 16dp marker. An infinite animation here redrew the page on every frame at 90Hz for as long as a
 * track was playing, which meant the whole screen (and the mini player's blur of it) was being
 * re-rendered continuously for the sake of three small bars.
 */
@Composable
private fun PlayingBars(color: Color, animate: Boolean) {
    val clock = remember { mutableFloatStateOf(0f) }
    if (animate) {
        LaunchedEffect(Unit) {
            // On a timer of its own, BARS_STEP_MS apart, not on the display's frame clock: a loop on every frame
            // redrew the page at the screen's rate (90 a second here) for three bars that look the same at 25.
            var last = android.os.SystemClock.uptimeMillis()
            while (true) {
                kotlinx.coroutines.delay(BARS_STEP_MS)
                val now = android.os.SystemClock.uptimeMillis()
                clock.floatValue += (now - last) / 1000f
                last = now
            }
        }
    }
    // Its own layer, so each step of the bars re-records these three bars and not the page around them:
    // without one the redraw lands on the nearest enclosing layer, the album page's list.
    Canvas(modifier = Modifier.size(width = 16.dp, height = 15.dp).graphicsLayer()) {
        val barWidth = 3.dp.toPx()
        val gap = (size.width - barWidth * 3) / 2
        val t = clock.floatValue
        val heights = if (animate) {
            floatArrayOf(wave(t, 6.4f, 0f), wave(t, 4.6f, 1.7f), wave(t, 5.5f, 3.1f))
        } else {
            floatArrayOf(0.5f, 1f, 0.7f)
        }
        heights.forEachIndexed { i, fraction ->
            val barHeight = size.height * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

private const val BARS_STEP_MS = 40L

/** A bar's height between 0.3 and 1, on its own rate and phase. */
private fun wave(t: Float, rate: Float, phase: Float): Float = 0.65f + 0.35f * sin(t * rate + phase)

/** "Électro · 2025 · Lossless": whatever of genre, year and quality the files actually carry. */
private fun albumMeta(tracks: List<Track>, tags: FileTags): String {
    if (tracks.isEmpty()) return ""
    val genre = tags.genre
    val year = tracks.maxOf { it.year }.takeIf { it > 0 } ?: tags.year.takeIf { it > 0 }
    val quality = when {
        tracks.any { it.isHiRes } -> "Hi-Res"
        tracks.any { it.isLossless } -> "Lossless"
        else -> tracks.firstOrNull { it.format != AudioFormat.UNKNOWN }?.format?.name
    }
    // A record with no tags to speak of still gets a line, rather than a gap under the artist.
    val count = if (genre == null && year == null) "${tracks.size} titre${if (tracks.size > 1) "s" else ""}" else null
    return listOfNotNull(genre, year?.toString(), count, quality).joinToString(" · ")
}

private val FEATURING_SEPARATORS = Regex("\\s*(?:;|/|,|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b)\\s*", RegexOption.IGNORE_CASE)

/**
 * What to print under a track's title: nothing when it is by the album's artist, "feat. …" when the
 * album artist is credited alongside others, and the artist as tagged when it is somebody else's.
 */
private fun trackSubtitle(albumArtist: String, trackArtist: String): String? {
    if (trackArtist.isBlank() || trackArtist.equals(albumArtist, ignoreCase = true)) return null
    val parts = trackArtist.split(FEATURING_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
    val others = parts.filterNot { it.equals(albumArtist, ignoreCase = true) }
    return if (others.size < parts.size && others.isNotEmpty()) "feat. ${others.joinToString(", ")}" else trackArtist
}

private val TITLE_FEATURING = Regex(
    "\\s*[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|with)\\s+([^)\\]]+?)\\s*[)\\]]\\s*$",
    RegexOption.IGNORE_CASE,
)

/** The title without a trailing "(feat. X)", and X if there was one. */
private fun splitFeaturing(title: String): Pair<String, String?> {
    val match = TITLE_FEATURING.find(title) ?: return title to null
    return title.removeRange(match.range).trim().ifEmpty { title } to match.groupValues[1]
}

/** "Switchblade (feat. Ty Dolla Sign)" already says so; saying it again underneath is noise. */
private fun featuringIsInTitle(title: String, subtitle: String): Boolean {
    val names = subtitle.removePrefix("feat. ").split(", ").filter { it.isNotBlank() }
    return names.isNotEmpty() && names.all { title.contains(it, ignoreCase = true) }
}

internal fun formatTrackDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (seconds < 10) "$minutes:0$seconds" else "$minutes:$seconds"
}
