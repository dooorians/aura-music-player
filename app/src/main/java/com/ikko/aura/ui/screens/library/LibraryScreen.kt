package com.ikko.aura.ui.screens.library

import com.ikko.aura.ui.screens.home.underHeaderScrim
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import androidx.compose.ui.input.pointer.pointerInput
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
    val customArtworks by playlistsViewModel.customArtworks.collectAsStateWithLifecycle()
    val tracksById = remember(uiState.tracks) { uiState.tracks.associateBy { it.id } }
    val homeViewModel: com.ikko.aura.viewmodel.HomeWidgetsViewModel = hiltViewModel()
    val profileUri by homeViewModel.profileImageUri.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    // The header folds away with the status bar's height included: the page runs under the bar (see HomeScreen),
    // so once folded the list goes on up beneath it and melts into it. At rest the list starts where it did.
    val headerHeight = com.ikko.aura.ui.components.designUnit() * 236f
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
    val accent = com.ikko.aura.ui.components.rememberPlayerAccent()

    // Header visibility on scroll for the 4 sub-menus:
    // Disappears on scroll down, reappears even on very slight scroll up.
    val subMenuHeaderVisible = remember { androidx.compose.animation.core.Animatable(1f) }
    var subMenuScrollAccumulator by remember { mutableFloatStateOf(0f) }
    val densityFactor = density.density
    val subMenuScrollConnection = remember(densityFactor) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy < -4f) {
                    // Scrolling down (finger moves up, list moves down) -> hide header
                    if (subMenuScrollAccumulator > 0f) subMenuScrollAccumulator = 0f
                    subMenuScrollAccumulator += dy
                    if (subMenuScrollAccumulator < -10f * densityFactor && subMenuHeaderVisible.targetValue > 0f) {
                        scope.launch {
                            subMenuHeaderVisible.animateTo(
                                0f,
                                androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 400f),
                            )
                        }
                    }
                } else if (dy > 2f) {
                    // Scrolling up (finger moves down, scrolling back to top) -> instantly reappear on very slight scroll
                    if (subMenuScrollAccumulator < 0f) subMenuScrollAccumulator = 0f
                    subMenuScrollAccumulator += dy
                    if (subMenuScrollAccumulator > 4f * densityFactor && subMenuHeaderVisible.targetValue < 1f) {
                        scope.launch {
                            subMenuHeaderVisible.animateTo(
                                1f,
                                androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 400f),
                            )
                        }
                    }
                }
                return Offset.Zero
            }
        }
    }

    // The library is a menu; its four pages (Titres, Albums, Artistes, Playlists) grow out of the pastille they open
    // from. `shown` is the page that is up (kept with the screen, so coming back from an album finds it where it was),
    // `clip` how far open it is: 0 at the pastille, 1 as a whole page.
    var shown by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val page = shown?.let { name -> LibraryFilter.values().firstOrNull { it.name == name } }
    val clip = remember { androidx.compose.animation.core.Animatable(if (page != null) 1f else 0f) }
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var closing by remember { mutableStateOf(false) }
    val containerOrigin = remember { arrayOf(Offset.Zero) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var composingWithAi by remember { mutableStateOf(false) }
    fun ms(v: Int) = if (reduceMotion) (v * 0.2f).toInt().coerceAtLeast(1) else v

    // Where each row of the menu is, for a page asked for from elsewhere (Home's "Tout voir") to open from its row.
    val rowBounds = remember { HashMap<LibraryFilter, androidx.compose.ui.geometry.Rect>() }
    // Each page is opened from its top: what was scrolled before is forgotten, as it would be for any new screen.
    fun resetScrolls() {
        tracksGridState.requestScrollToItem(0)
        albumsGridState.requestScrollToItem(0)
        artistsListState.requestScrollToItem(0)
        playlistsListState.requestScrollToItem(0)
    }
    fun openPage(filter: LibraryFilter, bounds: androidx.compose.ui.geometry.Rect) {
        if (shown != null) return
        resetScrolls()
        libraryViewModel.setFilter(filter)
        origin = bounds.translate(-containerOrigin[0])
        headerOffsetPx = 0f
        sharedHeaderOffset?.floatValue = 0f
        closing = false
        shown = filter.name
        scope.launch { subMenuHeaderVisible.snapTo(1f) }
        subMenuScrollAccumulator = 0f
    }
    androidx.compose.runtime.LaunchedEffect(shown) {
        if (shown != null && clip.value < 1f) {
            clip.animateTo(1f, tween(ms(PUSH_MS), easing = PushEasing))
        }
    }
    val openRequest by libraryViewModel.openRequest.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(openRequest) {
        val wanted = openRequest ?: return@LaunchedEffect
        libraryViewModel.clearOpenRequest()
        if (shown == null) openPage(wanted, rowBounds[wanted] ?: androidx.compose.ui.geometry.Rect.Zero)
    }
    fun closePage() {
        if (shown == null || closing) return
        closing = true
        // The page leaves as it is (scrolled, its header folded): what is put back to the top is put back once it is gone.
        // Except the common header's fold (shared with the page's), which it has no reason to keep: the header is on its way
        // back as the page goes, and must not wait for the end of it, slid up and faded, to appear.
        sharedHeaderOffset?.floatValue = 0f
        scope.launch {
            clip.animateTo(0f, tween(ms(PUSH_MS), easing = PushEasing))
            shown = null
            headerOffsetPx = 0f
            sharedHeaderOffset?.floatValue = 0f
            resetScrolls()
            closing = false
            creatingPlaylist = false
        }
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
    // The player opened over the page owns the back; a page of the library is closed by it otherwise.
    androidx.activity.compose.BackHandler(enabled = shown != null && com.ikko.aura.LocalSheetCard.current.owner == null) {
        closePage()
    }
    // The common header (search, profile) gives way to the page's own while it is up.
    val pageProgress = com.ikko.aura.LocalLibraryPageProgress.current
    androidx.compose.runtime.LaunchedEffect(clip) {
        androidx.compose.runtime.snapshotFlow { clip.value }.collect { pageProgress?.floatValue = it }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { pageProgress?.floatValue = 0f } }

    val counts = remember(uiState.tracks.size, uiState.albums.size, uiState.artists.size, playlists.size) {
        listOf(
            LibraryFilter.TRACKS to uiState.tracks.size,
            LibraryFilter.ALBUMS to uiState.albums.size,
            LibraryFilter.ARTISTS to uiState.artists.size,
            LibraryFilter.PLAYLISTS to playlists.size,
        )
    }
    val nowPlayingId by remember(playerViewModel) {
        playerViewModel.uiState.map { it.currentTrack?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

    MediaContextHost(
        navController = navController,
        playerViewModel = playerViewModel,
        onLibraryChanged = libraryViewModel::rescan,
    ) { context ->
    val stripTopPx = with(density) { (statusBarTop + STRIP_RELATIVE_TOP).toPx() }
    val listTopBasePx = headerHeightPx
    androidx.compose.runtime.CompositionLocalProvider(
        // The strip holds still on the screen while the header above the list folds.
        LocalIndexStripShift provides { stripTopPx - (listTopBasePx + headerOffsetPx) },
    ) {
    Box(Modifier.fillMaxSize()) {
    GradientScreenScaffold(
        applyStatusBarInset = false,
    ) {
        val rootScrollState = androidx.compose.foundation.rememberScrollState()
        val sharedLibScroll = com.ikko.aura.LocalLibraryScrollPx.current
        androidx.compose.runtime.LaunchedEffect(page, rootScrollState, tracksGridState, albumsGridState, artistsListState, playlistsListState) {
            androidx.compose.runtime.snapshotFlow {
                if (page == null) {
                    rootScrollState.value.toFloat()
                } else {
                    when (page) {
                        LibraryFilter.TRACKS -> if (tracksGridState.firstVisibleItemIndex > 0) 1000f else tracksGridState.firstVisibleItemScrollOffset.toFloat()
                        LibraryFilter.ALBUMS -> if (albumsGridState.firstVisibleItemIndex > 0) 1000f else albumsGridState.firstVisibleItemScrollOffset.toFloat()
                        LibraryFilter.ARTISTS -> if (artistsListState.firstVisibleItemIndex > 0) 1000f else artistsListState.firstVisibleItemScrollOffset.toFloat()
                        LibraryFilter.PLAYLISTS -> if (playlistsListState.firstVisibleItemIndex > 0) 1000f else playlistsListState.firstVisibleItemScrollOffset.toFloat()
                    }
                }
            }.collect { px ->
                sharedLibScroll?.floatValue = px
            }
        }
        val scanning = uiState.isScanning && uiState.tracks.isEmpty()
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { containerOrigin[0] = it.positionInRoot() },
        ) {
            // The menu stays composed under the page (taken out it would have to be rebuilt as the page leaves, one frame
            // late). Material's shared axis: it goes 30 dp to the left as the page comes in, and fades back - not all the way:
            // it stays in sight under the page.
            run {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val c = clip.value
                            translationX = -AXIS_SHIFT.toPx() * c
                            alpha = 1f - (1f - AXIS_MENU_REST) * c
                        },
                ) {
                    if (scanning) {
                        ScanningState()
                    } else {
                        LibraryRoot(
                            counts = counts,
                            recent = uiState.recentlyAdded,
                            navController = navController,
                            onAlbumLongPress = { album ->
                                context.show(LibraryContextTarget.AlbumTarget(album, uiState.tracks.filter { it.albumId == album.id }))
                            },
                            onOpen = ::openPage,
                            rowBounds = rowBounds,
                            scrollState = rootScrollState,
                        )
                    }
                }
            }

            if (page != null) {
                // A page of the library is the other end of a shared axis (Material): it comes in from the right, 30 dp, fading
                // in once the menu has begun to leave, over its own ground (the same mesh); and goes back out to the right.
                // [clip] is how far in it is, 0 gone, 1 in place.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val c = clip.value
                            translationX = (1f - c) * AXIS_SHIFT.toPx()
                            alpha = c
                        },
                ) {
                    com.ikko.aura.ui.components.EctoMesh(com.ikko.aura.ui.components.LocalEctoPalette.current, drifting = false)
                    Box(Modifier.fillMaxSize()) {
                    LibraryPageArrival {
                        val pageCount = when (page) {
                            LibraryFilter.PLAYLISTS -> playlists.size
                            LibraryFilter.ALBUMS -> uiState.albums.size
                            LibraryFilter.ARTISTS -> uiState.artists.size
                            LibraryFilter.TRACKS -> uiState.tracks.size
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(subMenuScrollConnection),
                        ) {
                            com.ikko.aura.ui.components.EndTabEntryWhenScrolled(
                                when (page) {
                                    LibraryFilter.TRACKS -> tracksGridState.isScrollInProgress
                                    LibraryFilter.ALBUMS -> albumsGridState.isScrollInProgress
                                    LibraryFilter.ARTISTS -> artistsListState.isScrollInProgress
                                    LibraryFilter.PLAYLISTS -> playlistsListState.isScrollInProgress
                                },
                            )
                            when (page) {
                                LibraryFilter.TRACKS -> TrackList(
                                    tracks = uiState.tracks,
                                    sort = uiState.sort,
                                    layoutMode = uiState.trackLayoutMode,
                                    gridState = tracksGridState,
                                    nowPlayingId = nowPlayingId,
                                    accent = accent,
                                    onTrackClick = { index -> playerViewModel.playQueue(uiState.tracks, index) },
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
                                    customArtworks = customArtworks,
                                    tracksById = tracksById,
                                    listState = playlistsListState,
                                    creating = false,
                                    onCreate = { name -> playlistsViewModel.createPlaylist(name); creatingPlaylist = false },
                                    onCancelCreate = { creatingPlaylist = false },
                                    onOpen = { id -> navController.navigate(AuraDestination.PlaylistPattern.route(id.toString())) },
                                )
                            }
                        }

                        LibraryPageHeader(
                            filter = page,
                            count = pageCount,
                            accent = accent,
                            sort = uiState.sort,
                            sortMenu = sortMenu,
                            onBack = ::closePage,
                            onSearch = LocalSearchOpener.current,
                            onShuffle = {
                                scope.launch {
                                    val queue = libraryViewModel.smartShuffle()
                                    if (queue.isNotEmpty()) playerViewModel.playQueue(queue, 0, shuffle = true, alreadyShuffled = true)
                                }
                            },
                            onCreate = { creatingPlaylist = true },
                            onAi = { composingWithAi = true },
                            visibleProgress = subMenuHeaderVisible.value,
                        )
                    }
                    }
                }
            }
        }
    }
    if (composingWithAi) {
        com.ikko.aura.ui.playlist.ai.AiPlaylistSheet(
            playerViewModel = playerViewModel,
            onDismiss = { composingWithAi = false },
            onOpenPlaylist = { id -> navController.navigate(AuraDestination.PlaylistPattern.route(id.toString())) },
        )
    }
    if (creatingPlaylist) {
        com.ikko.aura.ui.components.CreatePlaylistDialog(
            onDismiss = { creatingPlaylist = false },
            onCreate = { name, artworkUri ->
                playlistsViewModel.createPlaylist(name, artworkUri)
                creatingPlaylist = false
            },
        )
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
    LibraryFilter.TRACKS to "Morceaux",
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
private fun TrackList(
    tracks: List<Track>,
    sort: LibrarySort,
    layoutMode: TrackLayoutMode,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    nowPlayingId: String?,
    accent: Color,
    onTrackClick: (Int) -> Unit,
    onTrackLongPress: (Track) -> Unit,
    onJumped: (Boolean) -> Unit,
) {
    val u = com.ikko.aura.ui.components.designUnit()
    // The setting of the Réglages: a list (one column of rows) or two columns of them.
    val columns = if (layoutMode == TrackLayoutMode.LIST) 1 else 2
    IndexedList(
        target = rememberScrollTarget(gridState),
        indexKeys = remember(tracks, sort) { tracks.indexKeysFor(sort) { it.title to it.artist } },
        onJumped = onJumped,
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = u * 40f,
                end = u * 40f,
                top = u * 236f,
                bottom = listBottomPadding() + 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(u * 40f),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            gridItemsIndexed(tracks, key = { _, track -> track.id }, contentType = { _, _ -> "track" }) { index, track ->
                Box(Modifier.tabStagger(index, 12, 300, risePx = 16f, baseMs = 0)) {
                LibraryTrackRow(
                    track = track,
                    playing = track.id == nowPlayingId,
                    accent = accent,
                    compact = columns > 1,
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
private fun LibraryTrackRow(
    track: Track, playing: Boolean, accent: Color, compact: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit,
) {
    // Worked out once per song, not on each composition of its row.
    val length = remember(track.durationMs) {
        val m = track.durationMs / 1000
        if (track.durationMs > 0) "${m / 60}:${(m % 60).toString().padStart(2, '0')}" else null
    }
    val coverKey = remember(track.albumId) { track.albumId.toString() }
    LibraryListRow(
        coverUri = track.artworkUri, coverKey = coverKey, coverDescription = track.album,
        title = track.title, subtitle = track.artist,
        trailingText = length,
        playing = playing, accent = accent, compact = compact,
        onClick = onClick, onLongClick = onLongClick,
    )
}

/**
 * A row of the lists (songs, albums): a rounded cover, the title in bold and the line under it paler, a figure on the right
 * (a song's length, an album's count) and three dots that open the menu; a hairline under it starts where the words do
 * and runs to the margin. A song that is playing has its cover veiled with the three bars of the level in the accent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryListRow(
    coverUri: android.net.Uri?, coverKey: String, coverDescription: String?,
    title: String, subtitle: String, trailingText: String?,
    playing: Boolean, accent: Color, compact: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit,
) {
    val u = com.ikko.aura.ui.components.designUnit()
    // Every album cover has Apple Music's small corners, the same everywhere (the Home's four cards and the mini player aside).
    val coverShape = com.ikko.aura.ui.theme.AuraShapes.AlbumCover
    val face = com.ikko.aura.ui.theme.AuraFontFamily
    Box(Modifier.fillMaxWidth().height(u * 138f).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(u * 28f),
        ) {
            Box(Modifier.size(u * 116f).clip(coverShape)) {
                com.ikko.aura.ui.components.AlbumArt(
                    uri = coverUri, contentDescription = coverDescription, shape = coverShape,
                    modifier = Modifier.fillMaxSize(), targetSizeDp = u * 116f, cacheKey = coverKey,
                )
                if (playing) {
                    Box(Modifier.fillMaxSize().background(Color(0x8C080E45)), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Canvas(Modifier.size(u * 44f)) {
                            val k = size.width / 24f
                            // The level's three bars, as the design draws them (a 24 grid, bottoms on 20).
                            for (b in 0 until 3) {
                                val x = LEVEL_BARS[b * 3]; val y = LEVEL_BARS[b * 3 + 1]; val h = LEVEL_BARS[b * 3 + 2]
                                drawRoundRect(
                                    accent, topLeft = Offset(x * k, y * k), size = androidx.compose.ui.geometry.Size(3.6f * k, h * k),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.8f * k),
                                )
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title, color = if (playing) accent else LibInk, fontFamily = face, fontWeight = FontWeight.Bold,
                    fontSize = com.ikko.aura.ui.components.designSp(40f), lineHeight = com.ikko.aura.ui.components.designSp(48f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle, color = LibMuted, fontFamily = face, fontWeight = FontWeight.Medium,
                    fontSize = com.ikko.aura.ui.components.designSp(32f), lineHeight = com.ikko.aura.ui.components.designSp(40f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!compact) {
                if (trailingText != null) {
                    Text(
                        trailingText, color = LibMuted, fontFamily = face, fontWeight = FontWeight.Medium,
                        fontSize = com.ikko.aura.ui.components.designSp(34f), maxLines = 1,
                        style = TABULAR_STYLE,
                    )
                }
                // The menu: three dots in a 76 touch area (a long press does the same).
                Box(Modifier.size(u * 76f).clickable(onClick = onLongClick), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Canvas(Modifier.size(width = u * 44f, height = u * 10f)) {
                        val r = size.height / 2f
                        repeat(3) { i -> drawCircle(Color.White.copy(alpha = 0.7f), r, Offset(r + i * (size.width - 2 * r) / 2f, r)) }
                    }
                }
            }
        }
        // The hairline starts under the words, not under the cover.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(start = u * (116f + 28f))
                .fillMaxWidth()
                .height(u * 1.5f)
                .background(Color.White.copy(alpha = 0.16f)),
        )
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
    // The setting of the Réglages: two or three columns of covers, or a list of rows.
    val isList = layoutMode == AlbumLayoutMode.LIST
    val columns = when (layoutMode) {
        AlbumLayoutMode.GRID_2 -> 2
        AlbumLayoutMode.GRID_3 -> 3
        AlbumLayoutMode.LIST -> 1
    }
    val u = com.ikko.aura.ui.components.designUnit()
    val coverShape = com.ikko.aura.ui.theme.AuraShapes.AlbumCover
    val titleFontSize = com.ikko.aura.ui.components.designSp(34f)
    val titleLineHeight = com.ikko.aura.ui.components.designSp(42f)
    val artistFontSize = com.ikko.aura.ui.components.designSp(30f)
    val artistLineHeight = com.ikko.aura.ui.components.designSp(38f)
    val titleStyle = remember(titleFontSize, titleLineHeight) {
        androidx.compose.ui.text.TextStyle(
            fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
            color = LibInk,
            fontSize = titleFontSize,
            lineHeight = titleLineHeight,
            fontWeight = FontWeight.ExtraBold,
        )
    }
    val artistStyle = remember(artistFontSize, artistLineHeight) {
        androidx.compose.ui.text.TextStyle(
            fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
            color = LibMuted,
            fontSize = artistFontSize,
            lineHeight = artistLineHeight,
            fontWeight = FontWeight.SemiBold,
        )
    }
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
                top = u * 236f,
                bottom = listBottomPadding() + 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(u * 26f),
            verticalArrangement = Arrangement.spacedBy(u * 32f),
        ) {
            itemsIndexed(albums, key = { _, album -> album.id }, contentType = { _, _ -> if (isList) "album_row" else "album_cell" }) { albumIndex, album ->
                Box(Modifier.tabStagger(albumIndex, 12, 300, risePx = 16f, baseMs = 0)) {
                    AlbumGridCell(album, isList, columns, navController, onAlbumLongPress)
                }
            }
        }
    }
}

/** One album of the album grid, as the library draws it: a row in the list layout, a cover with its title and artist in the grids. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumGridCell(
    album: Album,
    isList: Boolean,
    columns: Int,
    navController: NavHostController,
    onAlbumLongPress: (Album) -> Unit,
    /** The hero page this grid lives in, when it is not a list of the library's own: its cells then stay while the trip that came to it rests. */
    pageKey: String? = null,
) {
    val u = com.ikko.aura.ui.components.designUnit()
    val coverShape = com.ikko.aura.ui.theme.AuraShapes.AlbumCover
    val titleFontSize = com.ikko.aura.ui.components.designSp(34f)
    val titleLineHeight = com.ikko.aura.ui.components.designSp(42f)
    val artistFontSize = com.ikko.aura.ui.components.designSp(30f)
    val artistLineHeight = com.ikko.aura.ui.components.designSp(38f)
    val titleStyle = remember(titleFontSize, titleLineHeight) {
        androidx.compose.ui.text.TextStyle(
            fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
            color = LibInk,
            fontSize = titleFontSize,
            lineHeight = titleLineHeight,
            fontWeight = FontWeight.ExtraBold,
        )
    }
    val artistStyle = remember(artistFontSize, artistLineHeight) {
        androidx.compose.ui.text.TextStyle(
            fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
            color = LibMuted,
            fontSize = artistFontSize,
            lineHeight = artistLineHeight,
            fontWeight = FontWeight.SemiBold,
        )
    }
    val targetArtDp = if (columns == 2) 260.dp else 180.dp
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
                        modifier = Modifier.heroRipple(heroKey, pageKey = pageKey),
                        verticalArrangement = Arrangement.spacedBy(u * 12f),
                    ) { // 316 px cover, 12 px under it
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
                                            radiusPx = with(tileDensity) { 8.dp.toPx() },
                                            title = titleAnchor to com.ikko.aura.ui.components.HeroText(album.title, androidx.compose.ui.geometry.Rect.Zero, titleStyle, LibInk),
                                            sub = subAnchor to com.ikko.aura.ui.components.HeroText(album.artist, androidx.compose.ui.geometry.Rect.Zero, artistStyle, LibMuted),
                                            sizeDp = targetArtDp,
                                            anyOfKind = album.remoteId != null,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumListItem(
    album: Album,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        LibraryListRow(
            coverUri = album.artworkUri, coverKey = album.id.toString(), coverDescription = album.title,
            title = album.title, subtitle = album.artist,
            trailingText = if (album.trackCount > 0) "${album.trackCount} titre${if (album.trackCount > 1) "s" else ""}" else null,
            playing = false, accent = Color.Unspecified, compact = false,
            onClick = onClick, onLongClick = onLongClick,
        )
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
        contentPadding = PaddingValues(start = u * 40f, end = u * 40f, top = u * 236f, bottom = listBottomPadding() + 12.dp),
    ) {
        itemsIndexed(rows, key = { _, r -> r.first().id }, contentType = { _, _ -> "track_pair" }) { rowIndex, pair ->
            Row(
                modifier = Modifier.tabStagger(rowIndex, 12, 300, risePx = 16f, baseMs = 0).fillMaxWidth().height(u * 132f),
                horizontalArrangement = Arrangement.spacedBy(u * 40f),
            ) {
                pair.forEach { artist ->
                    val hero = com.ikko.aura.ui.components.LocalHero.current
                    val heroKey = "artist:${artist.id}"
                    val coverAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
                    val titleAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
                    val rowDensity = androidx.compose.ui.platform.LocalDensity.current
                    val nameStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = com.ikko.aura.ui.theme.AuraFontFamily, fontWeight = FontWeight.ExtraBold,
                        fontSize = com.ikko.aura.ui.components.designSp(36f), lineHeight = com.ikko.aura.ui.components.designSp(44f),
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
                                    radiusPx = with(rowDensity) { (u * 56f).toPx() },
                                    title = titleAnchor to com.ikko.aura.ui.components.HeroText(artist.name, androidx.compose.ui.geometry.Rect.Zero, nameStyle, LibInk),
                                    sub = null,
                                    drawCover = false,
                                )) navController.navigate(AuraDestination.ArtistPattern.route(artist.id.toString()))
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(u * 24f),
                    ) {
                        Box(
                            Modifier.size(u * 112f).heroAnchor(heroKey, coverAnchor).clip(CircleShape).background(Color(0xFF1F1F27)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                artist.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
                                color = Color(0xFFC4C4CD), fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                                fontWeight = FontWeight.ExtraBold, fontSize = com.ikko.aura.ui.components.designSp(44f),
                            )
                            com.ikko.aura.data.rememberArtistImage(artist.name)?.let { photo ->
                                com.ikko.aura.ui.components.AlbumArt(
                                    uri = photo, contentDescription = artist.name, shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                                    modifier = Modifier.fillMaxSize(), targetSizeDp = u * 112f, cacheKey = photo.toString(),
                                )
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                artist.name, color = LibInk, fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                                fontWeight = FontWeight.ExtraBold, fontSize = com.ikko.aura.ui.components.designSp(36f),
                                lineHeight = com.ikko.aura.ui.components.designSp(44f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.heroAnchor(heroKey, titleAnchor, com.ikko.aura.ui.components.HeroState.ROLE_TITLE),
                            )
                            Text(
                                "${artist.trackCount} ${if (artist.trackCount > 1) "titres" else "titre"} · " +
                                    "${artist.albumCount} ${if (artist.albumCount > 1) "albums" else "album"}",
                                color = LibMuted, fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                                fontWeight = FontWeight.SemiBold, fontSize = com.ikko.aura.ui.components.designSp(30f),
                                lineHeight = com.ikko.aura.ui.components.designSp(38f), maxLines = 1, overflow = TextOverflow.Ellipsis,
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
internal fun PlaylistsList(
    playlists: List<PlaylistSummary>,
    customArtworks: Map<Long, String> = emptyMap(),
    tracksById: Map<String, com.ikko.aura.data.model.Track> = emptyMap(),
    listState: androidx.compose.foundation.lazy.LazyListState,
    creating: Boolean,
    onCreate: (String) -> Unit,
    onCancelCreate: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val u = com.ikko.aura.ui.components.designUnit()
    var newName by remember { mutableStateOf("") }
    val rows = remember(playlists) { playlists.chunked(3) }
    val accent = com.ikko.aura.ui.components.rememberPlayerAccent()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = u * 40f, end = u * 40f, top = u * 236f, bottom = listBottomPadding() + 12.dp),
        verticalArrangement = Arrangement.spacedBy(u * 32f),
    ) {
        if (creating) item(key = "create_playlist", contentType = "create_playlist") {
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
                }) { Icon(CupertinoIcons.Add, contentDescription = "Créer", tint = accent) }
                IconButton(onClick = { newName = ""; onCancelCreate() }) {
                    Icon(CupertinoIcons.Close, contentDescription = "Annuler", tint = LibMuted)
                }
            }
        }

        if (playlists.isEmpty() && !creating) {
            item(key = "no_playlists", contentType = "empty") {
                Text(
                    text = "Aucune playlist pour le moment",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AuraColors.TextSecondary,
                )
            }
        }

        itemsIndexed(rows, key = { _, r -> r.first().id }, contentType = { _, _ -> "playlist_trio" }) { rowIndex, trio ->
            Row(
                modifier = Modifier.tabStagger(rowIndex, 12, 300, risePx = 16f, baseMs = 0).fillMaxWidth(),
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
                        fontSize = com.ikko.aura.ui.components.designSp(34f), lineHeight = com.ikko.aura.ui.components.designSp(42f),
                    )
                    val subStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = com.ikko.aura.ui.theme.AuraFontFamily, fontWeight = FontWeight.SemiBold,
                        fontSize = com.ikko.aura.ui.components.designSp(30f), lineHeight = com.ikko.aura.ui.components.designSp(38f),
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
                                    radiusPx = with(tileDensity) { 8.dp.toPx() },
                                    title = titleAnchor to com.ikko.aura.ui.components.HeroText(playlist.name, androidx.compose.ui.geometry.Rect.Zero, titleStyle, LibInk),
                                    sub = subAnchor to com.ikko.aura.ui.components.HeroText(countText, androidx.compose.ui.geometry.Rect.Zero, subStyle, LibMuted),
                                    drawCover = false,
                                )) onOpen(playlist.id)
                            },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .heroAnchor(heroKey, coverAnchor)
                                .clip(com.ikko.aura.ui.theme.AuraShapes.AlbumCover),
                            contentAlignment = Alignment.Center,
                        ) {
                            com.ikko.aura.ui.components.PlaylistSquareCover(
                                playlist = playlist,
                                tracksById = tracksById,
                                customArtworkUri = customArtworks[playlist.id],
                                playing = !listState.isScrollInProgress,
                            )
                        }
                        Text(
                            playlist.name, color = LibInk, fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                            fontWeight = FontWeight.ExtraBold, fontSize = com.ikko.aura.ui.components.designSp(34f),
                            lineHeight = com.ikko.aura.ui.components.designSp(42f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = u * 12f)
                                .heroAnchor(heroKey, titleAnchor, com.ikko.aura.ui.components.HeroState.ROLE_TITLE),
                        )
                        Text(
                            countText,
                            color = LibMuted, fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                            fontWeight = FontWeight.SemiBold, fontSize = com.ikko.aura.ui.components.designSp(30f),
                            lineHeight = com.ikko.aura.ui.components.designSp(38f), maxLines = 1,
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

/**
 * Material's shared axis on x, as a cross-fade: the pages move this far; the page's alpha is how far in it is and the menu's
 * the rest, so the two always make a whole and nothing dips in between (fading one out and then the other in left a dark moment
 * at the middle, which blinked).
 */
private val AXIS_SHIFT = 30.dp
private const val AXIS_MENU_REST = 0.3f
private const val PUSH_MS = 300
private val PushEasing = androidx.compose.animation.core.FastOutSlowInEasing

/** The playing row's level: x, top and height of its three bars on the design's 24 grid (no list of triples per frame). */
private val LEVEL_BARS = floatArrayOf(3.5f, 9f, 11f, 10.2f, 4f, 16f, 16.9f, 12f, 8f)

/** A song's length in the rows, in figures of one width; one style, not one per row. */
private val TABULAR_STYLE = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
