package com.ikko.aura.ui.screens.library

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.toArgb
import com.ikko.aura.ui.theme.LocalSearchOpener
import com.ikko.aura.ui.components.frosted
import com.ikko.aura.ui.components.AuraMenu
import com.ikko.aura.ui.theme.AuraMotion
import androidx.compose.animation.togetherWith
import com.ikko.aura.ui.components.LibraryContextTarget
import com.ikko.aura.ui.components.MediaContextHost
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.rememberCoroutineScope
import com.ikko.aura.ui.components.AlphabetIndex
import com.ikko.aura.ui.components.indexLetterOf
import com.ikko.aura.ui.components.rememberScrollTarget
import com.ikko.aura.ui.components.LocalIndexStripShift
import com.ikko.aura.ui.components.ScrollTarget
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.ikko.aura.data.AlbumLayoutMode
import com.ikko.aura.data.TrackLayoutMode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Artist
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.PlaylistSummary
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.navigation.route
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.collapsingHeight
import com.ikko.aura.ui.components.rememberCollapseOffset
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.SquircleCard
import com.ikko.aura.ui.components.TrackListItem
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.LibraryFilter
import com.ikko.aura.viewmodel.LibrarySort
import com.ikko.aura.viewmodel.LibraryViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.PlaylistsViewModel
import kotlin.math.roundToInt
import com.ikko.aura.ui.theme.CoverText
import com.ikko.aura.ui.theme.LocalCoverColors
import com.ikko.aura.ui.theme.LibraryTypography
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import com.ikko.aura.ui.components.libraryTabIndex
import com.ikko.aura.ui.components.rememberCapsuleScrollConnection
import com.ikko.aura.ui.components.TabCapsuleState
import com.ikko.aura.ui.components.LocalTabCapsule
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.lazy.grid.itemsIndexed
import com.ikko.aura.ui.components.LocalPageMotion
import com.ikko.aura.ui.components.PageMotionSpec
import com.ikko.aura.ui.components.scrollTopBlur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import com.ikko.aura.ui.components.AuraChip
import com.ikko.aura.ui.components.AuraIconButton
import com.ikko.aura.ui.components.AuraIconGroup
import com.ikko.aura.ui.components.AuraGroupIcon
import com.ikko.aura.ui.components.GlassMenu
import com.ikko.aura.ui.components.GlassMenuItem
import com.ikko.aura.ui.components.GlassMenuState
import com.ikko.aura.ui.components.glassMenuAnchor
import com.ikko.aura.ui.components.rememberGlassMenuState
import com.ikko.aura.ui.components.ButtonTone
import androidx.compose.foundation.layout.width

/**
 * The library's measurements, from the top of the screen (status bar included): the title's baseline sits
 * 89 dp down, the count's 111, and the first row of covers starts at 129. One margin, 21 dp, at both sides.
 */
private val PAGE_MARGIN = 12.dp
// The baseline is placed exactly at 45.dp relative to the status bar, which perfectly aligns
// with Home's "Favoris" and Settings' "Réglages" (which are centered in a 56dp box starting at 8dp).
private val TITLE_RELATIVE_BASELINE = 45.dp
private val COUNT_RELATIVE_BASELINE = 69.dp
private val HEADER_RELATIVE_HEIGHT = 88.dp
private val HEADER_BUTTON = 48.dp
private val STRIP_RELATIVE_TOP = 78.dp
private val HEADER_BUTTON_GAP = 10.dp

/** How long the list takes to slide when the capsule changes the view. */
private const val FILTER_SLIDE_MS = 320

@Composable
fun LibraryScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel = hiltViewModel(),
    playlistsViewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val uiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val playlists by playlistsViewModel.playlists.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    // The header folds away with the status bar's height included: the page runs under the bar (see HomeScreen),
    // so once folded the list goes on up beneath it and melts into it. At rest the list starts where it did.
    val headerHeight = com.ikko.aura.ui.components.designUnit() * 424f
    val headerHeightPx = with(density) { headerHeight.toPx() }
    var headerOffsetPx by rememberCollapseOffset()
    // Same rule as the top nav row: collapse while scrolling down, and only come back once the
    // list has run out of upward scroll (leftover in onPostScroll), so the header stays out of the
    // way until you are actually back at the top rather than reappearing on the first reversal.
    // Keyed on the height: first composed before the status bar's inset is known, it kept the shorter height and folded
    // the header short of the bar, so the list stopped there instead of going on beneath it.
    val headerScrollConnection = remember(headerHeightPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                val newOffset = (headerOffsetPx + available.y).coerceAtLeast(-headerHeightPx)
                val consumed = newOffset - headerOffsetPx
                headerOffsetPx = newOffset
                return Offset(0f, consumed)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y <= 0f) return Offset.Zero
                val newOffset = (headerOffsetPx + available.y).coerceAtMost(0f)
                val used = newOffset - headerOffsetPx
                headerOffsetPx = newOffset
                return Offset(0f, used)
            }
        }
    }

    val scope = rememberCoroutineScope()
    // Shared by the sort button and the menu laid over the page: the menu grows out of the button, and the button
    // empties while it does.
    val sortMenu = rememberGlassMenuState()
    val capsule = LocalTabCapsule.current
    val reduceMotion = LocalPageMotion.current?.reduceMotion == true
    // A new view starts at its top, so its header is back, and the capsule with it.
    // Only on a real change of filter, though: this also runs when the page comes back into composition — back
    // from an album opened further down — and it used to reopen the header there over a list still scrolled
    // where it had been left. The filter last seen is saved with the page, so coming back is told apart.
    var lastFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<LibraryFilter?>(null) }
    androidx.compose.runtime.LaunchedEffect(uiState.filter) {
        if (lastFilter != null && lastFilter != uiState.filter) headerOffsetPx = 0f
        lastFilter = uiState.filter
        capsule?.scrolledAway = false
    }
    val capsuleScroll = rememberCapsuleScrollConnection(capsule, density.density) { headerOffsetPx == 0f }

    /**
     * Puts the header where the new scroll position says it should be, after a jump the nested
     * scroll never saw. At the top the header belongs open; anywhere else it belongs out of the
     * way, which is the whole reason the strip and the back-to-top button exist.
     */
    fun settleHeaderAfterJump(atTop: Boolean) {
        headerOffsetPx = if (atTop) 0f else -headerHeightPx
        // Back at the top, the tab capsule belongs out too: the jump is no scroll, so nothing else brought it back.
        if (atTop) capsule?.scrolledAway = false
    }

    val countText = when (uiState.filter) {
        LibraryFilter.PLAYLISTS -> countLabel(playlists.size, "playlist")
        LibraryFilter.ALBUMS -> countLabel(uiState.albums.size, "album")
        LibraryFilter.ARTISTS -> countLabel(uiState.artists.size, "artiste")
        LibraryFilter.TRACKS -> countLabel(uiState.tracks.size, "titre")
    }

    MediaContextHost(
        navController = navController,
        playerViewModel = playerViewModel,
        onLibraryChanged = libraryViewModel::rescan,
    ) { context ->
    val stripTopPx = with(density) { (statusBarTop + STRIP_RELATIVE_TOP).toPx() }
    val listTopBasePx = headerHeightPx
    androidx.compose.runtime.CompositionLocalProvider(
        // The strip holds still on the screen — 119 dp from the top — while the header above the list folds.
        LocalIndexStripShift provides { stripTopPx - (listTopBasePx + headerOffsetPx) },
    ) {
    Box(Modifier.fillMaxSize()) {
    GradientScreenScaffold(
        applyStatusBarInset = false,
        // On the whole page, not only the list: the header folds up under the status bar too, and has to melt into
        // it the same way rather than slide under it sharp. Grows as the header collapses (see scrollTopBlur).
        modifier = Modifier.scrollTopBlur(topInset = statusBarTop) { -headerOffsetPx },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .collapsingHeight(headerHeightPx) { headerOffsetPx }
                .clipToBounds(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, headerOffsetPx.roundToInt()) },
            ) {
                LibraryHeader(
                    statusBarTop = statusBarTop,
                    countText = countText,
                    sortMenu = sortMenu,
                    filter = uiState.filter,
                    onFilter = libraryViewModel::setFilter,
                    onSearch = LocalSearchOpener.current,
                    onSmartShuffle = {
                        scope.launch {
                            val queue = libraryViewModel.smartShuffle()
                            if (queue.isNotEmpty()) {
                                playerViewModel.playQueue(queue, 0, shuffle = true)
                            }
                        }
                    },
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // The capsule's, outermost: it hears every scroll before the header takes its share, so a scroll that only
                // moves the header still counts.
                .nestedScroll(capsuleScroll)
                .nestedScroll(headerScrollConnection),
        ) {
            // The four filters are peers, exactly like the top tabs, so they animate with the
            // same vocabulary — switching them used to swap content instantly with nothing at all.
            val scanning = uiState.isScanning && uiState.tracks.isEmpty()
            AnimatedContent(
                targetState = if (scanning) null else uiState.filter,
                transitionSpec = {
                    val from = initialState
                    val to = targetState
                    if (from == null || to == null) {
                        AuraMotion.peerEnter togetherWith AuraMotion.peerExit
                    } else if (reduceMotion) {
                        fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                    } else {
                        // One slide, in the direction of the chips: going right along them, the list slides
                        // in from the right; going back, from the left. Both go at once, like pages.
                        val direction = if (chipIndex(to) >= chipIndex(from)) 1 else -1
                        val slide = tween<IntOffset>(FILTER_SLIDE_MS, easing = FastOutSlowInEasing)
                        (slideInHorizontally(slide) { it * direction } + fadeIn(tween(FILTER_SLIDE_MS / 2))) togetherWith
                            (slideOutHorizontally(slide) { -it * direction } + fadeOut(tween(FILTER_SLIDE_MS / 2)))
                    }
                },
                label = "library-filter",
            ) { filter ->
                when (filter) {
                    null -> ScanningState()
                    LibraryFilter.TRACKS -> TrackList(
                        tracks = uiState.tracks,
                        sort = uiState.sort,
                        layoutMode = uiState.trackLayoutMode,
                        onTrackClick = { index ->
                            playerViewModel.playQueue(uiState.tracks, index)
                        },
                        onTrackLongPress = { context.show(it) },
                        onJumped = ::settleHeaderAfterJump,
                    )
                    LibraryFilter.ALBUMS -> AlbumGrid(
                        albums = uiState.albums,
                        sort = uiState.sort,
                        layoutMode = uiState.albumLayoutMode,
                        navController = navController,
                        onAlbumLongPress = { album ->
                            context.show(
                                LibraryContextTarget.AlbumTarget(
                                    album = album,
                                    tracks = uiState.tracks.filter { it.albumId == album.id },
                                ),
                            )
                        },
                        onJumped = ::settleHeaderAfterJump,
                    )
                    LibraryFilter.ARTISTS -> ArtistList(
                        artists = uiState.artists,
                        navController = navController,
                        onJumped = ::settleHeaderAfterJump,
                    )
                    LibraryFilter.PLAYLISTS -> PlaylistsList(
                        playlists = playlists,
                        onCreate = playlistsViewModel::createPlaylist,
                        onOpen = { id -> navController.navigate(AuraDestination.PlaylistPattern.route(id.toString())) },
                    )
                }
            }
        }
    }
    // Over the whole page and outside the header's clip: the glass has to be able to leave the header's bounds.
    // The design's sort menu: a check button above it, three rows, the chosen one on a lit bed in the accent.
    val sortAccent = com.ikko.aura.ui.components.rememberPlayerAccent()
    com.ikko.aura.ui.components.DesignMenu(
        state = sortMenu,
        width = 240.dp,
        shade = 0.16f,
        closeIcon = CupertinoIcons.Check,
        closeTint = sortAccent,
    ) {
        com.ikko.aura.ui.components.DesignMenuRow("Nom", { libraryViewModel.setSort(LibrarySort.NAME); sortMenu.close() },
            selected = uiState.sort == LibrarySort.NAME, accent = sortAccent)
        com.ikko.aura.ui.components.DesignMenuRow("Artiste", { libraryViewModel.setSort(LibrarySort.ARTIST); sortMenu.close() },
            selected = uiState.sort == LibrarySort.ARTIST, accent = sortAccent)
        com.ikko.aura.ui.components.DesignMenuRow("Date d'ajout", { libraryViewModel.setSort(LibrarySort.DATE_ADDED); sortMenu.close() },
            selected = uiState.sort == LibrarySort.DATE_ADDED, accent = sortAccent)
    }
    }
    }
    }
}

/** "128 albums": the count with its noun, agreed, and the thousands set off by a narrow no-break space. */
/** Where a view sits among the header's chips, left to right — the direction the list slides when it changes. */
private val CHIP_ORDER = listOf(LibraryFilter.TRACKS, LibraryFilter.ALBUMS, LibraryFilter.ARTISTS, LibraryFilter.PLAYLISTS)
private fun chipIndex(filter: LibraryFilter): Int = CHIP_ORDER.indexOf(filter).coerceAtLeast(0)

private fun countLabel(count: Int, noun: String): String {
    val number = java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE).format(count)
    return "$number $noun" + if (count > 1) "s" else ""
}

@Composable
private fun LibraryHeader(
    statusBarTop: androidx.compose.ui.unit.Dp,
    countText: String,
    sortMenu: GlassMenuState,
    onSmartShuffle: () -> Unit,
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    onSearch: () -> Unit,
) {
    // The "F" header of the design: the title and the count, a frosted toolbar (search, shuffle, sort) at the right, then the
    // four views as chips. Laid out in the design's pixels (1080 px wide = the phone's width).
    val u = com.ikko.aura.ui.components.designUnit()
    val accent = com.ikko.aura.ui.components.rememberPlayerAccent()
    val face = androidx.compose.ui.text.TextStyle(fontFamily = com.ikko.aura.ui.theme.AuraFontFamily, color = Color.White)
    val chipInk = remember(accent) {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(accent.toArgb(), hsl)
        Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsl[0], (hsl[1] * 0.5f).coerceAtMost(0.5f), 0.16f)))
    }
    Column(Modifier.fillMaxWidth().padding(top = u * 128f, start = u * 40f, end = u * 40f)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(u * 10f), modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    "Bibliothèque",
                    style = face.copy(fontSize = com.ikko.aura.ui.components.designSp(66f), lineHeight = com.ikko.aura.ui.components.designSp(76f),
                        fontWeight = FontWeight.ExtraBold, letterSpacing = com.ikko.aura.ui.components.designSp(66f * -0.02f)),
                    maxLines = 1,
                )
                Text(
                    countText,
                    style = face.copy(fontSize = com.ikko.aura.ui.components.designSp(34f), lineHeight = com.ikko.aura.ui.components.designSp(42f),
                        fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.78f)),
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier
                    .offset(y = u * -18f)
                    .height(u * 112f)
                    .shadow(u * 14f, CircleShape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
                    .clip(CircleShape)
                    .frosted(CircleShape)
                    .padding(horizontal = u * 6f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton(u, onSearch) { Icon(CupertinoIcons.Search, "Rechercher", tint = accent, modifier = Modifier.size(u * 44f)) }
                Box(Modifier.width(u * 1.5f).height(u * 44f).background(Color.White.copy(alpha = 0.28f)))
                ToolbarButton(u, onSmartShuffle) { Icon(CupertinoIcons.Shuffle, "Lecture aléatoire", tint = accent, modifier = Modifier.size(u * 44f)) }
                Box(Modifier.width(u * 1.5f).height(u * 44f).background(Color.White.copy(alpha = 0.28f)))
                ToolbarButton(u, sortMenu::open, Modifier.glassMenuAnchor(sortMenu)) {
                    Icon(CupertinoIcons.Sort, "Trier", tint = accent, modifier = Modifier.size(u * 44f))
                }
            }
        }
        Row(Modifier.padding(top = u * 36f), horizontalArrangement = Arrangement.spacedBy(u * 12f)) {
            listOf(
                LibraryFilter.TRACKS to "Titres",
                LibraryFilter.ALBUMS to "Albums",
                LibraryFilter.ARTISTS to "Artistes",
                LibraryFilter.PLAYLISTS to "Playlists",
            ).sortedBy { chipIndex(it.first) }.forEach { (f, label) ->
                val on = f == filter
                Box(
                    Modifier
                        .height(u * 88f)
                        .clip(CircleShape)
                        .then(
                            if (on) Modifier.background(Color.White)
                            else Modifier
                                .background(Color.White.copy(alpha = 0.14f))
                                .border(u * 1.5f, Color.White.copy(alpha = 0.24f), CircleShape),
                        )
                        .clickable { onFilter(f) }
                        .padding(horizontal = u * 44f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = face.copy(
                            fontSize = com.ikko.aura.ui.components.designSp(38f), lineHeight = com.ikko.aura.ui.components.designSp(46f),
                            fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Bold,
                            color = if (on) chipInk else Color.White,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    u: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.width(u * 104f).height(u * 100f).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    sort: LibrarySort,
    layoutMode: TrackLayoutMode,
    onTrackClick: (Int) -> Unit,
    onTrackLongPress: (Track) -> Unit,
    onJumped: (Boolean) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val isList = layoutMode == TrackLayoutMode.LIST
    val columns = if (isList) 1 else 2
    IndexedList(
        target = rememberScrollTarget(gridState),
        indexKeys = remember(tracks, sort) { tracks.indexKeysFor(sort) { it.title to it.artist } },
        onJumped = onJumped,
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(// Rows carry 4 dp of their own: taken off, the covers sit on the title's line.
            start = com.ikko.aura.ui.components.designUnit() * 40f - 4.dp, end = INDEX_GUTTER, top = 0.dp, bottom = listBottomPadding() + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isList) 0.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (isList) 2.dp else 0.dp),
        ) {
            gridItemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                TrackListItem(
                    track = track,
                    onClick = { onTrackClick(index) },
                    onLongClick = { onTrackLongPress(track) },
                    artSize = if (isList) 48.dp else 52.dp,
                )
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<Album>,
    sort: LibrarySort,
    layoutMode: AlbumLayoutMode,
    navController: NavHostController,
    onAlbumLongPress: (Album) -> Unit,
    onJumped: (Boolean) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val isList = layoutMode == AlbumLayoutMode.LIST
    val columns = when (layoutMode) {
        AlbumLayoutMode.LIST -> 1
        AlbumLayoutMode.GRID_2 -> 2
        AlbumLayoutMode.GRID_3 -> 3
    }
    IndexedList(
        target = rememberScrollTarget(gridState),
        onJumped = onJumped,
        indexKeys = remember(albums, sort) {
            albums.map { if (sort == LibrarySort.ARTIST) it.artist else it.title }
        },
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = com.ikko.aura.ui.components.designUnit() * 40f,
                end = INDEX_GUTTER,
                top = if (isList) 8.dp else 0.dp,
                bottom = listBottomPadding() + 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (isList) 0.dp else com.ikko.aura.ui.components.designUnit() * 25f),
            verticalArrangement = Arrangement.spacedBy(if (isList) 2.dp else com.ikko.aura.ui.components.designUnit() * 40f),
        ) {
            itemsIndexed(albums, key = { _, album -> album.id }) { index, album ->
                if (isList) {
                    AlbumListItem(
                        album = album,
                        onClick = { navController.navigate(album.route()) },
                        onLongClick = { onAlbumLongPress(album) },
                    )
                } else {
                    // The "F" grid: square covers with 32 px corners and a hairline rim, then the title and the artist, in design px.
                    val u = com.ikko.aura.ui.components.designUnit()
                    val coverShape = androidx.compose.foundation.shape.RoundedCornerShape(u * 32f)
                    val face = androidx.compose.ui.text.TextStyle(fontFamily = com.ikko.aura.ui.theme.AuraFontFamily, color = Color.White)
                    Column(verticalArrangement = Arrangement.spacedBy(u * 14f)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(coverShape)
                                .combinedClickable(
                                    onClick = { navController.navigate(album.route()) },
                                    onLongClick = { onAlbumLongPress(album) },
                                ),
                        ) {
                            AlbumArt(
                                uri = album.artworkUri,
                                contentDescription = album.title,
                                shape = coverShape,
                                modifier = Modifier.fillMaxSize(),
                                targetSizeDp = if (columns == 2) 260.dp else 180.dp,
                                cacheKey = album.id.toString(),
                            )
                            Box(Modifier.fillMaxSize().border(u * 1.5f, Color.White.copy(alpha = 0.1f), coverShape))
                        }
                        Column {
                            Text(
                                text = album.title,
                                style = face.copy(fontSize = com.ikko.aura.ui.components.designSp(38f), lineHeight = com.ikko.aura.ui.components.designSp(46f), fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = album.artist,
                                style = face.copy(fontSize = com.ikko.aura.ui.components.designSp(34f), lineHeight = com.ikko.aura.ui.components.designSp(42f),
                                    fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.78f)),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumListItem(
    album: Album,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(AuraShapes.SquircleMedium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SquircleCard(
            modifier = Modifier.size(50.dp),
            shape = AuraShapes.AlbumCover,
        ) {
            AlbumArt(
                uri = album.artworkUri,
                contentDescription = album.title,
                modifier = Modifier.fillMaxSize(),
                shape = AuraShapes.AlbumCover,
                targetSizeDp = 50.dp,
                cacheKey = album.id.toString(),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = LibraryTypography.AlbumTitle,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CoverText(
                text = if (album.trackCount > 0) {
                    "${album.artist} · ${album.trackCount} titre${if (album.trackCount > 1) "s" else ""}"
                } else {
                    album.artist
                },
                style = LibraryTypography.AlbumArtist,
                alpha = 0.72f,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ArtistList(
    artists: List<Artist>,
    navController: NavHostController,
    onJumped: (Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    IndexedList(
        target = rememberScrollTarget(listState),
        indexKeys = remember(artists) { artists.map { it.name } },
        onJumped = onJumped,
    ) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PAGE_MARGIN, end = INDEX_GUTTER, top = 8.dp, bottom = listBottomPadding() + 12.dp),
    ) {
        items(artists, key = { it.id }) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navController.navigate(AuraDestination.ArtistPattern.route(artist.id.toString())) }
                    .padding(vertical = 14.dp),
            ) {
                Column {
                    Text(artist.name, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextPrimary)
                    Text(
                        "${artist.trackCount} ${if (artist.trackCount > 1) "titres" else "titre"} · " +
                            "${artist.albumCount} ${if (artist.albumCount > 1) "albums" else "album"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraColors.TextSecondary,
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun PlaylistsList(
    playlists: List<PlaylistSummary>,
    onCreate: (String) -> Unit,
    onOpen: (Long) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PAGE_MARGIN, end = PAGE_MARGIN, top = 8.dp, bottom = listBottomPadding() + 12.dp),
    ) {
        item {
            if (creating) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("Nom de la playlist") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AuraColors.TextPrimary,
                            unfocusedTextColor = AuraColors.TextPrimary,
                            focusedBorderColor = AuraColors.TextPrimary,
                            unfocusedBorderColor = AuraColors.GlassBorder,
                        ),
                    )
                    IconButton(onClick = {
                        onCreate(newName)
                        newName = ""
                        creating = false
                    }) {
                        Icon(CupertinoIcons.Add, contentDescription = "Créer", tint = AuraColors.TextPrimary)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { creating = true }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(CupertinoIcons.Add, contentDescription = null, tint = AuraColors.TextPrimary)
                    Text(
                        "Nouvelle playlist",
                        style = MaterialTheme.typography.titleMedium,
                        color = AuraColors.TextPrimary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }

        if (playlists.isEmpty() && !creating) {
            item {
                Text(
                    text = "Aucune playlist pour le moment",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AuraColors.TextSecondary,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }

        items(playlists, key = { it.id }) { playlist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(playlist.id) }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(AuraShapes.SquircleSmall)
                        .background(AuraColors.GlassFill),
                    contentAlignment = Alignment.Center,
                ) {
                    // Favoris, pinned first, is marked with the heart that fills it.
                    if (playlist.isFavorites) {
                        Icon(CupertinoIcons.HeartFilled, contentDescription = null, tint = LocalCoverColors.current.accent)
                    } else {
                        Icon(CupertinoIcons.PlaylistMusic, contentDescription = null, tint = AuraColors.TextPrimary)
                    }
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextPrimary)
                    Text(
                        "${playlist.trackCount} titre${if (playlist.trackCount > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanningState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = AuraColors.TextPrimary)
            Text(
                "Scan de la bibliothèque…",
                style = MaterialTheme.typography.bodyLarge,
                color = AuraColors.TextSecondary,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/**
 * Keeps the index strip clear of the content it scrubs. Everything else keeps its own 16dp.
 */
/** Kept clear at the grid's right for the index strip. */
private val INDEX_GUTTER = 12.dp

/**
 * The names the alphabet strip files this list under, or null when the strip should not be shown
 * at all.
 *
 * Only an alphabetical ordering has letters to jump to: under "date d'ajout" the list is in no
 * relation to the alphabet, and a strip over it would take you somewhere arbitrary and look
 * broken doing it. Under "artiste" it indexes the artist, because that is what the list is
 * ordered by — indexing the title there would be the same lie.
 */
private inline fun <T> List<T>.indexKeysFor(sort: LibrarySort, keys: (T) -> Pair<String, String>): List<String>? =
    when (sort) {
        LibrarySort.NAME -> map { keys(it).first }
        LibrarySort.ARTIST -> map { keys(it).second }
        LibrarySort.DATE_ADDED -> null
    }

/**
 * Wraps a list in what makes a long one navigable: PowerAmp's alphabet strip down the right edge,
 * invisible, which also takes a double tap back to the top.
 *
 * Both read the scroll position in composition, which is what they are for — but only this
 * wrapper does, so a scroll invalidates this one composable and not the list inside it.
 */
@Composable
private fun IndexedList(
    target: ScrollTarget,
    indexKeys: List<String>?,
    /**
     * Called after a jump that did not come from a finger on the list — a letter, or the double tap
     * back to the top. [atTop] says where it landed.
     *
     * The collapsing header is driven by nested scroll, and a programmatic scroll does not
     * produce any: jumping to the Ms left the header fully open above a list scrolled halfway
     * down, and jumping back to the top left it collapsed with nothing above it to collapse for.
     * Both wasted the space the header is supposed to give back.
     */
    onJumped: (atTop: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(modifier = Modifier.fillMaxSize()) {
        content()

        if (indexKeys != null && indexKeys.size >= MIN_ITEMS_FOR_INDEX) {
            val available = remember(indexKeys) { indexKeys.map(::indexLetterOf).toSet() }
            val shift = LocalIndexStripShift.current
            AlphabetIndex(
                available = available,
                onLetterPicked = { letter ->
                    val destination = indexKeys.indexOfFirst { indexLetterOf(it) == letter }
                    if (destination >= 0) {
                        scope.launch { target.jumpTo(destination) }
                        onJumped(destination == 0)
                    }
                },
                // A double tap in the (invisible) strip goes back to the top: it replaced the back-to-top button.
                onDoubleTap = {
                    scope.launch { target.animateToTop() }
                    onJumped(true)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, shift().roundToInt()) },
            )
        }
    }
}

/** Below this a list fits in a couple of flicks and the strip is only in the way. */
private const val MIN_ITEMS_FOR_INDEX = 25



/** The room a list keeps at its end: the player's, plus the tab capsule's while it is out. */
@Composable
private fun listBottomPadding(): androidx.compose.ui.unit.Dp {
    val capsule = LocalTabCapsule.current
    // Whether the list's own scrolling has put the capsule away — and nothing else. It used to be the capsule's animation
    // target, which also moves when the player opens (the capsule is reset under the card) and again when it closes: each
    // time the list's end padding changed and every grid and list of the page, hidden under the player, was measured again —
    // text and all — in the middle of the card's travel.
    val shown = capsule != null && !capsule.scrolledAway
    return LocalPlayerPillHeight.current +
        if (shown) (PageMotionSpec.CAPSULE_HEIGHT_DP + PageMotionSpec.CAPSULE_GAP_TO_PLAYER_DP).dp else 0.dp
}
