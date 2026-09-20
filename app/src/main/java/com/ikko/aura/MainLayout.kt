package com.ikko.aura

import android.net.Uri
import androidx.navigation.NavBackStackEntry
import com.ikko.aura.ui.theme.AuraMotion
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.FloatingPlayerPill
import com.ikko.aura.ui.components.AlbumArtBackground
import com.ikko.aura.ui.components.LocalAuraBackdrop
import com.ikko.aura.ui.components.GLASS_SETTLE_FADE_MS
import com.ikko.aura.ui.components.LocalGlassSettled
import com.ikko.aura.ui.components.backdrop.backdrops.layerBackdrop
import com.ikko.aura.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.ikko.aura.ui.components.PermissionGate
import com.ikko.aura.ui.screens.album.AlbumScreen
import com.ikko.aura.ui.screens.album.AlbumListScreen
import com.ikko.aura.ui.screens.artist.ArtistScreen
import com.ikko.aura.ui.screens.equalizer.EqualizerScreen
import com.ikko.aura.ui.screens.home.HomeScreen
import com.ikko.aura.ui.screens.library.LibraryScreen
import com.ikko.aura.ui.screens.lyrics.LyricsScreen
import com.ikko.aura.ui.screens.nowplaying.NowPlayingScreen
import com.ikko.aura.ui.screens.playlist.PlaylistScreen
import com.ikko.aura.ui.screens.queue.QueueScreen
import com.ikko.aura.ui.screens.search.SearchPanel
import com.ikko.aura.ui.screens.settings.SettingsScreen
import com.ikko.aura.ui.screens.stats.StatsScreen
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalNowPlayingMesh
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.PlayerViewModel
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.chrisbanes.haze.hazeSource

/**
 * When the glass starts coming back, relative to the page transition starting.
 *
 * Timed so the fade *finishes* as the page stops, rather than beginning once it has. Waiting for
 * the movement to end first meant every panel on the screen changed together a beat after
 * everything else had settled — which reads as the screen blinking, not as glass arriving. Run
 * over the tail of the slide it is continuous, and the frames it costs are the slowest ones of
 * the animation, where there is room for them.
 */
private val GLASS_SETTLE_START_MS = (AuraMotion.SHEET_MS - GLASS_SETTLE_FADE_MS).toLong()

/** Routes presented as a sheet rising from the bottom rather than pushed in from the side. */
private fun isSheet(entry: NavBackStackEntry) = entry.destination.route == AuraDestination.NowPlaying.route

private fun pushEnter() =
    slideInHorizontally(tween(AuraMotion.PUSH_MS, easing = FastOutSlowInEasing)) { it / 4 } +
        fadeIn(tween(AuraMotion.PUSH_MS))

private fun pushExit() =
    slideOutHorizontally(tween(AuraMotion.PUSH_MS, easing = FastOutSlowInEasing)) { -it / 4 } +
        fadeOut(tween(AuraMotion.PUSH_MS))

private fun popEnter() =
    slideInHorizontally(tween(AuraMotion.PUSH_MS, easing = FastOutSlowInEasing)) { -it / 4 } +
        fadeIn(tween(AuraMotion.PUSH_MS))

private fun popExit() =
    slideOutHorizontally(tween(AuraMotion.PUSH_MS, easing = FastOutSlowInEasing)) { it / 4 } +
        fadeOut(tween(AuraMotion.PUSH_MS))

@Composable
fun MainLayout() {
    val navController = rememberNavController()
    val hazeState = remember { HazeState() }
    // Records only the artwork backdrop, not the page on top of it — see LocalAuraBackdrop.
    val glassBackdrop = rememberLayerBackdrop()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    // Narrowed on purpose. Collecting the whole PlayerUiState here subscribed MainLayout — and so
    // the entire NavHost under it — to `positionMs`, which ticks every 500ms during playback, so
    // the whole tree recomposed four times a second while scrolling. Only the backdrop's inputs
    // are read at this level; everything position-related stays inside PlayerPillHost.
    val backdrop by rememberBackdropState(playerViewModel)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showPill = currentRoute !in AuraDestination.fullScreenRoutes
    val density = LocalDensity.current

    // True once a page has stopped moving. The liquid glass reads it and stays out of the way
    // until then — see LocalGlassSettled for why a sliding glass panel is so expensive.
    val glassSettled = remember { mutableStateOf(true) }
    LaunchedEffect(currentRoute) {
        glassSettled.value = false
        delay(GLASS_SETTLE_START_MS)
        glassSettled.value = true
    }

    // Pull-to-search: how far the page has receded, 0 shut, 1 open.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searchPull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val searchConnection = remember {
        object : NestedScrollConnection {
            /**
             * Downward scroll the page could not use is the gesture: it only ever happens when a
             * list is already at its very top and the finger keeps pulling. Nothing else in the
             * app means that, which is why there is no threshold to arm and nothing to disambiguate.
             */
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (searchOpen || source != NestedScrollSource.UserInput || available.y <= 0f) {
                    return Offset.Zero
                }
                val next = (searchPull.value + available.y / PULL_DISTANCE_PX).coerceAtMost(1f)
                scope.launch { searchPull.snapTo(next) }
                return Offset(0f, available.y)
            }

            /** Pulling back up puts the page back before it scrolls, so the gesture is reversible. */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (searchOpen || searchPull.value <= 0f || available.y >= 0f) return Offset.Zero
                val used = maxOf(available.y, -searchPull.value * PULL_DISTANCE_PX)
                scope.launch { searchPull.snapTo((searchPull.value + used / PULL_DISTANCE_PX).coerceAtLeast(0f)) }
                return Offset(0f, used)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (searchOpen) return Velocity.Zero
                if (searchPull.value >= PULL_COMMIT) {
                    searchOpen = true
                    searchPull.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
                } else if (searchPull.value > 0f) {
                    searchPull.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                }
                return Velocity.Zero
            }
        }
    }

    fun closeSearch() {
        searchOpen = false
        scope.launch { searchPull.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
    }

    BackHandler(enabled = searchOpen) { closeSearch() }

    var pillHeightPx by remember { mutableStateOf(0) }
    val reservedPillHeight =
        if (showPill && backdrop.hasTrack) with(density) { pillHeightPx.toDp() } else 0.dp

    Surface(color = AuraColors.Black, modifier = Modifier.fillMaxSize()) {
        PermissionGate {
        CompositionLocalProvider(
            LocalNowPlayingMesh provides backdrop.meshColors,
            LocalPlayerPillHeight provides reservedPillHeight,
            LocalAuraBackdrop provides glassBackdrop,
            LocalGlassSettled provides glassSettled,
        ) {
        Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
        // The glass layer is recorded here, around the backdrop alone. Haze's source stays on the
        // enclosing box so the pill keeps blurring the list scrolling under it; the player's glass
        // wants only what is behind it, and recording the page into a layer the page itself
        // samples from is what makes overlapping glass smear.
        AlbumArtBackground(
            artworkUri = backdrop.artworkUri,
            artworkKey = backdrop.artworkKey,
            fallbackColors = backdrop.meshColors,
            primaryColor = backdrop.primaryColor,
            forceArtwork = currentRoute == AuraDestination.NowPlaying.route,
            // Recorded around the settled backdrop only, not around the whole thing: the cover on
            // its way out is drawn above this and deliberately outside the record. See the
            // parameter's own note for what that buys.
            settledModifier = Modifier.layerBackdrop(glassBackdrop),
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .nestedScroll(searchConnection)
                // The page recedes rather than being covered: it shrinks a little and slides down
                // under the panel, which is what makes the search read as arriving *over* what you
                // were doing instead of replacing it.
                .graphicsLayer {
                    val p = searchPull.value
                    if (p == 0f) return@graphicsLayer
                    val shrink = 1f - PAGE_RECEDE * p
                    scaleX = shrink
                    scaleY = shrink
                    translationY = size.height * PAGE_SLIDE * p
                },
        ) {
            NavHost(
                navController = navController,
                startDestination = AuraDestination.Tabs.route,
                modifier = Modifier.fillMaxWidth().weight(1f),
                // Two vocabularies left, now that the five spaces are pages of one pager rather
                // than five destinations: the player rises as a sheet, everything else is pushed.
                enterTransition = {
                    if (isSheet(targetState)) AuraMotion.sheetEnter else pushEnter()
                },
                exitTransition = {
                    // Faded, not held still. Every screen here is transparent over the one shared
                    // backdrop, so leaving this one fully drawn while the player rose over it
                    // showed both at once.
                    if (isSheet(targetState)) AuraMotion.sheetUnderExit else pushExit()
                },
                popEnterTransition = {
                    if (isSheet(initialState)) AuraMotion.sheetUnderEnter else popEnter()
                },
                popExitTransition = {
                    if (isSheet(initialState)) AuraMotion.sheetExit else popExit()
                },
            ) {
                composable(AuraDestination.Tabs.route) {
                    TabPager(navController = navController, playerViewModel = playerViewModel)
                }
                composable(AuraDestination.Queue.route) {
                    QueueScreen(navController = navController, playerViewModel = playerViewModel)
                }
                composable(AuraDestination.Lyrics.route) {
                    LyricsScreen(navController = navController, playerViewModel = playerViewModel)
                }
                composable(AuraDestination.Equalizer.route) { EqualizerScreen(navController) }
                composable(AuraDestination.NowPlaying.route) {
                    NowPlayingScreen(navController = navController, viewModel = playerViewModel)
                }
                composable(AuraDestination.AlbumPattern.route) { backStack ->
                    AlbumScreen(
                        albumId = backStack.arguments?.getString("albumId").orEmpty(),
                        navController = navController,
                        playerViewModel = playerViewModel,
                    )
                }
                composable(AuraDestination.ArtistPattern.route) { backStack ->
                    ArtistScreen(
                        artistId = backStack.arguments?.getString("artistId").orEmpty(),
                        navController = navController,
                        playerViewModel = playerViewModel,
                    )
                }
                composable(AuraDestination.PlaylistPattern.route) { backStack ->
                    PlaylistScreen(
                        playlistId = backStack.arguments?.getString("playlistId").orEmpty(),
                        navController = navController,
                        playerViewModel = playerViewModel,
                    )
                }
                composable(AuraDestination.AlbumListPattern.route) { backStack ->
                    AlbumListScreen(
                        mode = backStack.arguments?.getString("mode").orEmpty(),
                        navController = navController,
                    )
                }
            }
        }
        }

            // Search, over everything the page is doing. Composed only while it is on its way in
            // or already there, so a shut search costs nothing at all.
            //
            // Derived rather than read straight: the pull is an Animatable driven by the finger,
            // so `searchPull.value` moves every frame of the gesture and reading it here made the
            // whole layout — pager, pill and backdrop included — recompose ninety times a second
            // to answer a question whose answer changes twice per pull.
            val searchVisible by remember {
                derivedStateOf { searchPull.value > 0f }
            }
            if (searchVisible) {
                SearchOverlay(
                    progressProvider = { searchPull.value },
                    hazeState = hazeState,
                    open = searchOpen,
                    onDismiss = ::closeSearch,
                    navController = navController,
                    playerViewModel = playerViewModel,
                )
            }

            if (showPill) {
                PlayerPillHost(
                    playerViewModel = playerViewModel,
                    hazeState = hazeState,
                    onOpenNowPlaying = { navController.navigate(AuraDestination.NowPlaying.route) },
                    onHeightMeasured = { pillHeightPx = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        // Measured inside the window inset (which GradientScreenScaffold applies
                        // on its own) but outside the pill's own margins, so what screens reserve
                        // is exactly the space the pill visually occupies above the nav bar.
                        .onSizeChanged { pillHeightPx = it.height }
                        .padding(horizontal = 21.2.dp, vertical = 6.dp),
                )
            }
        }
        }
        }
    }
}

/**
 * Everything MainLayout itself needs from the player, and nothing that changes while a track
 * merely plays — so the app-wide tree is only invalidated when the artwork or palette actually
 * changes, not on every position tick.
 */
@Immutable
private data class BackdropState(
    val artworkUri: Uri?,
    val artworkKey: String?,
    val meshColors: List<Color>,
    val primaryColor: Color,
    val hasTrack: Boolean,
)

@Composable
private fun rememberBackdropState(playerViewModel: PlayerViewModel): State<BackdropState> {
    val flow = remember(playerViewModel) {
        playerViewModel.uiState
            .map {
                BackdropState(
                    artworkUri = it.currentTrack?.artworkUri,
                    artworkKey = it.currentTrack?.albumId?.toString(),
                    meshColors = it.meshColors,
                    primaryColor = it.primaryColor,
                    hasTrack = it.currentTrack != null,
                )
            }
            .distinctUntilChanged()
    }
    return flow.collectAsStateWithLifecycle(
        BackdropState(null, null, AuraColors.DefaultMesh, AuraColors.DefaultPrimary, false),
    )
}

/**
 * Owns the pill's subscription to the player state. Keeping it here rather than in [MainLayout]
 * means the twice-a-second progress updates recompose this one composable instead of the whole
 * navigation tree.
 */
@Composable
private fun PlayerPillHost(
    playerViewModel: PlayerViewModel,
    hazeState: HazeState,
    onOpenNowPlaying: () -> Unit,
    onHeightMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val track = uiState.currentTrack ?: return

    FloatingPlayerPill(
        track = track,
        queueIndex = uiState.queueIndex,
        isPlaying = uiState.isPlaying,
        hazeState = hazeState,
        onTogglePlay = playerViewModel::togglePlayPause,
        onNext = playerViewModel::skipNext,
        onPrevious = playerViewModel::skipPrevious,
        onOpen = onOpenNowPlaying,
        modifier = modifier,
    )
}

/**
 * The five spaces, swiped between rather than tapped between.
 *
 * A pager rather than five navigation destinations, and that is what lets the icon row go: with
 * destinations, moving between them needs something to tap, and the row that provided it cost
 * 64dp of a 496dp-tall screen permanently. Held as pages, the gesture *is* the navigation.
 *
 * A pager also nests properly with what is inside it — the library's filter chips, Home's
 * carousels — because Compose's nested scrolling gives those the drag first and hands the pager
 * whatever they could not use at their own edge. A screen-wide drag detector would have fought
 * them for every gesture.
 *
 * One page either side is kept composed. At zero the page being swiped to is blank until it
 * composes, which is exactly the frame the gesture makes you look at.
 */
@Composable
private fun TabPager(navController: NavHostController, playerViewModel: PlayerViewModel) {
    val pages = AuraDestination.topLevelRoutes
    val pagerState = rememberPagerState(initialPage = 0) { pages.size }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
        key = { pages[it].route },
    ) { page ->
        when (pages[page]) {
            AuraDestination.Home ->
                HomeScreen(navController = navController, playerViewModel = playerViewModel)
            AuraDestination.Library ->
                LibraryScreen(navController = navController, playerViewModel = playerViewModel)
            AuraDestination.Stats -> StatsScreen(navController)
            AuraDestination.Settings -> SettingsScreen(navController)
            else -> Unit
        }
    }
}

/**
 * The search panel, and the page receding behind it.
 *
 * Modelled on iOS Spotlight: the page blurs, darkens and draws back, and the field arrives over
 * it. The one deliberate difference is the direction — the field comes down from the top edge,
 * because that is the edge the gesture starts from and a panel that arrived from anywhere else
 * would not feel connected to the finger that asked for it.
 *
 * The blur is [hazeState], the same one the player pill samples, so this costs one more surface
 * rather than a second capture of the screen. It is faded in by alpha rather than by animating
 * the blur radius: a radius that changes rebuilds the whole effect pipeline on every frame of the
 * pull, which is the mistake the animated backdrop made before it was measured.
 *
 * [progressProvider] is read in draw and layer blocks only, so dragging repaints and never
 * recomposes the results underneath.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun SearchOverlay(
    progressProvider: () -> Float,
    hazeState: HazeState,
    open: Boolean,
    onDismiss: () -> Unit,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
) {
    val surfaceTint = MaterialTheme.colorScheme.surface

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progressProvider() }
            .hazeEffect(state = hazeState, style = HazeMaterials.regular(surfaceTint))
            .background(AuraColors.GlassScrim)
            // Tapping what is left of the page puts it back — the same way the gesture came.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .graphicsLayer {
                val p = progressProvider()
                alpha = p
                // Comes down from above the top edge, arriving as the pull completes.
                translationY = -size.height * PANEL_ENTRY_TRAVEL * (1f - p)
            },
    ) {
        // Built only once the gesture has committed: the field takes focus and raises the
        // keyboard the moment it exists, which is not something to do halfway through a drag that
        // may yet be pulled back.
        if (open) {
            SearchPanel(
                navController = navController,
                playerViewModel = playerViewModel,
                onNavigate = onDismiss,
            )
        }
    }
}

/** How far a finger has to pull past the top of a list before the search is offered. */
private const val PULL_DISTANCE_PX = 320f

/** How much of that pull commits to opening rather than springing back. */
private const val PULL_COMMIT = 0.55f

/** How much the page shrinks and slides as it recedes. */
private const val PAGE_RECEDE = 0.06f
private const val PAGE_SLIDE = 0.04f

/** Share of the screen the panel travels down through on its way in. */
private const val PANEL_ENTRY_TRAVEL = 0.12f
