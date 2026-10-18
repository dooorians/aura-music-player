package com.ikko.aura.ui.screens.library

import com.ikko.aura.ui.components.tabFade
import com.ikko.aura.ui.components.heroRipple
import com.ikko.aura.ui.components.heroSideLine
import com.ikko.aura.ui.components.heroAnchor
import androidx.compose.foundation.layout.fillMaxHeight
import com.ikko.aura.ui.components.TabMotion
import com.ikko.aura.ui.components.tabStagger
import com.ikko.aura.ui.components.tabTextRise
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
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
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
    val homeViewModel: com.ikko.aura.viewmodel.HomeWidgetsViewModel = hiltViewModel()
    val profileUri by homeViewModel.profileImageUri.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    // The header folds away with the status bar's height included: the page runs under the bar (see HomeScreen),
    // so once folded the list goes on up beneath it and melts into it. At rest the list starts where it did.
    val headerHeight = com.ikko.aura.ui.components.designUnit() * 432f
    val headerHeightPx = with(density) { headerHeight.toPx() }
    var headerOffsetPx by rememberCollapseOffset()
    val sharedHeaderOffset = com.ikko.aura.LocalLibraryHeaderOffsetPx.current
    androidx.compose.runtime.SideEffect {
        sharedHeaderOffset?.floatValue = headerOffsetPx
    }
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
                sharedHeaderOffset?.floatValue = newOffset
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
                sharedHeaderOffset?.floatValue = newOffset
                return Offset(0f, used)
            }
        }
    }

    val scope = rememberCoroutineScope()
    // Shared by the sort button and the menu laid over the page: the menu grows out of the button, and the button
    // empties while it does.
    val sortMenu = rememberGlassMenuState()
    val reduceMotion = LocalPageMotion.current?.reduceMotion == true
    val tracksGridState = rememberLazyGridState()
    val albumsGridState = rememberLazyGridState()
    val artistsListState = rememberLazyListState()
    val playlistsListState = rememberLazyListState()
    // A new view starts at its top, so its header is back, and the capsule with it.
    // Only on a real change of filter, though: this also runs when the page comes back into composition — back
    // from an album opened further down — and it used to reopen the header there over a list still scrolled
    // where it had been left. The filter last seen is saved with the page, so coming back is told apart.
    var lastFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<LibraryFilter?>(null) }
    androidx.compose.runtime.LaunchedEffect(uiState.filter) {
        if (lastFilter != null && lastFilter != uiState.filter) {
            headerOffsetPx = 0f
            sharedHeaderOffset?.floatValue = 0f
        }
        lastFilter = uiState.filter
    }

    /**
     * Puts the header where the new scroll position says it should be, after a jump the nested
     * scroll never saw. At the top the header belongs open; anywhere else it belongs out of the
     * way, which is the whole reason the strip and the back-to-top button exist.
     */
    fun settleHeaderAfterJump(atTop: Boolean) {
        val next = if (atTop) 0f else -headerHeightPx
        headerOffsetPx = next
        sharedHeaderOffset?.floatValue = next
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
                    profileUri = profileUri,
                    sort = uiState.sort,
                    onProfile = { navController.navigate(AuraDestination.Settings.route) },
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
                .nestedScroll(headerScrollConnection),
        ) {
            // The four filters are peers, exactly like the top tabs, so they animate with the
            // same vocabulary — switching them used to swap content instantly with nothing at all.
            val scanning = uiState.isScanning && uiState.tracks.isEmpty()
            val filterDir = remember { intArrayOf(chipIndex(uiState.filter), 1) }
            chipIndex(uiState.filter).let { if (filterDir[0] != it) { filterDir[1] = if (it > filterDir[0]) 1 else -1; filterDir[0] = it } }
            AnimatedContent(
                targetState = if (scanning) null else uiState.filter,
                transitionSpec = run {
                    val tabs = com.ikko.aura.ui.components.tabPanelTransition<LibraryFilter?>(reduceMotion) { from, to ->
                        if (from == null || to == null || chipIndex(to) >= chipIndex(from)) 1 else -1
                    }
                    val scan: AnimatedContentTransitionScope<LibraryFilter?>.() -> ContentTransform = {
                        if (initialState == null || targetState == null) {
                            AuraMotion.peerEnter togetherWith AuraMotion.peerExit
                        } else {
                            tabs()
                        }
                    }
                    scan
                },
                label = "library-filter",
            ) { filter ->
                val changing = transition.currentState == androidx.compose.animation.EnterExitState.PreEnter
                com.ikko.aura.ui.components.ProvideTabEntry(changing, filterDir[1]) {
                com.ikko.aura.ui.components.EndTabEntryWhenScrolled(
                    when (filter) {
                        LibraryFilter.TRACKS -> tracksGridState.isScrollInProgress
                        LibraryFilter.ALBUMS -> albumsGridState.isScrollInProgress
                        LibraryFilter.ARTISTS -> artistsListState.isScrollInProgress
                        LibraryFilter.PLAYLISTS -> playlistsListState.isScrollInProgress
                        null -> false
                    },
                )
                when (filter) {
                    null -> ScanningState()
                    LibraryFilter.TRACKS -> TrackList(
                        tracks = uiState.tracks,
                        sort = uiState.sort,
                        layoutMode = uiState.trackLayoutMode,
                        gridState = tracksGridState,
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
                        gridState = albumsGridState,
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
                        listState = artistsListState,
                        navController = navController,
                        onJumped = ::settleHeaderAfterJump,
                    )
                    LibraryFilter.PLAYLISTS -> PlaylistsList(
                        playlists = playlists,
                        listState = playlistsListState,
                        onCreate = playlistsViewModel::createPlaylist,
                        onOpen = { id -> navController.navigate(AuraDestination.PlaylistPattern.route(id.toString())) },
                    )
                }
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
private val LIBRARY_CHIPS = listOf(
    LibraryFilter.TRACKS to "Titres",
    LibraryFilter.ALBUMS to "Albums",
    LibraryFilter.ARTISTS to "Artistes",
    LibraryFilter.PLAYLISTS to "Playlists",
)
private fun chipIndex(filter: LibraryFilter): Int = CHIP_ORDER.indexOf(filter).coerceAtLeast(0)

private fun countLabel(count: Int, noun: String): String {
    val number = java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE).format(count)
    return "$number $noun" + if (count > 1) "s" else ""
}

private val LibraryTabActiveStyle = androidx.compose.ui.text.TextStyle(
    fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 15.5.sp,
    lineHeight = 20.sp,
)

private val LibraryTabInactiveStyle = androidx.compose.ui.text.TextStyle(
    fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.5.sp,
    lineHeight = 20.sp,
)

@Composable
private fun LibraryHeader(
    @Suppress("UNUSED_PARAMETER") statusBarTop: androidx.compose.ui.unit.Dp,
    countText: String,
    sortMenu: GlassMenuState,
    onSmartShuffle: () -> Unit,
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    onSearch: () -> Unit,
    profileUri: String?,
    sort: LibrarySort,
    onProfile: () -> Unit,
) {
    val u = com.ikko.aura.ui.components.designUnit()
    val muted = Color(0xFF7F7F89)
    Box(Modifier.fillMaxWidth().height(u * 432f).heroRipple(fast = true)) {
        // Onglets 40/48, 44 px entre eux; le gris devient blanc en 220 ms.
        Row(
            modifier = Modifier.padding(start = u * 40f, top = u * com.ikko.aura.ui.components.PageHeader.TABS_TOP)
                .height(u * com.ikko.aura.ui.components.PageHeader.TABS_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(u * com.ikko.aura.ui.components.PageHeader.TAB_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LIBRARY_CHIPS.forEach { (f, label) ->
                val color by androidx.compose.animation.animateColorAsState(
                    if (f == filter) Color.White else muted, tween(220), label = "library-tab-color",
                )
                Text(
                    text = label,
                    color = color,
                    fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = com.ikko.aura.ui.components.designSp(com.ikko.aura.ui.components.PageHeader.TAB_TEXT),
                    lineHeight = com.ikko.aura.ui.components.designSp(com.ikko.aura.ui.components.PageHeader.TAB_LINE),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { onFilter(f) },
                )
            }
        }

        // Aléatoire et Trier: 104 px, glyphe seul avec une ombre, collés à 16 px du bord.
        Row(Modifier.align(Alignment.TopEnd).padding(end = u * 12f, top = u * (com.ikko.aura.ui.components.PageHeader.TABS_TOP + (com.ikko.aura.ui.components.PageHeader.TABS_HEIGHT - com.ikko.aura.ui.components.PageHeader.ACTION) / 2f))) {
            Box(
                Modifier.size(u * com.ikko.aura.ui.components.PageHeader.ACTION).clip(CircleShape).clickable(onClick = onSmartShuffle),
                contentAlignment = Alignment.Center,
            ) {
                com.ikko.aura.ui.components.ShadowedGlyph(CupertinoIcons.Shuffle, "Lecture aléatoire", size = u * com.ikko.aura.ui.components.PageHeader.ACTION_GLYPH)
            }
            Box(
                Modifier.size(u * com.ikko.aura.ui.components.PageHeader.ACTION).clip(CircleShape).glassMenuAnchor(sortMenu).clickable(onClick = sortMenu::open),
                contentAlignment = Alignment.Center,
            ) {
                com.ikko.aura.ui.components.ShadowedGlyph(CupertinoIcons.Sort, "Trier", size = u * com.ikko.aura.ui.components.PageHeader.ACTION_GLYPH)
            }
        }

        // Le sélecteur: « 423 titres · Nom A–Z ».
        val sortLabel = when (sort) {
            LibrarySort.NAME -> "Nom A–Z"
            LibrarySort.ARTIST -> "Artiste A–Z"
            LibrarySort.DATE_ADDED -> "Date d'ajout"
        }
        // « 423 titres · Nom A–Z »: the count changes with the tab and the sort.
        val unitPx = with(androidx.compose.ui.platform.LocalDensity.current) { u.toPx() }
        androidx.compose.animation.AnimatedContent(
            targetState = countText to sortLabel,
            modifier = Modifier.padding(start = u * 40f, top = u * com.ikko.aura.ui.components.PageHeader.COUNT_TOP).height(u * 44f),
            transitionSpec = {
                // The board's: the old count leaves upwards in 120 ms, then the new one comes from below in 260.
                val outPx = (8f * unitPx).toInt()
                val inPx = (10f * unitPx).toInt()
                (androidx.compose.animation.fadeIn(tween(260, delayMillis = 120, easing = com.ikko.aura.ui.components.TabMotion.Enter)) +
                    androidx.compose.animation.slideInVertically(tween(260, delayMillis = 120, easing = com.ikko.aura.ui.components.TabMotion.Enter)) { inPx }) togetherWith
                    (androidx.compose.animation.fadeOut(tween(120, easing = com.ikko.aura.ui.components.TabMotion.Exit)) +
                        androidx.compose.animation.slideOutVertically(tween(120, easing = com.ikko.aura.ui.components.TabMotion.Exit)) { -outPx })
            },
            label = "library-count",
        ) { (count, sortName) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(u * 12f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val st = androidx.compose.ui.text.TextStyle(
                    fontFamily = com.ikko.aura.ui.theme.AuraFontFamily, fontWeight = FontWeight.Bold,
                    fontSize = com.ikko.aura.ui.components.designSp(32f), lineHeight = com.ikko.aura.ui.components.designSp(40f),
                )
                Text(count, style = st, color = muted, maxLines = 1)
                Text("·", style = st, color = muted)
                Text(sortName, style = st, color = Color(0xFFB9B9C2), maxLines = 1)
            }
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    sort: LibrarySort,
    layoutMode: TrackLayoutMode,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onTrackClick: (Int) -> Unit,
    onTrackLongPress: (Track) -> Unit,
    onJumped: (Boolean) -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val unusedLayout = layoutMode
    val u = com.ikko.aura.ui.components.designUnit()
    val columns = 2
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
            start = u * 40f, end = u * 40f, top = 0.dp, bottom = listBottomPadding() + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(u * 32f),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            gridItemsIndexed(tracks, key = { _, track -> track.id }, contentType = { _, _ -> "track" }) { index, track ->
                Box(Modifier.tabStagger(index, TabMotion.ROW_STEP_MS, TabMotion.ROW_MS)) {
                LibraryTrackRow(
                    track = track,
                    onClick = { onTrackClick(index) },
                    onLongClick = { onTrackLongPress(track) },
                )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTrackRow(track: Track, onClick: () -> Unit, onLongClick: () -> Unit) {
    val u = com.ikko.aura.ui.components.designUnit()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(u * 148f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u * 22f),
    ) {
        com.ikko.aura.ui.components.AlbumArt(
            uri = track.artworkUri,
            contentDescription = track.album,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(u * 28f),
            modifier = Modifier.size(u * 124f),
            targetSizeDp = u * 124f,
            cacheKey = track.albumId.toString(),
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title, color = Color.White, fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                fontWeight = FontWeight.ExtraBold, fontSize = com.ikko.aura.ui.components.designSp(40f),
                lineHeight = com.ikko.aura.ui.components.designSp(48f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist, color = Color(0xFF8B8B95), fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                fontWeight = FontWeight.SemiBold, fontSize = com.ikko.aura.ui.components.designSp(34f),
                lineHeight = com.ikko.aura.ui.components.designSp(42f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<Album>,
    sort: LibrarySort,
    layoutMode: AlbumLayoutMode,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    navController: NavHostController,
    onAlbumLongPress: (Album) -> Unit,
    onJumped: (Boolean) -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val unusedAlbumLayout = layoutMode
    val isList = false
    val columns = 3
    val u = com.ikko.aura.ui.components.designUnit()
    val coverShape = remember(u) { androidx.compose.foundation.shape.RoundedCornerShape(u * 30f) }
    val titleFontSize = com.ikko.aura.ui.components.designSp(38f)
    val titleLineHeight = com.ikko.aura.ui.components.designSp(46f)
    val artistFontSize = com.ikko.aura.ui.components.designSp(34f)
    val artistLineHeight = com.ikko.aura.ui.components.designSp(42f)
    val titleStyle = remember(titleFontSize, titleLineHeight) {
        androidx.compose.ui.text.TextStyle(
            fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
            color = Color.White,
            fontSize = titleFontSize,
            lineHeight = titleLineHeight,
            fontWeight = FontWeight.ExtraBold,
        )
    }
    val artistStyle = remember(artistFontSize, artistLineHeight) {
        androidx.compose.ui.text.TextStyle(
            fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
            color = Color(0xFF8B8B95),
            fontSize = artistFontSize,
            lineHeight = artistLineHeight,
            fontWeight = FontWeight.SemiBold,
        )
    }
    val rimColor = Color.White.copy(alpha = 0.1f)
    val targetArtDp = if (columns == 2) 260.dp else 180.dp
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
                start = u * 40f,
                end = u * 40f,
                top = 0.dp,
                bottom = listBottomPadding() + 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(u * 26f),
            verticalArrangement = Arrangement.spacedBy(u * 32f),
        ) {
            itemsIndexed(albums, key = { _, album -> album.id }, contentType = { _, _ -> if (isList) "album_row" else "album_cell" }) { albumIndex, album ->
                Box(Modifier.tabStagger(albumIndex, TabMotion.ROW_STEP_MS, TabMotion.ROW_MS)) {
                if (isList) {
                    AlbumListItem(
                        album = album,
                        onClick = { navController.navigate(album.route()) },
                        onLongClick = { onAlbumLongPress(album) },
                    )
                } else {
                    // The "F" grid: square covers with 32 px corners and a hairline rim, then the title and the artist, in design px.
                    val hero = com.ikko.aura.ui.components.LocalHero.current
                    val heroKey = "album:${album.id}"
                    val coverAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
                    val titleAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
                    val subAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
                    val tileDensity = androidx.compose.ui.platform.LocalDensity.current
                    Column(
                        modifier = Modifier.heroRipple(heroKey),
                        verticalArrangement = Arrangement.spacedBy(u * 14f),
                    ) { // 316 px cover, 14 px under it
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .heroAnchor(heroKey, coverAnchor)
                                .clip(coverShape)
                                .combinedClickable(
                                    onClick = {
                                        if (hero.begin(
                                            key = heroKey,
                                            uri = album.artworkUri,
                                            cacheKey = album.id.toString(),
                                            cover = coverAnchor,
                                            radiusPx = with(tileDensity) { (u * 30f).toPx() },
                                            title = titleAnchor to com.ikko.aura.ui.components.HeroText(album.title, androidx.compose.ui.geometry.Rect.Zero, titleStyle, Color.White),
                                            sub = subAnchor to com.ikko.aura.ui.components.HeroText(album.artist, androidx.compose.ui.geometry.Rect.Zero, artistStyle, Color(0xFF8B8B95)),
                                            sizeDp = targetArtDp,
                                        )) navController.navigate(album.route())
                                    },
                                    onLongClick = { onAlbumLongPress(album) },
                                ),
                        ) {
                            AlbumArt(
                                uri = album.artworkUri,
                                contentDescription = album.title,
                                shape = coverShape,
                                modifier = Modifier.fillMaxSize(),
                                targetSizeDp = targetArtDp,
                                cacheKey = album.id.toString(),
                            )
                            Box(Modifier.fillMaxSize().border(u * 1.5f, rimColor, coverShape))
                        }
                        Column {
                            Text(
                                text = album.title,
                                style = titleStyle,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.heroAnchor(heroKey, titleAnchor, com.ikko.aura.ui.components.HeroState.ROLE_TITLE),
                            )
                            Text(
                                text = album.artist,
                                style = artistStyle,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.heroAnchor(heroKey, subAnchor, com.ikko.aura.ui.components.HeroState.ROLE_SUB),
                            )
                        }
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
    listState: androidx.compose.foundation.lazy.LazyListState,
    navController: NavHostController,
    onJumped: (Boolean) -> Unit,
) {
    val u = com.ikko.aura.ui.components.designUnit()
    val rows = remember(artists) { artists.chunked(2) }
    IndexedList(
        target = rememberScrollTarget(listState),
        indexKeys = remember(rows) { rows.map { it.first().name } },
        onJumped = onJumped,
    ) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = u * 40f, end = u * 40f, top = 0.dp, bottom = listBottomPadding() + 12.dp),
    ) {
        itemsIndexed(rows, key = { _, r -> r.first().id }) { rowIndex, pair ->
            Row(
                modifier = Modifier.tabStagger(rowIndex, TabMotion.ROW_STEP_MS, TabMotion.ROW_MS).fillMaxWidth().height(u * 148f),
                horizontalArrangement = Arrangement.spacedBy(u * 32f),
            ) {
                pair.forEach { artist ->
                    val hero = com.ikko.aura.ui.components.LocalHero.current
                    val heroKey = "artist:${artist.id}"
                    val coverAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
                    val titleAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
                    val rowDensity = androidx.compose.ui.platform.LocalDensity.current
                    val nameStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = com.ikko.aura.ui.theme.AuraFontFamily, fontWeight = FontWeight.ExtraBold,
                        fontSize = com.ikko.aura.ui.components.designSp(40f), lineHeight = com.ikko.aura.ui.components.designSp(48f),
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heroRipple(heroKey)
                            .clickable {
                                if (hero.begin(
                                    key = heroKey,
                                    uri = null,
                                    cacheKey = null,
                                    cover = coverAnchor,
                                    radiusPx = with(rowDensity) { (u * 62f).toPx() },
                                    title = titleAnchor to com.ikko.aura.ui.components.HeroText(artist.name, androidx.compose.ui.geometry.Rect.Zero, nameStyle, Color.White),
                                    sub = null,
                                    drawCover = false,
                                )) navController.navigate(AuraDestination.ArtistPattern.route(artist.id.toString()))
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(u * 22f),
                    ) {
                        Box(
                            Modifier.size(u * 124f).heroAnchor(heroKey, coverAnchor).clip(CircleShape).background(Color(0xFF1F1F27)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                artist.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
                                color = Color(0xFFC4C4CD), fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                                fontWeight = FontWeight.ExtraBold, fontSize = com.ikko.aura.ui.components.designSp(44f),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                artist.name, color = Color.White, fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                                fontWeight = FontWeight.ExtraBold, fontSize = com.ikko.aura.ui.components.designSp(40f),
                                lineHeight = com.ikko.aura.ui.components.designSp(48f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.heroAnchor(heroKey, titleAnchor, com.ikko.aura.ui.components.HeroState.ROLE_TITLE),
                            )
                            Text(
                                "${artist.trackCount} ${if (artist.trackCount > 1) "titres" else "titre"} · " +
                                    "${artist.albumCount} ${if (artist.albumCount > 1) "albums" else "album"}",
                                color = Color(0xFF8B8B95), fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                                fontWeight = FontWeight.SemiBold, fontSize = com.ikko.aura.ui.components.designSp(34f),
                                lineHeight = com.ikko.aura.ui.components.designSp(42f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.heroSideLine(heroKey),
                            )
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    }
}

@Composable
private fun PlaylistsList(
    playlists: List<PlaylistSummary>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onCreate: (String) -> Unit,
    onOpen: (Long) -> Unit,
) {
    val u = com.ikko.aura.ui.components.designUnit()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val rows = remember(playlists) { playlists.chunked(3) }
    val accent = Color(0xFFA9DCEF)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = u * 40f, end = u * 40f, top = 0.dp, bottom = listBottomPadding() + 12.dp),
        verticalArrangement = Arrangement.spacedBy(u * 32f),
    ) {
        item {
            if (creating) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("Nom de la playlist") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AuraColors.TextPrimary,
                            unfocusedTextColor = AuraColors.TextPrimary,
                            focusedBorderColor = accent,
                            unfocusedBorderColor = AuraColors.GlassBorder,
                        ),
                    )
                    IconButton(onClick = {
                        onCreate(newName)
                        newName = ""
                        creating = false
                    }) { Icon(CupertinoIcons.Add, contentDescription = "Créer", tint = accent) }
                }
            } else {
                Row(Modifier.fillMaxWidth().tabFade(200, 260), horizontalArrangement = Arrangement.End) {
                    Row(
                        modifier = Modifier
                            .height(u * 64f)
                            .clip(CircleShape)
                            .clickable { creating = true }
                            .padding(horizontal = u * 12f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(u * 12f),
                    ) {
                        Icon(CupertinoIcons.Add, null, tint = accent, modifier = Modifier.size(u * 34f))
                        Text(
                            "Nouvelle playlist", color = accent, fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                            fontWeight = FontWeight.ExtraBold, fontSize = com.ikko.aura.ui.components.designSp(34f),
                            lineHeight = com.ikko.aura.ui.components.designSp(42f), maxLines = 1, softWrap = false,
                        )
                    }
                }
            }
        }

        if (playlists.isEmpty() && !creating) {
            item {
                Text(
                    text = "Aucune playlist pour le moment",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AuraColors.TextSecondary,
                )
            }
        }

        itemsIndexed(rows, key = { _, r -> r.first().id }) { rowIndex, trio ->
            Row(
                modifier = Modifier.tabStagger(rowIndex, TabMotion.ROW_STEP_MS, TabMotion.ROW_MS).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(u * 26f),
            ) {
                trio.forEach { playlist ->
                    val hero = com.ikko.aura.ui.components.LocalHero.current
                    val heroKey = "playlist:${playlist.id}"
                    val coverAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
                    val titleAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
                    val subAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
                    val tileDensity = androidx.compose.ui.platform.LocalDensity.current
                    val titleStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = com.ikko.aura.ui.theme.AuraFontFamily, fontWeight = FontWeight.ExtraBold,
                        fontSize = com.ikko.aura.ui.components.designSp(38f), lineHeight = com.ikko.aura.ui.components.designSp(46f),
                    )
                    val subStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = com.ikko.aura.ui.theme.AuraFontFamily, fontWeight = FontWeight.SemiBold,
                        fontSize = com.ikko.aura.ui.components.designSp(34f), lineHeight = com.ikko.aura.ui.components.designSp(42f),
                    )
                    val countText = "${playlist.trackCount} titre${if (playlist.trackCount > 1) "s" else ""}"
                    Column(
                        Modifier
                            .weight(1f)
                            .heroRipple(heroKey)
                            .clickable {
                                if (hero.begin(
                                    key = heroKey,
                                    uri = null,
                                    cacheKey = null,
                                    cover = coverAnchor,
                                    radiusPx = with(tileDensity) { (u * 30f).toPx() },
                                    title = titleAnchor to com.ikko.aura.ui.components.HeroText(playlist.name, androidx.compose.ui.geometry.Rect.Zero, titleStyle, Color.White),
                                    sub = subAnchor to com.ikko.aura.ui.components.HeroText(countText, androidx.compose.ui.geometry.Rect.Zero, subStyle, Color(0xFF8B8B95)),
                                    drawCover = false,
                                )) onOpen(playlist.id)
                            },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .heroAnchor(heroKey, coverAnchor)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(u * 30f))
                                .background(if (playlist.isFavorites) Color(0xFF2A1520) else Color(0xFF17171D)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (playlist.isFavorites) {
                                Icon(CupertinoIcons.HeartFilled, null, tint = Color(0xFFF39AB4), modifier = Modifier.size(u * 110f))
                            } else {
                                Icon(CupertinoIcons.PlaylistMusic, null, tint = Color(0xFF8B8B95), modifier = Modifier.size(u * 96f))
                            }
                        }
                        Text(
                            playlist.name, color = Color.White, fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                            fontWeight = FontWeight.ExtraBold, fontSize = com.ikko.aura.ui.components.designSp(38f),
                            lineHeight = com.ikko.aura.ui.components.designSp(46f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = u * 14f)
                                .heroAnchor(heroKey, titleAnchor, com.ikko.aura.ui.components.HeroState.ROLE_TITLE),
                        )
                        Text(
                            countText,
                            color = Color(0xFF8B8B95), fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                            fontWeight = FontWeight.SemiBold, fontSize = com.ikko.aura.ui.components.designSp(34f),
                            lineHeight = com.ikko.aura.ui.components.designSp(42f), maxLines = 1,
                            modifier = Modifier.heroAnchor(heroKey, subAnchor, com.ikko.aura.ui.components.HeroState.ROLE_SUB),
                        )
                    }
                }
                repeat(3 - trio.size) { Spacer(Modifier.weight(1f)) }
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



/** The room a list keeps at its end: the player's. */
@Composable
private fun listBottomPadding(): androidx.compose.ui.unit.Dp = LocalPlayerPillHeight.current
