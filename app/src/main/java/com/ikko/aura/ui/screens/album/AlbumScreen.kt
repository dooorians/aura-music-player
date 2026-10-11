package com.ikko.aura.ui.screens.album

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
import com.ikko.aura.ui.components.scrollTopBlur
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

internal val PAGE_PADDING = 15.dp
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
)

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
    // The "F" page is lit by blurred blobs of the cover's own colours (see Immersive.kt).
    val immersive = com.ikko.aura.ui.components.LocalImmersivePalette.current
    val immersivePalette = remember(theme) {
        val (h, sat) = com.ikko.aura.ui.components.hueAndSaturation(theme.accent)
        com.ikko.aura.ui.components.immersiveFromCover(h, sat, dark = true)
    }
    DisposableEffect(immersivePalette) {
        immersive.value = immersivePalette
        onDispose { if (immersive.value === immersivePalette) immersive.value = null }
    }

    val accent = rememberUpdatedState(theme.accent)
    val accentSoft = rememberUpdatedState(theme.accentSoft)

    val density = LocalDensity.current
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }

    androidx.compose.runtime.CompositionLocalProvider(LocalPageTheme provides localPageThemeState) {
    MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
        GradientScreenScaffold(
            applyStatusBarInset = false,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                run {

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    item(key = "header") {
                        AlbumHeader(
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
                            hasDownload = streamed != null,
                            downloadSlot = {
                                if (streamed != null) {
                                    AlbumDownloadChip(uiState.tracks, streamed)
                                }
                            },
                            onMore = { moreMenuState.open() },
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
                                )
                            },
                        )
                    }
                    itemsIndexed(uiState.tracks, key = { _, track -> track.id }) { index, track ->
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
internal fun AlbumGround(ground: Color, listState: LazyListState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val fadeEnd = GROUND_SOLID_DP.dp.toPx()
                translationY = -listState.headerScrollPx(fadeEnd)
            }
            .drawWithCache {
                val fadeStart = GROUND_CLEAR_DP.dp.toPx()
                val fadeEnd = GROUND_SOLID_DP.dp.toPx()
                val gradientBrush = Brush.verticalGradient(
                    0f to ground.copy(alpha = 0f),
                    0.42f to ground.copy(alpha = 0.38f),
                    0.78f to ground.copy(alpha = 0.86f),
                    1f to ground,
                    startY = fadeStart,
                    endY = fadeEnd,
                )
                onDrawBehind {
                    drawRect(
                        brush = gradientBrush,
                        topLeft = Offset(0f, fadeStart),
                        size = Size(size.width, fadeEnd - fadeStart),
                    )
                    drawRect(ground, topLeft = Offset(0f, fadeEnd), size = Size(size.width, size.height * 3f))
                }
            },
    )
}

/** Where the aurora is fully clear, and where the flat colour is fully there, from the top. */
private const val GROUND_CLEAR_DP = 170
private const val GROUND_SOLID_DP = 360

/** Scrolled past the header: far enough that the flat colour covers the whole screen. */
private const val GROUND_TRAVEL_PX = 2400f

/** How far the list has scrolled, in pixels, for anything that should ride along with the header. */
private fun LazyListState.headerScrollPx(fallback: Float): Float =
    if (firstVisibleItemIndex == 0) firstVisibleItemScrollOffset.toFloat() else fallback

/**
 * Keeps the clock and battery readable once rows scroll up under the status bar: nothing at rest,
 * the resting colour fading in as the header leaves.
 */
@Composable
internal fun StatusBarScrim(solid: Color, listState: LazyListState) {
    val height = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 18.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                alpha = (listState.headerScrollPx(Float.MAX_VALUE) / SCRIM_RAMP_PX).coerceIn(0f, 1f)
            }
            .drawWithCache {
                val brush = Brush.verticalGradient(
                    0f to solid.copy(alpha = 0.94f),
                    1f to solid.copy(alpha = 0f),
                )
                onDrawBehind { drawRect(brush) }
            },
    )
}

private const val SCRIM_RAMP_PX = 260f


/**
 * The header starts where the other pages' titles do — Home's "Favoris", the library's "Bibliothèque",
 * "Réglages": 21 dp under the status bar (8 dp of margin, then a 56 dp title row centring a 30 sp line). At 10 dp
 * it sat noticeably higher than everything else in the app (the user's report).
 */
internal val HEADER_TOP_BELOW_STATUS = 21.dp

@Composable
private fun AlbumHeader(
    uiState: AlbumUiState,
    fileTags: FileTags,
    accentSoft: State<Color>,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
    moreMenu: @Composable () -> Unit = {},
    moreAnchor: Modifier = Modifier,
    downloadSlot: @Composable () -> Unit = {},
    hasDownload: Boolean = false,
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val meta = remember(uiState.tracks, fileTags) { albumMeta(uiState.tracks, fileTags) }

    // As the design: the cover on the left, the title / artist (in the accent) / count beside it, then one row of actions.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = com.ikko.aura.ui.components.designUnit() * 40f, end = com.ikko.aura.ui.components.designUnit() * 40f, top = com.ikko.aura.ui.components.designUnit() * 128f, bottom = com.ikko.aura.ui.components.designUnit() * 24f),
    ) {
        val u = com.ikko.aura.ui.components.designUnit()
        val accentInk = rememberPlayerAccent()
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(
                uri = uiState.tracks.firstOrNull()?.artworkUri,
                contentDescription = uiState.title,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(u * 50f),
                modifier = Modifier.size(u * 240f),
                targetSizeDp = u * 240f,
                cacheKey = uiState.tracks.firstOrNull()?.albumId?.toString(),
            )
            Spacer(Modifier.width(u * 40f))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u * 6f)) {
                Text(
                    text = uiState.title.trim(),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = com.ikko.aura.ui.components.designSp(66f),
                    lineHeight = com.ikko.aura.ui.components.designSp(76f),
                    letterSpacing = com.ikko.aura.ui.components.designSp(66f * -0.02f),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = uiState.artist.trim(),
                    fontWeight = FontWeight.Bold,
                    fontSize = com.ikko.aura.ui.components.designSp(40f),
                    lineHeight = com.ikko.aura.ui.components.designSp(48f),
                    // The mock's artist is the cover's colour pushed toward white (#d7d3ff over a violet cover).
                    color = androidx.compose.ui.graphics.lerp(accentInk, Color.White, 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta.ifEmpty { "${uiState.tracks.size} titre${if (uiState.tracks.size > 1) "s" else ""}" },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = com.ikko.aura.ui.components.designSp(32f),
                    lineHeight = com.ikko.aura.ui.components.designSp(40f),
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.height(u * 40f))

        Row(horizontalArrangement = Arrangement.spacedBy(u * 25f), verticalAlignment = Alignment.CenterVertically) {
            PlayAllButton(onClick = onPlayAll, label = "Lire tout", modifier = Modifier.weight(1.15f))
            AuraButton(
                onClick = onShuffle,
                modifier = Modifier.weight(1f),
                shape = CircleShape,
                minHeight = u * 112f,
                contentPadding = PaddingValues(horizontal = u * 8f),
            ) {
                Icon(CupertinoIcons.Shuffle, contentDescription = null, tint = Color.White, modifier = Modifier.size(u * 44f))
                Spacer(Modifier.width(u * 18f))
                Text(text = "Mélanger", color = Color.White, fontSize = com.ikko.aura.ui.components.designSp(40f), lineHeight = com.ikko.aura.ui.components.designSp(48f), fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            CircleAction(onClick = onFavorite) {
                FillableIcon(
                    outline = CupertinoIcons.Star,
                    filled = CupertinoIcons.StarFilled,
                    active = uiState.isFavorite,
                    contentDescription = "Favori",
                    tint = Color.White,
                    modifier = Modifier.size(u * 44f),
                )
            }
            Box(moreAnchor) {
                CircleAction(onClick = onMore) {
                    Icon(CupertinoIcons.MoreVertical, contentDescription = "Plus d'options", tint = Color.White, modifier = Modifier.size(u * 44f))
                }
                moreMenu()
            }
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

@Composable
internal fun PlayAllButton(onClick: () -> Unit, label: String = "Lire", modifier: Modifier = Modifier, inkOverride: Color? = null) {
    // The white capsule stays white; its text and icon take the accent's hue, deepened: the pale accent the other
    // controls wear would not read on white.
    val accent = rememberPlayerAccent()
    val ink = inkOverride ?: remember(accent) {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(accent.toArgb(), hsl)
        Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceIn(0.45f, 0.85f), 0.28f)))
    }
    Row(
        modifier = modifier
            .height(com.ikko.aura.ui.components.designUnit() * 112f)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(CupertinoIcons.Play, contentDescription = null, tint = ink, modifier = Modifier.size(com.ikko.aura.ui.components.designUnit() * 44f))
        Spacer(Modifier.width(com.ikko.aura.ui.components.designUnit() * 18f))
        Text(text = label, fontSize = com.ikko.aura.ui.components.designSp(40f), lineHeight = com.ikko.aura.ui.components.designSp(48f), fontWeight = FontWeight.ExtraBold, color = ink, maxLines = 1)
    }
}

@Composable
internal fun ShuffleButton(onClick: () -> Unit) {
    CircleAction(onClick = onClick) {
        Icon(CupertinoIcons.Shuffle, contentDescription = "Aléatoire", tint = Color.White, modifier = Modifier.size(com.ikko.aura.ui.components.designUnit() * 44f))
    }
}

/**
 * The page's round button: frosted glass with a dark veil and a white glyph, so it stands out of a page lit with the same
 * hue as the cover (a glyph in the cover's colour over glass of that colour was close to invisible).
 */
@Composable
internal fun CircleAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    com.ikko.aura.ui.components.FrostedCircleButton(
        onClick = onClick,
        size = com.ikko.aura.ui.components.designUnit() * 112f,
        scrim = 0.34f,
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
