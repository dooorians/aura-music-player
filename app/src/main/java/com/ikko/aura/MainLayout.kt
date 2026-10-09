package com.ikko.aura

import android.net.Uri
import androidx.navigation.NavBackStackEntry
import com.ikko.aura.ui.theme.AuraMotion
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
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
import com.ikko.aura.ui.components.AlbumTheme
import com.ikko.aura.ui.components.LocalAuraBackdrop
import com.ikko.aura.ui.components.backdrop.GlassSettle
import com.ikko.aura.ui.components.backdrop.LocalGlassSettle
import com.ikko.aura.ui.components.backdrop.backdrops.LayerBackdrop
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
import com.ikko.aura.ui.screens.settings.SourceOrderScreen
import com.ikko.aura.ui.screens.ytm.RemoteAlbumScreen
import com.ikko.aura.ui.screens.ytm.RemoteArtistScreen
import com.ikko.aura.ui.screens.ytm.YtmAlbumScreen
import com.ikko.aura.ui.screens.ytm.YtmArtistScreen
import com.ikko.aura.ui.screens.settings.SettingsScreen
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalNowPlayingMesh
import com.ikko.aura.ui.theme.LocalPageTheme
import com.ikko.aura.ui.theme.LocalSearchOpener
import com.ikko.aura.ui.components.AuroraBackground
import com.ikko.aura.ui.components.LocalPageMotion
import com.ikko.aura.ui.components.LocalTabCapsule
import com.ikko.aura.ui.components.PageMotionSpec
import com.ikko.aura.ui.components.PageMotionState
import com.ikko.aura.ui.components.PagedContainer
import com.ikko.aura.ui.components.TabCapsule
import com.ikko.aura.ui.components.TabCapsuleState
import com.ikko.aura.ui.components.capsuleBottom
import com.ikko.aura.ui.components.capsuleTo
import com.ikko.aura.ui.components.capsuleWanted
import com.ikko.aura.ui.components.rememberPageMotion
import com.ikko.aura.viewmodel.TabCapsuleViewModel
import kotlinx.coroutines.flow.collectLatest
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The pill comes back as the closing card reaches its place (the card takes 320 ms to get there). */
private const val PILL_RETURN_DELAY_MS = 210L
private const val PILL_FADE_IN_MS = 110
private const val PILL_FADE_OUT_MS = 30

/** Routes presented as a sheet over the page, fading in and out, rather than pushed in from the side. */
/** What the navigation transitions have worked out about the player's card, for the screen to read. */
/**
 * Where the player's card is, and who is driving it.
 *
 * One object, with one owner, because everything else on screen is arranged around it: the page under the
 * card is drawn only above its top edge, and the mini player and the tab capsule stay away while it covers
 * them. Held as separate flags, a cancelled or interrupted transition could leave one of them saying the
 * card was still there after the player had gone — and a page clipped to a card that no longer exists is an
 * empty screen you can still tap.
 *
 * The player claims it on its first composition and gives it back when it leaves, and nothing else writes
 * to it: what it says is true as long as [owner] is not null, and means nothing once it is.
 */
@Stable
class SheetCard {
    /** The card's top edge, as a fraction of the screen's height. 0 is up, 1 is no card. Read in draw. */
    var edge by mutableFloatStateOf(1f)
        internal set

    /** The player composition in charge of the card, or null when there is no player on screen. */
    var owner: Any? by mutableStateOf(null)
        internal set

    /**
     * Whether the card has something to show. False from the tap on the mini player until the player has
     * drawn itself, which takes a few frames: until then the card stays hidden and the mini player stays
     * where it is, so what the eye sees is the pill, then the card with the player already in it — never
     * an empty grey card at the pill's place (the blink on opening).
     */
    var ready by mutableStateOf(true)
        internal set

    /** Takes the card over, at [edge]. [ready] is false when the player still has to draw before it shows. */
    fun claim(token: Any, edge: Float, ready: Boolean = true) {
        owner = token
        this.edge = edge
        this.ready = ready
        sinking = false
    }

    /**
     * Moves the card, if [token] still holds it. A player that has lost the card to a newer one keeps running its
     * own animation until it is disposed, and it went on writing the card's position under the new player's feet:
     * two animations on one card, which is what a stutter on reopening mid-close was.
     */
    fun move(token: Any, edge: Float) {
        if (owner === token) this.edge = edge
    }

    fun markReady() {
        ready = true
    }

    /**
     * How many pages that can sit under the card are composed right now (see UnderSheet). A closing card waits
     * for one: the page is let go while the player is open, and is composed again by the pop.
     */
    var pagesComposed by androidx.compose.runtime.mutableIntStateOf(0)
        internal set

    /**
     * True while the player is coming back from one of its own pages (the lyrics, the queue, an album) by a dissolve.
     * The page under the card is cut out wherever the card is, and a returning player claims the card at the very top
     * in its first frame: the page it was dissolving into vanished at once, and for the frames the player took to
     * fade in there was nothing on screen but the cover's wash — the blink on the way back.
     */
    var passing by mutableStateOf(false)

    /**
     * The player is leaving sideways (for the lyrics, the queue, an album) and [topRoute] is where it is going. The pages
     * kept under the player are put away for that time: with the card gone to the edge they were drawn whole, and for a
     * tap soon after the player had opened — before the page under it had been let go — the home page showed through the
     * player as it faded, over the background it was dissolving into.
     */
    var leavingSideways by mutableStateOf(false)
        internal set

    var topRoute: String? by mutableStateOf(null)
        internal set

    /** Set by the player while its card is going back down to the mini player (its Closing phase). */
    var sinking by mutableStateOf(false)
        internal set

    /**
     * How opaque the card is: fully, except on its way down, where it clears over the last of the way (from
     * SHEET_CLEAR_FROM to the mini player) as the pill takes its place. The cover's wash behind it clears with
     * it, and the page under it comes back through it, so nothing is left to appear at once when it has gone.
     */
    val cardAlpha: Float
        get() = if (owner == null || !sinking) 1f else 1f - (
            (edge - AuraMotion.SHEET_CLEAR_FROM) / (AuraMotion.SHEET_PILL_TOP_FRACTION - AuraMotion.SHEET_CLEAR_FROM)
            ).coerceIn(0f, 1f)

    /** True while the sheet card is actively traveling (animating open or closed), so underlying backdrops and glass freeze. */
    var frozen: Boolean by mutableStateOf(false)
        internal set

    /** Gives it back, if it is still ours: another player may already have taken it. */
    fun release(token: Any) {
        if (owner === token) {
            owner = null
            edge = 1f
            ready = true
            sinking = false
            frozen = false
        }
    }

    /**
     * Whether a tap on the mini player may open the player now: no player holds the card, or the one that did has
     * taken it all the way back down to the pill. Not while a card is on its way down — the mini player is
     * invisible over it, yet it still caught taps, and each one started a new player that took the card from the
     * closing one mid-flight: two players driving one card, the stutter on spamming the pill.
     */
    val acceptsOpen: Boolean
        get() = owner == null || (sinking && edge >= AuraMotion.SHEET_PILL_TOP_FRACTION - 0.001f)

    /** True while the card is claimed but still hidden, waiting for the player to draw. */
    val waitingToShow: Boolean get() = owner != null && !ready

    /** Whether the card is over the mini player and the tab capsule. */
    val coversPill: Boolean get() = owner != null && edge < CARD_COVERS_PILL_BELOW

    /** Where the card's top edge is while it travels, as a fraction of the screen's height; 0 when it is up or absent. */
    val cardTop: Float get() = if (owner == null || edge >= 1f) 0f else edge.coerceIn(0f, 1f)

    /** Progress of opening the player sheet: 0f at mini player or closed, 1f at fullscreen. */
    val openProgress: Float
        // Not while the player is dissolving back over one of its own pages: that page is not under a card, it is
        // being faded into, and shrinking it and darkening it with the card's rise was the dark step of the blink.
        get() = if (owner == null || !ready || passing) 0f else (1f - (edge / AuraMotion.SHEET_PILL_TOP_FRACTION)).coerceIn(0f, 1f)

    /** How dark the page under the card is: none of it when the card is at the mini player, all of it when the card is up. */
    val dim: Float
        get() = if (owner == null || !ready) 0f else (AuraMotion.SHEET_DIM * openProgress).coerceIn(0f, 1f)
}

val LocalSheetCard = androidx.compose.runtime.staticCompositionLocalOf { SheetCard() }

/** The card covers the mini player and the tab capsule from here down: they stay away while it is over them. */
private const val CARD_COVERS_PILL_BELOW = 0.70f

/** What the navigation transitions have worked out about the player, for the screen itself to read. */
private class SheetFlags(private val card: SheetCard) {
    var opening = false
    var closing = false

    /**
     * Set when the player leaves for a page of its own (the lyrics, the queue, an album), cleared when it is opened
     * from the pill or comes back. `opening` is only written when a transition is worked out, and stays true from the
     * last time the pill opened the player: the player coming back from the lyrics read it, took itself for a player
     * being opened from the pill, and held the card hidden at the pill — drawing the pill — until it had shown.
     */
    var away = false
        set(value) {
            field = value
            card.leavingSideways = value
        }

    /** The player is being opened from the pill, as opposed to coming back from a page it had left for. */
    val fromPill: Boolean get() = opening && !away

    /**
     * Said at the touch that opens the player, before the navigation. The transition says the same thing, but it is
     * worked out a few frames after the route has changed, and in those frames the mini player — whose alpha follows
     * [fromPill] — was drawn as if no player were coming: it went out, came back once the transition had spoken, and
     * went out again under the card. One blink, on every first opening.
     */
    fun openingFromPill() {
        opening = true
        away = false
    }
}

/**
 * How much of the mini player and the tab capsule is left while the search is in: all of it when it is shut, none of it
 * once it is half way in. Going out it follows the search's pull at once; coming back it waits for the page to be nearly
 * itself again ([BACK_FROM], [BACK_TO]) — their glass shows what is behind it, and with the page still blurred and
 * shrunk (and the fade not done) it was see-through for the first moments, the text of the page showing in it.
 */
private const val BACK_FROM = 0.22f
private const val BACK_TO = 0.04f

private fun searchClear(pull: Float, closing: Boolean): Float {
    val out = (1f - pull * 2f).coerceIn(0f, 1f)
    val back = ((BACK_FROM - pull) / (BACK_FROM - BACK_TO)).coerceIn(0f, 1f)
    val u = if (closing) back else out
    return u * u * (3f - 2f * u)
}

/** How dim the page under the player's card is, 0..1. Read in draw only. */
val LocalSheetDim = androidx.compose.runtime.staticCompositionLocalOf<() -> Float> { { 0f } }

/**
 * A destination that can sit under the player's card: cut off at the card's top edge, and dimmed.
 *
 * Both are done to a layer of this page rather than in a draw over it. A clip that changes shape is a new
 * outline on the same recorded picture, while the reveal pass keeps the page visible beneath the card as its
 * alpha clears during the close.
 */
@Composable
private fun UnderSheet(content: @Composable () -> Unit) {
    val dim = LocalSheetDim.current
    val card = LocalSheetCard.current
    androidx.compose.runtime.DisposableEffect(card) {
        card.pagesComposed++
        onDispose { card.pagesComposed-- }
    }
    // Which page this is, to tell it from the one the player is leaving for.
    val myRoute = (androidx.lifecycle.compose.LocalLifecycleOwner.current as? androidx.navigation.NavBackStackEntry)?.destination?.route
    val cutout = remember { androidx.compose.ui.graphics.Path() }
    val revealPaint = remember { androidx.compose.ui.graphics.Paint() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxShiftY = with(density) { 14.dp.toPx() }
    val maxCornerRadius = with(density) { 26.dp.toPx() }
    val pageSnapshot = rememberGraphicsLayer()
    val pageSnapshotState = remember { PageSnapshotState() }

    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                if (!card.frozen) pageSnapshotState.invalidate()
                // A page under the player, while the player leaves for another one: not drawn at all.
                if (card.leavingSideways && card.owner != null && myRoute != null && myRoute != card.topRoute) return@drawWithContent
                val o = card.edge
                // Nor once the card has cleared completely (the end of a close): it is invisible, and the page is
                // drawn whole.
                if (card.passing || card.owner == null || !card.ready || o >= 1f || card.cardAlpha <= 0f) {
                    pageSnapshotState.invalidate()
                    drawContent()
                    return@drawWithContent
                }
                val snapshotSize = IntSize(size.width.toInt(), size.height.toInt())
                val useSnapshot = card.frozen && pageSnapshotState.isValid(snapshotSize)
                if (card.frozen && !useSnapshot) {
                    pageSnapshot.record(snapshotSize) {
                        this@drawWithContent.drawContent()
                    }
                    pageSnapshotState.record(snapshotSize)
                }
                fun drawPage() {
                    if (card.frozen && pageSnapshotState.isValid(snapshotSize)) {
                        drawLayer(pageSnapshot)
                    } else {
                        this@drawWithContent.drawContent()
                    }
                }
                setToCard(cutout, o, size, AuraMotion.cornerAt(o).toPx())
                clipPath(cutout, androidx.compose.ui.graphics.ClipOp.Difference) {
                    drawPage()
                }
                val reveal = 1f - card.cardAlpha
                if (reveal > 0.004f) {
                    clipPath(cutout) {
                        revealPaint.alpha = reveal
                        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), revealPaint)
                        drawPage()
                        drawContext.canvas.restore()
                    }
                }
            }
            .graphicsLayer { },
    ) {
        // Subtle depth effect: background page recedes, slides down slightly, and rounds its top corners
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val t = card.openProgress
                    if (t > 0.001f) {
                        val s = 1f - 0.055f * t
                        scaleX = s
                        scaleY = s
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                        translationY = maxShiftY * t
                        val corner = maxCornerRadius * t
                        // One radius on all four corners, with the bottom two pushed below the layer: the same
                        // picture (the page's bottom edge is square), but a clip the GPU can do by itself. Rounded
                        // at the top only it is a "complex" shape, which Skia masks on the CPU and uploads again
                        // on every frame of the rise — the same cost found in the card itself (see setToCard).
                        shape = TopRoundedClip(corner)
                        clip = true
                    }
                },
        ) {
            content()
        }

        // Full-screen dimming overlay: spans the full un-shifted bounds from y=0
        // all the way down to the player card cutout, so the darkening covers
        // the top contours and corners of the screen seamlessly.
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val d = dim()
                    if (d > 0.001f) drawRect(Color.Black, alpha = d)
                },
        )
    }
}

private class PageSnapshotState {
    private var size = IntSize.Zero
    private var valid = false

    fun record(newSize: IntSize) {
        size = newSize
        valid = true
    }

    fun isValid(newSize: IntSize): Boolean = valid && size == newSize

    fun invalidate() {
        valid = false
    }
}

/**
 * Sets [path] to the player's card as NowPlayingScreen lays it out, in the coordinates of a full-screen box of
 * [size]: pushed down by [edge] of the height, scaled about its top centre by 1 - SHEET_NARROWING * edge, with
 * top corners of cornerAt(edge) ([radiusPx], before that scale). The page under the card is drawn outside
 * this and the cover's wash inside it, so the two meet exactly at the card's own edge, corners included.
 */
private fun setToCard(path: androidx.compose.ui.graphics.Path, edge: Float, size: Size, radiusPx: Float) {
    val s = 1f - AuraMotion.SHEET_NARROWING * edge
    val width = size.width * s
    val left = (size.width - width) / 2f
    val top = edge * size.height
    val radius = androidx.compose.ui.geometry.CornerRadius(radiusPx * s)
    path.reset()
    path.addRoundRect(
        androidx.compose.ui.geometry.RoundRect(
            left = left,
            top = top,
            right = left + width,
            bottom = top + size.height * s,
            topLeftCornerRadius = radius,
            topRightCornerRadius = radius,
            bottomRightCornerRadius = radius,
            bottomLeftCornerRadius = radius,
        ),
    )
}


private fun isSheet(entry: NavBackStackEntry) = entry.destination.route == AuraDestination.NowPlaying.route

// Every push and pop starts a little late (PUSH_/POP_START_DELAY_MS), both pages together. The page coming in is built in
// the transition's first frame — an album page takes ~70 ms of it, measured — and the transition's clock was
// already running through that frame: its next frame drew the slide a third of the way in, half-way along
// this easing, and the rest played in a handful of frames (the jerk on opening an album). Waiting out that
// first frame before moving anything lets the whole slide play, at its own pace, from its start.
private fun pushEnter() =
    slideInHorizontally(tween(AuraMotion.PUSH_MS, delayMillis = AuraMotion.PUSH_START_DELAY_MS, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) { it / 4 } +
        fadeIn(tween(AuraMotion.PUSH_MS, delayMillis = AuraMotion.PUSH_START_DELAY_MS))

private fun pushExit() =
    slideOutHorizontally(tween(AuraMotion.PUSH_MS, delayMillis = AuraMotion.PUSH_START_DELAY_MS, easing = androidx.compose.animation.core.FastOutLinearInEasing)) { -it / 4 } +
        fadeOut(tween(AuraMotion.PUSH_MS, delayMillis = AuraMotion.PUSH_START_DELAY_MS))

private fun popEnter() =
    slideInHorizontally(tween(AuraMotion.PUSH_MS, delayMillis = AuraMotion.POP_START_DELAY_MS, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) { -it / 4 } +
        fadeIn(tween(AuraMotion.PUSH_MS, delayMillis = AuraMotion.POP_START_DELAY_MS))

private fun popExit() =
    slideOutHorizontally(tween(AuraMotion.PUSH_MS, delayMillis = AuraMotion.POP_START_DELAY_MS, easing = androidx.compose.animation.core.FastOutLinearInEasing)) { it / 4 } +
        fadeOut(tween(AuraMotion.PUSH_MS, delayMillis = AuraMotion.POP_START_DELAY_MS))

/**
 * The player and the full-screen lyrics are the two densest screens in the app and each is mostly
 * text and panels, so cross-fading them printed one over the other for a fifth of a second — lyric
 * lines across the transport buttons. Between these two the outgoing screen clears quickly and
 * the incoming one arrives just behind it, so they overlap for a few frames rather than a
 * hundred milliseconds. The slide is unchanged.
 */
private fun isLyrics(entry: NavBackStackEntry) = entry.destination.route == AuraDestination.Lyrics.route
private fun isLyricsOrAlbum(entry: NavBackStackEntry): Boolean {
    val route = entry.destination.route ?: return false
    return route == AuraDestination.Lyrics.route || route == AuraDestination.Queue.route || route.startsWith("album/")
}

// The incoming page's first frame is the heavy one (it is composed there), and the transition's clock runs through it:
// an outgoing fade that began with it was already over when it ended — the screen went blank in a single frame and the
// new one then took twelve to arrive (measured: edge energy 12 -> 2 -> 11). So the incoming page fades in from the
// start, over the outgoing one, which holds until the new one is mostly there and only then clears.
// A dissolve, not a hand-over: the outgoing screen clears while the incoming one is already arriving, so
// at no frame is there only the background. Both fades are linear and their alphas add up to about one
// across the overlap; with a gap between them (out in 90 ms, in after a slow-starting ease) the screen
// showed the bare cover for several frames in the middle — the blink between the player's menus.
// Between the player and one of its pages the two screens trade places in one gesture, the same both ways: the outgoing one
// slides a quarter of the width away and clears while the incoming one slides in from the other side and arrives, over
// the same short window — so going and coming back are mirror images. [startMs] is how long both wait before moving: the
// incoming screen's first frame is the heavy one, and moving anything during it only drops it.
// The outgoing screen clears a little ahead of the incoming one arriving, so the two overlap for a few frames (a
// dissolve) and never leave the bare background between them, and never print two screens' text over each other for long.
private fun throughEnter(fromLeft: Boolean, startMs: Int = AuraMotion.PUSH_START_DELAY_MS) =
    slideInHorizontally(tween(AuraMotion.PUSH_MS, delayMillis = startMs, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) { if (fromLeft) -it / 4 else it / 4 } +
        fadeIn(tween(THROUGH_IN_MS, delayMillis = startMs + THROUGH_IN_LAG_MS, easing = LinearEasing))

private fun throughExit(toLeft: Boolean, startMs: Int = AuraMotion.PUSH_START_DELAY_MS) =
    slideOutHorizontally(tween(AuraMotion.PUSH_MS, delayMillis = startMs, easing = androidx.compose.animation.core.FastOutLinearInEasing)) { if (toLeft) -it / 4 else it / 4 } +
        fadeOut(tween(THROUGH_OUT_MS, delayMillis = startMs, easing = LinearEasing))

private const val THROUGH_OUT_MS = 120
private const val THROUGH_IN_MS = 150
private const val THROUGH_IN_LAG_MS = 30

/** The player's cover wash fading into the lyrics' aurora and back. */
private const val LYRICS_WASH_FADE_MS = 300

private data class CapsuleTrigger(
    val wanted: Boolean,
    val windUp: Boolean,
    val leftTabs: Boolean,
    val isOpening: Boolean,
)

@Composable
fun MainLayout() {
    val navController = rememberNavController()
    val sheetCard = remember { SheetCard() }
    val sheetFlags = remember { SheetFlags(sheetCard) }
    // The dissolve back to the player lasts as long as the outgoing page's own exit (its start delay and its slide).
    LaunchedEffect(sheetCard.passing) {
        if (sheetCard.passing) {
            kotlinx.coroutines.delay((AuraMotion.PUSH_START_DELAY_MS + AuraMotion.PUSH_MS + 60).toLong())
            sheetCard.passing = false
        }
    }
    val cardOverPill by remember { derivedStateOf { sheetCard.coversPill } }
    // Every weight of the typeface is built once, off the first frame that would otherwise build it: a
    // variable font makes one instance per weight, and the player's first frame was where several of them
    // were first asked for.
    val fontResolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    LaunchedEffect(Unit) { runCatching { fontResolver.preload(com.ikko.aura.ui.theme.AuraFontFamily) } }
    // What the mini player refracts: the page itself, recorded once per frame and shared.
    val pillBackdrop = rememberLayerBackdrop()
    val pageMotion = rememberPageMotion(AuraDestination.topLevelRoutes.size)

    val capsule = remember { TabCapsuleState() }
    val capsuleViewModel: TabCapsuleViewModel = hiltViewModel()
    val selectedFilter by capsuleViewModel.selection.filter.collectAsStateWithLifecycle()
    // Records only the artwork backdrop, not the page on top of it — see LocalAuraBackdrop.
    val glassBackdrop = rememberLayerBackdrop()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    // Makes the player's ground ahead of time, so its opening has nothing to build.
    com.ikko.aura.ui.screens.nowplaying.PlayerGroundWarmer(playerViewModel)
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val amoledBackgroundEnabled by settingsViewModel.amoledBackgroundEnabled.collectAsStateWithLifecycle()
    // Narrowed on purpose. Collecting the whole PlayerUiState here subscribed MainLayout — and so
    // the entire NavHost under it — to `positionMs`, which ticks every 500ms during playback, so
    // the whole tree recomposed four times a second while scrolling. Only the backdrop's inputs
    // are read at this level; everything position-related stays inside PlayerPillHost.
    val backdrop by rememberBackdropState(playerViewModel)
    val coverColors = com.ikko.aura.ui.theme.rememberCoverColors(backdrop.theme.hue)
    // Set by a page that is lit with colours of its own; the playing track's are used otherwise.
    val pageTheme = remember { mutableStateOf<AlbumTheme?>(null) }
    var albumAuroraTheme by remember { mutableStateOf<AlbumTheme?>(null) }
    LaunchedEffect(pageTheme.value) {
        pageTheme.value?.let { albumAuroraTheme = it }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    sheetCard.topRoute = currentRoute
    // Never in a menu of the player: not in its own screens (the lyrics, the queue, the equaliser), and not in a page
    // reached from it (an album, an artist) — the player is still in the back stack under those, and the mini player,
    // which is the player folded away, has no business over the screen it is open on.
    val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
    val playerBelow = backStack.any { it.destination.route == AuraDestination.NowPlaying.route }
    val showPill = currentRoute !in AuraDestination.fullScreenRoutes && !playerBelow
    // `showPill` above flips the instant the route changes — a frame or more before the card has
    // even claimed `sheetCard.owner`, let alone risen. Anything downstream that keys off `showPill`
    // alone (the room reserved for the pill, and so the capsule's position above it) jumped on that
    // same frame: the capsule sliding at the very start of the open gesture, before `isOpening` below
    // was true anywhere else and could stop it.
    val isOpening = (currentRoute == AuraDestination.NowPlaying.route || sheetCard.owner != null) && !sheetCard.sinking
    var tabsShown by remember { mutableStateOf(true) }
    var isPlayerRoute by remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute) {
        tabsShown = currentRoute == AuraDestination.Tabs.route
        isPlayerRoute = currentRoute == AuraDestination.NowPlaying.route
    }
    LaunchedEffect(capsule, pageMotion, currentRoute) {
        // Whether the capsule is out, and if not, whether it was the list's scrolling that put it away
        // (which earns the wind-up) rather than the page leaving (which just sinks).
        snapshotFlow {
            val isTabs = currentRoute == AuraDestination.Tabs.route
            val isOpening = (currentRoute == AuraDestination.NowPlaying.route || sheetCard.owner != null) && !sheetCard.sinking
            val wanted = capsuleWanted(pageMotion.p, pageMotion.dragging, capsule.scrolledAway, isTabs)
            CapsuleTrigger(wanted, !wanted && capsule.scrolledAway && pageMotion.p in 0.95f..1.05f, !isTabs, isOpening)
        }
            .distinctUntilChanged()
            .collectLatest { (wanted, windUp, leftTabs, isOpening) ->
                if (isOpening) {
                    // The player opening must never animate the capsule away — no shrink, no slide
                    // down into the mini player's place, it just gets covered by the rising card.
                    // Freezes whatever is already in flight (e.g. a hide from scrolling, cut short
                    // by the tap) exactly where it is, rather than letting it finish or snapping it.
                    capsule.shown.stop()
                    capsule.inset.stop()
                } else if (currentRoute != AuraDestination.NowPlaying.route) {
                    launch { capsule.inset.animateTo(if (wanted) PageMotionSpec.CAPSULE_HEIGHT_DP + PageMotionSpec.CAPSULE_GAP_TO_PLAYER_DP else 0f, tween(300)) }
                    if (leftTabs && !wanted) capsule.shown.snapTo(0f) else capsule.shown.capsuleTo(wanted, windUp)
                }
            }
    }

    // When the player sheet is fully open, silently reset capsule.shown to 0f without animation
    // so that on closing it can execute its natural spring entrance back into place.
    LaunchedEffect(capsule) {
        snapshotFlow { sheetCard.openProgress >= 0.85f && !sheetCard.sinking }
            .distinctUntilChanged()
            .collect { fullyOpen ->
                if (fullyOpen && capsule.shown.value > 0f) {
                    capsule.shown.snapTo(0f)
                }
            }
    }
    val density = LocalDensity.current

    // Search: how far the page has receded to make room for it, 0 shut, 1 open.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // Open already when the activity came back with the search open (rotation, dark mode, locale): the open flag is
    // saved, the pull is not, and a search that is "open" at pull 0 is invisible and eats the first Back.
    val searchPull = remember { Animatable(if (searchOpen) 1f else 0f) }
    val scope = rememberCoroutineScope()

    fun openSearch() {
        if (searchOpen) return
        searchOpen = true
        scope.launch { searchPull.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) }
    }

    fun closeSearch() {
        searchOpen = false
        scope.launch { searchPull.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
    }

    BackHandler(enabled = searchOpen) { closeSearch() }

    // pillHeightPx removed to avoid mid-animation recomposition
    // The navigation bar is part of what a list has to clear: pages run beneath it now, so the
    // last row needs the bar's height as well as the pill's to be reachable.
    val navigationBarHeight = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val reservedPillHeight = navigationBarHeight +
        if ((showPill || isOpening) && backdrop.hasTrack) 76.dp else 0.dp

    Surface(color = AuraColors.Black, modifier = Modifier.fillMaxSize()) {
        PermissionGate {
        CompositionLocalProvider(
            LocalNowPlayingMesh provides backdrop.meshColors,
            com.ikko.aura.ui.theme.LocalAmoledBackgroundEnabled provides amoledBackgroundEnabled,
            LocalPlayerPillHeight provides reservedPillHeight,
            LocalAuraBackdrop provides glassBackdrop,
            LocalSearchOpener provides ::openSearch,
            LocalPageTheme provides pageTheme,
            com.ikko.aura.ui.theme.LocalCoverColors provides coverColors,
            LocalPageMotion provides pageMotion,
            // The page darkens as the card rises over it and lightens as it sinks — tied to the card rather
            // than run on a clock of its own, which put a change of brightness on the screen that belonged to
            // nothing one could see moving.
            LocalSheetDim provides { sheetCard.dim },
            LocalSheetCard provides sheetCard,
            LocalTabCapsule provides capsule,
        ) {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                // Seen, not taken: it only tells the aurora that somebody is using the screen (see AuroraPace).
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        com.ikko.aura.ui.components.AuroraPace.touched()
                    }
                }
            },
        ) {
        // Outside the page's recorded layer: the background is its own surface, not something the
        // mini player has to re-process when it changes. The pill loses nothing it could show — what
        // it refracts is the page, and the background under it is smooth colour.
        val isAlbumRoute = currentRoute?.let {
            it.startsWith("album/") || it.startsWith("ytm_album/") || it.startsWith("remote_album/")
        } == true
        val albumAuroraScrim by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isAlbumRoute) 0f else 1f,
            animationSpec = if (isAlbumRoute) {
                tween(
                    durationMillis = com.ikko.aura.ui.theme.AuraMotion.PUSH_MS,
                    delayMillis = com.ikko.aura.ui.theme.AuraMotion.PUSH_START_DELAY_MS,
                    easing = androidx.compose.animation.core.LinearOutSlowInEasing,
                )
            } else {
                tween(
                    durationMillis = com.ikko.aura.ui.theme.AuraMotion.PUSH_MS,
                    delayMillis = com.ikko.aura.ui.theme.AuraMotion.POP_START_DELAY_MS,
                    easing = androidx.compose.animation.core.FastOutLinearInEasing,
                )
            },
            label = "album-aurora-scrim",
        )
        val albumAuroraVisible = !amoledBackgroundEnabled || (albumAuroraTheme != null && (isAlbumRoute || albumAuroraScrim < 1f))
        if (albumAuroraVisible) {
            val currentTheme = if (amoledBackgroundEnabled) {
                albumAuroraTheme ?: backdrop.theme
            } else {
                if (isAlbumRoute || albumAuroraScrim == 0f) {
                    pageTheme.value ?: albumAuroraTheme ?: backdrop.theme
                } else {
                    pageTheme.value ?: backdrop.theme
                }
            }
            AuroraBackground(
                theme = currentTheme,
                active = currentRoute !in AuraDestination.fullScreenRoutes && (!amoledBackgroundEnabled || isAlbumRoute || albumAuroraScrim < 1f),
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (amoledBackgroundEnabled && albumAuroraScrim > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = albumAuroraScrim))
            )
        }
        // Clipped to the card while it travels: the cover's wash is the player's own background, so above the
        // card's top edge the page keeps the plain one it always has. Drawn full-screen through the whole
        // travel instead, it darkened the page under the card and then lifted again — a dip in brightness
        // that reads as a blink at both ends of the animation.
        val washClip = remember { androidx.compose.ui.graphics.Path() }
        // Between the player and its lyrics the cover's wash cross-fades with the aurora the lyrics sit on, both
        // ways, rather than cutting: it came on at once going back to the player and was dropped outright once the
        // player had left for the lyrics. Only there — opening and closing the player keep their own timing.
        val onPlayerScreen = currentRoute in AuraDestination.fullScreenRoutes
        // The route before this one, for the way back from the lyrics or the queue: there the cover's wash is already on
        // screen and must stay, whole, under the dissolve. Treated as the opening of the player (1% until the card is
        // claimed, then clipped to the card), it dropped out for the length of the player's first frames and the
        // background fell dark — the big blink on the way back — before rising again.
        val routeTrail = remember { object { var last: String? = null; var before: String? = null } }
        if (currentRoute != routeTrail.last) { routeTrail.before = routeTrail.last; routeTrail.last = currentRoute }
        val fadingRoute = currentRoute?.startsWith("album/") == true || currentRoute?.startsWith("artist/") == true
        val washFade by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (fadingRoute) 0f else 1f,
            animationSpec = tween(LYRICS_WASH_FADE_MS, easing = LinearEasing),
            label = "lyrics-wash-fade",
        )
        AlbumArtBackground(
            artworkUri = backdrop.artworkUri,
            artworkKey = backdrop.artworkKey,
            fallbackColors = backdrop.meshColors,
            // For as long as the card exists, not only while the route says "player": closing pops the route
            // at the start of the gesture, before the card has moved, and the cover went (and the aurora came
            // back behind the glass) while the card was still full screen — the flash on closing.
            forceArtwork = onPlayerScreen || sheetCard.owner != null,
            // Recorded around the settled backdrop only, not around the whole thing: the cover on
            // its way out is drawn above this and deliberately outside the record. See the
            // parameter's own note for what that buys.
            // Under the battery saver the glass is flat and samples nothing, so there is nothing to record for it.
            settledModifier = Modifier.layerBackdrop(
                glassBackdrop,
                // The player glass has already captured its resting picture before the sheet
                // starts moving. Do not re-record the artwork source while that picture is frozen.
                // Off: the player draws its own ground (the album aurora under the cover) and records that instead — see
                // PlayerCoverGround. Two things recording into one layer would fight over it.
                enabled = false,
            ),
            modifier = Modifier
                .fillMaxSize()
                // Inside the card's own shape — its width and its rounded corners — rather than everything
                // below its top edge: a straight cut across the whole width showed the wash (or black) beside
                // the narrower card and in its corners. At 1% while the card is still getting ready, like the
                // card itself: rendered, so the glass has it to sample, but unseen under the mini player.
                // Also at 1% in the frame or two between the tap and the player's first composition, when the route
                // is already the player's but no card has been claimed: with no card to clip to, the wash was drawn
                // across the whole screen at full strength — the cover's blur flashing over the aurora on opening.
                .graphicsLayer {
                    val playerNotYetThere = sheetCard.owner == null && currentRoute == AuraDestination.NowPlaying.route
                    // The player's other screens sit on the same cover whole, with or without the card.
                    val comingBack = currentRoute == AuraDestination.NowPlaying.route &&
                        routeTrail.before in AuraDestination.fullScreenRoutes &&
                        routeTrail.before != AuraDestination.NowPlaying.route &&
                        (sheetCard.passing || sheetCard.owner == null)
                    val onOwnScreen = (onPlayerScreen && currentRoute != AuraDestination.NowPlaying.route) || comingBack
                    alpha = if (onOwnScreen) washFade else (if (sheetCard.waitingToShow || playerNotYetThere) 0.01f else sheetCard.cardAlpha) * washFade
                }
                .drawWithContent {
                    // No card and not the player: the cover has nothing to fill. Its own fade-out would
                    // otherwise run across the whole screen, over the page, once the card has gone. Except on
                    // the way to the lyrics, until it has faded out there.
                    val isFadingRoute = fadingRoute && washFade > 0f
                    if (sheetCard.owner == null && !onPlayerScreen && !isFadingRoute) return@drawWithContent
                    val edge = sheetCard.cardTop
                    val comingBack = currentRoute == AuraDestination.NowPlaying.route &&
                        routeTrail.before in AuraDestination.fullScreenRoutes &&
                        routeTrail.before != AuraDestination.NowPlaying.route &&
                        (sheetCard.passing || sheetCard.owner == null)
                    // The player's card carries its own opaque ground (the cover and the aurora, see PlayerCoverGround) over every
                    // pixel this would be drawn on: drawn anyway, it was a full-screen, panning, blurred picture per frame of the
                    // card's travel for nothing. It is still the background of the player's own screens (lyrics, queue, equaliser).
                    if (currentRoute == AuraDestination.NowPlaying.route && !comingBack) return@drawWithContent
                    if (edge <= 0f || comingBack || (onPlayerScreen && currentRoute != AuraDestination.NowPlaying.route)) {
                        drawContent()
                        return@drawWithContent
                    }
                    setToCard(washClip, edge, size, AuraMotion.cornerAt(edge).toPx())
                    clipPath(washClip) { this@drawWithContent.drawContent() }
                },
        )
        // Recorded only while something is there to refract it. This records the whole page into a layer
        // every time the page draws, and while the player's card is over the mini player and the tab capsule
        // — the only two things that sample it — that was a full-screen recording per frame of the card's
        // travel, for a picture nobody was looking through.
        // And never under the battery saver: its glass is a flat fill that samples no backdrop, so recording the whole
        // page into a layer every frame of every transition was work for a picture nobody could look through.
        // And in the frames of an opening from the pill before the player has claimed its card: the route is already the
        // player's (so `showPill` is false) and nothing owns the card yet, but the mini player is still on screen and is a
        // glass that samples this recording. Not recorded there, it drew nothing — it went out for those frames, came
        // back when the card was claimed, and went out again under the card: the blink on the first opening.
        val playerOpening = currentRoute == AuraDestination.NowPlaying.route && sheetCard.owner == null
        val pageBackdropNeeded = (showPill || playerOpening || (sheetCard.owner != null && !cardOverPill)) && !sheetCard.frozen
        // A layer of its own, before the recording. Without one, the page shares its picture with everything
        // drawn beside it — the mini player, the tab capsule — so each frame of their own arrival had the
        // whole page recorded again for a backdrop whose content had not changed.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { }
                .layerBackdrop(pillBackdrop, enabled = pageBackdropNeeded),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The page recedes rather than being covered: it shrinks a little and slides down
                // under the panel, which is what makes the search read as arriving *over* what you
                // were doing instead of replacing it.
                .graphicsLayer {
                    val p = searchPull.value
                    if (p == 0f) return@graphicsLayer
                    // Depth, not travel: the page steps back (smaller, softer) and stays where it is — nothing slides.
                    val shrink = 1f - PAGE_RECEDE * p
                    scaleX = shrink
                    scaleY = shrink
                    // Blurred as it recedes, in step with the pull: nothing at the start of the gesture, the full
                    // radius once the search is open. The page goes soft behind the panel instead of staying sharp
                    // under it, which is what makes the panel and its results legible.
                    val blurPx = SEARCH_BLUR_DP.dp.toPx() * p
                    renderEffect = if (blurPx >= 1f) {
                        androidx.compose.ui.graphics.BlurEffect(blurPx, blurPx, androidx.compose.ui.graphics.TileMode.Clamp)
                    } else {
                        null
                    }
                },
        ) {
            NavHost(
                navController = navController,
                startDestination = AuraDestination.Tabs.route,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                // Two vocabularies left, now that the five spaces are pages of one pager rather
                // than five destinations: the player rises as a sheet, everything else is pushed.
                enterTransition = {
                    when {
                        isSheet(targetState) -> { sheetFlags.opening = true; sheetFlags.away = false; AuraMotion.sheetEnter }
                        isLyricsOrAlbum(targetState) && isSheet(initialState) -> throughEnter(fromLeft = false)
                        else -> pushEnter()
                    }
                },
                exitTransition = {
                    if (isSheet(initialState)) { sheetFlags.closing = false; sheetFlags.away = true }
                    when {
                        isSheet(targetState) -> AuraMotion.sheetUnderExit
                        isLyricsOrAlbum(targetState) && isSheet(initialState) -> throughExit(toLeft = true, startMs = AuraMotion.PUSH_START_DELAY_MS)
                        isSheet(initialState) -> fadeOut(tween(com.ikko.aura.ui.theme.AuraMotion.PUSH_MS, delayMillis = com.ikko.aura.ui.theme.AuraMotion.PUSH_START_DELAY_MS))
                        else -> pushExit()
                    }
                },
                popEnterTransition = {
                    if (isSheet(targetState)) { sheetFlags.opening = false; sheetFlags.away = false }
                    when {
                        isSheet(initialState) -> AuraMotion.sheetUnderEnter
                        isSheet(targetState) && isLyricsOrAlbum(initialState) -> { sheetCard.passing = true; throughEnter(fromLeft = true, startMs = AuraMotion.PUSH_START_DELAY_MS) }
                        isSheet(targetState) -> fadeIn(tween(com.ikko.aura.ui.theme.AuraMotion.PUSH_MS, delayMillis = com.ikko.aura.ui.theme.AuraMotion.POP_START_DELAY_MS))
                        else -> popEnter()
                    }
                },
                popExitTransition = {
                    if (isSheet(initialState)) sheetFlags.closing = !isLyricsOrAlbum(targetState)
                    when {
                        isSheet(initialState) -> AuraMotion.sheetExit
                        isSheet(targetState) && isLyricsOrAlbum(initialState) -> throughExit(toLeft = false, startMs = AuraMotion.PUSH_START_DELAY_MS)
                        else -> popExit()
                    }
                },
            ) {
                composable(AuraDestination.Settings.route) {
                    UnderSheet { SettingsScreen(navController) }
                }
                composable(AuraDestination.Tabs.route) {
                    UnderSheet {
                        Box(Modifier.fillMaxSize()) {
                            TabPager(navController = navController, playerViewModel = playerViewModel, motion = pageMotion)
                        }
                    }
                }
                composable(AuraDestination.Queue.route) {
                    UnderSheet { QueueScreen(navController = navController, playerViewModel = playerViewModel) }
                }
                composable(AuraDestination.Lyrics.route) {
                    // While the page slides in or out the screen only has its background and buttons to draw; the
                    // lyrics themselves come in once it has settled (see LyricsScreen).
                    val sliding = transition.currentState != androidx.compose.animation.EnterExitState.Visible ||
                        transition.targetState != androidx.compose.animation.EnterExitState.Visible
                    UnderSheet { LyricsScreen(navController = navController, playerViewModel = playerViewModel, isTransitioning = sliding) }
                }
                composable(AuraDestination.Equalizer.route) { UnderSheet { EqualizerScreen(navController) } }
                composable(AuraDestination.NowPlaying.route) {
                    // Whether this screen is coming up from the mini player, and whether it is going back down to
                    // it, as the transitions above decided when they were worked out.
                    val opening = remember { sheetFlags.fromPill }
                    val leaving = transition.targetState == androidx.compose.animation.EnterExitState.PostExit
                    NowPlayingScreen(
                        navController = navController,
                        viewModel = playerViewModel,
                        sheetOpening = opening,
                        sheetClosing = leaving && sheetFlags.closing,
                        leavingSideways = leaving && !sheetFlags.closing,
                        sheetCard = sheetCard,
                    )
                }
                composable(AuraDestination.AlbumPattern.route) { backStack ->
                    val sliding = transition.currentState != androidx.compose.animation.EnterExitState.Visible ||
                        transition.targetState != androidx.compose.animation.EnterExitState.Visible
                    UnderSheet {
                        AlbumScreen(
                            albumId = backStack.arguments?.getString("albumId").orEmpty(),
                            navController = navController,
                            playerViewModel = playerViewModel,
                            isTransitioning = sliding,
                        )
                    }
                }
                composable(AuraDestination.ArtistPattern.route) { backStack ->
                    UnderSheet {
                        ArtistScreen(
                            artistId = backStack.arguments?.getString("artistId").orEmpty(),
                            navController = navController,
                            playerViewModel = playerViewModel,
                        )
                    }
                }
                composable(AuraDestination.PlaylistPattern.route) { backStack ->
                    UnderSheet {
                        PlaylistScreen(
                            playlistId = backStack.arguments?.getString("playlistId").orEmpty(),
                            navController = navController,
                            playerViewModel = playerViewModel,
                        )
                    }
                }
                composable(
                    AuraDestination.YtmAlbumPattern.route,
                    arguments = listOf(androidx.navigation.navArgument("online") { type = androidx.navigation.NavType.BoolType; defaultValue = false }),
                ) {
                    UnderSheet {
                        YtmAlbumScreen(navController = navController, playerViewModel = playerViewModel)
                    }
                }
                composable(AuraDestination.YtmArtistPattern.route) {
                    UnderSheet {
                        YtmArtistScreen(navController = navController, playerViewModel = playerViewModel)
                    }
                }
                composable(
                    AuraDestination.RemoteAlbumPattern.route,
                    arguments = listOf(androidx.navigation.navArgument("online") { type = androidx.navigation.NavType.BoolType; defaultValue = false }),
                ) {
                    UnderSheet {
                        RemoteAlbumScreen(navController = navController, playerViewModel = playerViewModel)
                    }
                }
                composable(AuraDestination.RemoteArtistPattern.route) {
                    UnderSheet {
                        RemoteArtistScreen(navController = navController, playerViewModel = playerViewModel)
                    }
                }
                composable(AuraDestination.SourceOrder.route) {
                    UnderSheet {
                        SourceOrderScreen()
                    }
                }
                composable(AuraDestination.AlbumListPattern.route) { backStack ->
                    UnderSheet {
                        AlbumListScreen(
                            mode = backStack.arguments?.getString("mode").orEmpty(),
                            navController = navController,
                        )
                    }
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
                    open = searchOpen,
                    onDismiss = ::closeSearch,
                    navController = navController,
                    playerViewModel = playerViewModel,
                )
            }

            // Not switched on and off with the route. Removed the instant the player started
            // rising it blinked out a few frames before the sheet covered it; returned the instant
            // the player started leaving it sat on top of the interface still clearing. It now
            // leaves quickly, and comes back once the sheet has mostly gone.
            val pillAlpha = remember { Animatable(if (showPill) 1f else 0f) }
            LaunchedEffect(showPill) {
                if (showPill) {
                    if (sheetCard.owner != null) {
                        pillAlpha.snapTo(1f)
                    } else {
                        delay(PILL_RETURN_DELAY_MS)
                        pillAlpha.animateTo(1f, tween(PILL_FADE_IN_MS))
                    }
                } else {
                    // Gone in the same frame the card appears over it, as in Apple's: no fade to show through.
                    pillAlpha.snapTo(0f)
                }
            }
            // Keyed on showPill: the lambda captures it by value, so without the key it kept the value
            // it had when first composed (true) for good — the pill stayed composed after it had
            // faded out, invisible and still catching touches over the player's bottom edge.
            // The pill's arrival: when a track starts and there was no pill, it comes in the way the tab
            // capsule does — a spring with a little overshoot, rising, growing and stretching with its own
            // speed. Reading `arrival` only in a graphics layer, so it costs no recomposition.
            val isNowPlayingRoute = currentRoute == AuraDestination.NowPlaying.route
            val isTabsRoute = currentRoute == AuraDestination.Tabs.route
            val hasPill = (showPill || isNowPlayingRoute || sheetCard.owner != null) && backdrop.hasTrack
            val arrival = remember { Animatable(if (backdrop.hasTrack) 1f else 0f) }
            LaunchedEffect(backdrop.hasTrack) {
                if (backdrop.hasTrack) arrival.capsuleTo(true) else arrival.capsuleTo(false, windUp = false)
            }
            // The capsule sits on the pill, so it moves with the room the pill takes rather than jumping.
            val capsuleBottomDp by androidx.compose.animation.core.animateDpAsState(
                targetValue = capsuleBottom(reservedPillHeight, navigationBarHeight, hasPill),
                animationSpec = androidx.compose.animation.core.spring(
                    PageMotionSpec.CAPSULE_SHOW_DAMPING, PageMotionSpec.CAPSULE_SHOW_STIFFNESS,
                ),
                label = "capsule-bottom",
            )
            // Composed while there is something of it to show: any value above rest, or an animation on its way up. An
            // animation that only holds it at 0 — the one run when the player returns to a page the capsule does not
            // belong to — used to count, and composed an invisible capsule (alpha 0) in the very frame the closing card
            // uncovered the mini player: that frame's missed deadline, measured on every close from the home page.
            val pillGlassSettle = remember { GlassSettle() }
            pillGlassSettle.frozen = sheetCard.frozen
            LaunchedEffect(sheetCard.frozen) {
                pillGlassSettle.frozen = sheetCard.frozen
            }

            val capsuleEnabled = isTabsRoute && !cardOverPill && !searchOpen

            // How visible the capsule is, from the route, the search and the player's card. Read by its layer and by its
            // drawing, so both follow the same states.
            fun capsuleAlpha(): Float {
                val isTabs = currentRoute == AuraDestination.Tabs.route
                val isNowPlaying = currentRoute == AuraDestination.NowPlaying.route
                return searchClear(searchPull.value, !searchOpen) * when {
                    !isTabs && !isNowPlaying -> 0f
                    // The route is the player's but its card has not been claimed yet: the first frames of an
                    // opening from the mini player, before the player has composed. The capsule is not part of
                    // the opening, so it stays exactly as it was until the card covers it — as the mini player
                    // does in the same frames (see `waiting` below). Only for an opening from the pill: a
                    // player coming back from the lyrics or the queue has nothing to hold the capsule for.
                    sheetCard.owner == null && isNowPlaying && sheetFlags.fromPill -> 1f
                    sheetCard.owner == null -> if (isTabs) 1f else 0f
                    sheetCard.waitingToShow || (isNowPlaying && !sheetCard.ready) -> 1f
                    // A narrow ramp on opening: a handful of frames of blend right at the cut, not
                    // dozens. Closing is untouched (still the hard cut).
                    !sheetCard.sinking -> ((0.22f - sheetCard.openProgress) / 0.05f).coerceIn(0f, 1f)
                    else -> if (sheetCard.edge > 0.72f) 1f else 0f
                }
            }

            CompositionLocalProvider(LocalGlassSettle provides pillGlassSettle) {
                TabCapsule(
                    state = capsule,
                    selected = selectedFilter,
                    onSelect = capsuleViewModel.selection::select,
                    backdrop = pillBackdrop,
                    enabled = capsuleEnabled,
                    onSearchClick = ::openSearch,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(horizontal = 12.dp)
                        .offset { IntOffset(0, -capsuleBottomDp.roundToPx()) }
                        .graphicsLayer {
                            val targetAlpha = capsuleAlpha()
                            if (targetAlpha <= 0f) {
                                alpha = 0f
                                // Not only unseen but out of reach: an invisible capsule is still composed, and its touch handling
                                // is still in the way of whatever is drawn under it — on the player it sat over the playback
                                // controls and swallowed the touches that landed on them (every press in its band was lost).
                                // Moved off the screen, it can be hit by nothing.
                                translationY = CAPSULE_PARKED_PX
                                return@graphicsLayer
                            }
                            alpha = targetAlpha
                        }
                        // And not drawn at all then: at alpha 0 its glass was still part of every frame, its offscreen layers
                        // (blur, lens) re-rendered each time anything moved — under the player, the lyrics and the queue.
                        .drawWithContent { if (capsuleAlpha() > 0f) drawContent() },
                )
                // The player is being opened but has not claimed the card yet: the route changes a frame or more
                // before the player's first composition, and the mini player stays until the card replaces it.
                val playerComing = currentRoute == AuraDestination.NowPlaying.route
                // The pill belongs to the pages and to the player's own card. On the lyrics, the queue and the equalizer it
                // does not exist: the player leaving for one of them keeps the card (and so `owner`) until its exit
                // transition is over — about a second — with the card's edge put back at the pill, and the rules below for
                // a card in use drew the pill in full, over the lyrics, for that whole second.
                val pillRouteOk = showPill || playerComing
                val pillComposed by remember(showPill, currentRoute) {
                    derivedStateOf {
                        showPill ||
                            pillAlpha.value > 0f ||
                            sheetCard.waitingToShow ||
                            (playerComing && sheetCard.owner == null) ||
                            (sheetCard.owner != null && !sheetCard.coversPill && pillRouteOk)
                    }
                }
                if (pillComposed) {
                    val pillInteractive = showPill && sheetCard.owner == null && !searchOpen
                    PlayerPillHost(
                        playerViewModel = playerViewModel,
                        backdrop = pillBackdrop,
                        enabled = pillInteractive,
                        // The finger is still on the pill: start building the player now. It opens exactly as it does for
                        // a tap (the same navigation, the same hidden warm-up under the pill); it is just earlier.
                        onPrewarm = {
                            val playerOnTop = navController.currentBackStackEntry?.destination?.route == AuraDestination.NowPlaying.route
                            if (!playerOnTop && sheetCard.acceptsOpen) {
                                sheetFlags.openingFromPill()
                                navController.navigate(AuraDestination.NowPlaying.route) { launchSingleTop = true }
                            }
                        },
                        // The press became something else before the player had shown: let it go, unseen. Past that point
                        // the player is already on its way up and is left to finish.
                        onPrewarmCancel = {
                            val playerOnTop = navController.currentBackStackEntry?.destination?.route == AuraDestination.NowPlaying.route
                            if (playerOnTop && !sheetCard.ready) navController.popBackStack()
                        },
                        onOpenNowPlaying = {
                            // Once per opening. The mini player stays on screen (and under the finger) until the player's card
                            // replaces it, and every further tap navigated to the player again: launchSingleTop still swaps
                            // the entry for a new one, which tore the player down and built it again from the start — the
                            // card went back to the pill each time, and for a frame there was no card at all, so the
                            // cover's wash showed across the whole screen. Read here, at the tap, not captured.
                            val playerOnTop = navController.currentBackStackEntry?.destination?.route == AuraDestination.NowPlaying.route
                            if (!playerOnTop && sheetCard.acceptsOpen) {
                                sheetFlags.openingFromPill()
                                navController.navigate(AuraDestination.NowPlaying.route) { launchSingleTop = true }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .graphicsLayer {
                                // Kept, as it is, while the card waits for the player to draw: it goes in the
                                // frame the card comes in (see SheetCard.ready), so the two never leave a gap.
                                // "The player is coming, the card has not been claimed yet" holds the pill only for a player opened
                                // from the pill (sheetFlags.opening). Coming back from the lyrics or the queue it is the same
                                // moment — the route has changed, the screen is still being composed — and the pill was drawn
                                // in full for all of it.
                                val waiting = sheetCard.waitingToShow ||
                                    (playerComing && sheetCard.owner == null && sheetFlags.fromPill)
                                val pAlpha = when {
                                    !pillRouteOk -> 0f
                                    waiting -> 1f
                                    sheetCard.owner != null -> {
                                        if (sheetCard.sinking) {
                                            // On closing: card clears via cardAlpha; keep mini player opaque (1f) once card is near pill,
                                            // avoiding any semi-transparent phase that exposes the underlying list.
                                            if (sheetCard.coversPill) 0f else 1f
                                        } else {
                                            // On opening: narrow ramp, same as the capsule's fade above.
                                            if (sheetCard.coversPill || (sheetCard.edge >= 1f && playerComing)) 0f
                                            else ((sheetCard.edge - 0.75f) / 0.05f).coerceIn(0f, 1f)
                                        }
                                    }
                                    showPill -> pillAlpha.value
                                    else -> 0f
                                }
                                if (pAlpha <= 0f) {
                                    alpha = 0f
                                    return@graphicsLayer
                                }
                                val a = arrival.value
                                val speed = if (arrival.isRunning) {
                                    min(abs(arrival.velocity) / PageMotionSpec.CAPSULE_STRETCH_FULL_AT, 1f)
                                } else {
                                    0f
                                }
                                val scale = PageMotionSpec.CAPSULE_SCALE_FROM + (1f - PageMotionSpec.CAPSULE_SCALE_FROM) * a
                                scaleX = scale * (1f - PageMotionSpec.CAPSULE_STRETCH_X * speed)
                                scaleY = scale * (1f + PageMotionSpec.CAPSULE_STRETCH_Y * speed)
                                translationY = (1f - a) * PageMotionSpec.CAPSULE_RISE_DP.dp.toPx()
                                alpha = pAlpha * (1.6f * a).coerceIn(0f, 1f) * searchClear(searchPull.value, !searchOpen)
                            }
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
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
    val theme: AlbumTheme,
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
                    theme = it.albumTheme,
                    hasTrack = it.currentTrack != null,
                )
            }
            .distinctUntilChanged()
    }
    return flow.collectAsStateWithLifecycle(
        BackdropState(null, null, AuraColors.DefaultMesh, AuraColors.DefaultPrimary, AlbumTheme.Neutral, false),
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
    backdrop: LayerBackdrop,
    onOpenNowPlaying: () -> Unit,
    onPrewarm: () -> Unit,
    onPrewarmCancel: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val track = uiState.currentTrack ?: return

    CompositionLocalProvider(LocalAuraBackdrop provides backdrop) {
        FloatingPlayerPill(
            track = track,
            queueIndex = uiState.queueIndex,
            isPlaying = uiState.isPlaying,
            onTogglePlay = playerViewModel::togglePlayPause,
            onNext = playerViewModel::skipNext,
            onPrevious = playerViewModel::skipPrevious,
            onOpen = onOpenNowPlaying,
            onPrewarm = onPrewarm,
            onPrewarmCancel = onPrewarmCancel,
            modifier = modifier,
            enabled = enabled,
        )
    }
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
private fun TabPager(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    motion: PageMotionState,
) {
    // Kept across recompositions rather than rebuilt: a new list of new lambdas each time this ran made the pager
    // see three changed pages. It seldom re-runs (its inputs never change identity), so this only keeps the list's
    // identity stable when it does.
    val pages = remember(navController, playerViewModel) {
        AuraDestination.topLevelRoutes.map { destination ->
            @Composable {
                when (destination) {
                    AuraDestination.Home ->
                        HomeScreen(navController = navController, playerViewModel = playerViewModel)
                    AuraDestination.Library ->
                        LibraryScreen(navController = navController, playerViewModel = playerViewModel)
                    else -> Unit
                }
            }
        }
    }
    PagedContainer(
        state = motion,
        pages = pages,
    )
}

/**
 * The search panel, and the page receding behind it.
 *
 * Modelled on iOS Spotlight: the page blurs, darkens and draws back, and the field arrives over
 * it. The one deliberate difference is the direction — the field comes down from the top edge,
 * because that is the edge the gesture starts from and a panel that arrived from anywhere else
 * would not feel connected to the finger that asked for it.
 *
* It is faded in by alpha rather than by animating a blur radius: a radius that changes rebuilds the
 * whole effect pipeline on every frame, which is the mistake the animated backdrop made before it
 * was measured.
 *
 * [progressProvider] is read in draw and layer blocks only, so dragging repaints and never
 * recomposes the results underneath.
 */
@Composable
private fun SearchOverlay(
    progressProvider: () -> Float,
    open: Boolean,
    onDismiss: () -> Unit,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progressProvider() }
            // Hardly a scrim: the blur and the depth do the work, and a dark plate over the page was what made the
            // search read as a spotlight.
            .background(SEARCH_SCRIM)
            // Tapping what is left of the page puts it back — the same way the gesture came.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            // Above the keyboard: the panel is the height that is left, so the prompt is centred in what can be seen
            // and not under the keys.
            .imePadding()
            .graphicsLayer {
                val p = progressProvider()
                alpha = p
                // Comes forward rather than down: a touch smaller to start, full size once open. No vertical travel.
                val scale = PANEL_ENTRY_SCALE + (1f - PANEL_ENTRY_SCALE) * p
                scaleX = scale
                scaleY = scale
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

/** How much the page shrinks and slides as it recedes. */
private const val PAGE_RECEDE = 0.05f

/** The blur of the page behind the search, at full pull. */
private const val SEARCH_BLUR_DP = 18f

/**
 * Laid over the page while the search is open. Not a blur of the page: the background behind the
 * window is a surface of its own that a blur cannot sample, and a scrim over the page alone printed
 * a dark plate across the aurora. A light scrim keeps the aurora and dims the page enough to read
 * the field over it.
 */
private val SEARCH_SCRIM = Color.Black.copy(alpha = 0.16f)

/** How big the panel is when it starts to arrive, against full size. */
private const val PANEL_ENTRY_SCALE = 0.96f

/** Rounded at the top only, in appearance — built as a rectangle with equal corners that hangs below the layer. */
private class TopRoundedClip(private val radius: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline = androidx.compose.ui.graphics.Outline.Rounded(
        androidx.compose.ui.geometry.RoundRect(
            left = 0f,
            top = 0f,
            right = size.width,
            bottom = size.height + 2f * radius,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
        ),
    )
}

/** Where the capsule is parked while it is not shown: far enough below the screen to be touched by nothing. */
private const val CAPSULE_PARKED_PX = 20_000f
