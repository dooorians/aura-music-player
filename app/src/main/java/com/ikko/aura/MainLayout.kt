package com.ikko.aura

import com.ikko.aura.ui.components.heroRipple
import android.net.Uri
import androidx.navigation.NavBackStackEntry
import com.ikko.aura.ui.theme.AuraMotion
import com.ikko.aura.ui.theme.CupertinoIcons
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalNowPlayingMesh
import com.ikko.aura.ui.theme.LocalPageTheme
import com.ikko.aura.ui.theme.LocalSearchOpener
import com.ikko.aura.ui.components.AuroraBackground
import com.ikko.aura.ui.components.LocalPageMotion
import com.ikko.aura.ui.components.PageSwitchPill
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

    /**
     * The finger is on the mini player and pulling the player up with it. The card then sits where the finger puts it
     * ([dragTo]) and the player waits to settle until the finger is let go.
     */
    var holding by mutableStateOf(false)

    /** Where the finger has put the card's top edge, as a fraction of the screen's height; read by the mini player riding with it. */
    var dragEdge by mutableFloatStateOf(1f)
        internal set

    /** The card's speed when the finger let go, in screen heights per second (negative is up); used once, by the next move. */
    var releaseVelocity: Float = 0f

    fun dragTo(edge: Float) {
        dragEdge = edge
        this.edge = edge
    }

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
        pageSettle?.frozen = true
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

    /** The NavBackStackEntry.id of the destination sitting directly beneath the open player. */
    var underEntryId: String? by mutableStateOf(null)
        internal set

    /** Optional page-wide GlassSettle synchronized immediately when [frozen] changes. */
    internal var pageSettle: GlassSettle? = null

    /** Set by the player while its card is going back down to the mini player (its Closing phase). */
    var sinking by mutableStateOf(false)
        internal set

    /**
     * How opaque the card is: fully, except on its way down, where it clears over the last of the way (from
     * SHEET_CLEAR_FROM to the mini player) as the pill takes its place. The cover's wash behind it clears with
     * it, and the page under it comes back through it, so nothing is left to appear at once when it has gone.
     */
    val cardAlpha: Float
        // Always whole: the docked mini player is an opaque bar the card sinks behind, so there is nothing to clear into.
        get() = 1f

    /** True while the sheet card is actively traveling (animating open or closed), so underlying backdrops and glass freeze. */
    private var _frozen by mutableStateOf(false)
    var frozen: Boolean
        get() = _frozen
        internal set(value) {
            _frozen = value
            pageSettle?.frozen = value || owner != null
        }

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
 * A destination that can sit under the player's card: recedes with a GPU-clipped top radius and dims as the card rises.
 *
 * The untransformed page content is recorded once into [pageSnapshot] when the sheet begins traveling and retained
 * while the player is open, so both the opening rise and the closing descent blit a single GPU texture without
 * re-drawing the page's list/frosted children or rasterizing CPU clipPaths.
 */
@Composable
private fun UnderSheet(content: @Composable () -> Unit) {
    val dim = LocalSheetDim.current
    val card = LocalSheetCard.current
    androidx.compose.runtime.DisposableEffect(card) {
        card.pagesComposed++
        onDispose { card.pagesComposed-- }
    }
    val myEntry = androidx.lifecycle.compose.LocalLifecycleOwner.current as? androidx.navigation.NavBackStackEntry
    val myRoute = myEntry?.destination?.route
    val myEntryId = myEntry?.id
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxShiftY = with(density) { 14.dp.toPx() }
    val maxCornerRadius = with(density) { 26.dp.toPx() }
    val pageSnapshot = rememberGraphicsLayer()
    val pageSnapshotState = remember { PageSnapshotState() }

    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                val isUnderPlayerEntry = if (card.underEntryId != null) {
                    myEntryId == card.underEntryId
                } else {
                    myRoute != null && myRoute != card.topRoute
                }
                // A page under the player, while the player leaves for or returns from another one: not drawn at all.
                if (card.owner != null && isUnderPlayerEntry && (card.leavingSideways || card.passing)) {
                    return@drawWithContent
                }
                val o = card.edge
                if (card.passing || card.owner == null || !card.ready || o >= 1f || card.cardAlpha <= 0f) {
                    if (card.owner == null || card.leavingSideways) {
                        pageSnapshotState.invalidate()
                    }
                    drawContent()
                    return@drawWithContent
                }
                drawContent()
            }
            .graphicsLayer { },
    ) {
        // Subtle depth effect: background page recedes, slides down slightly, and rounds its top corners.
        // Recording pageSnapshot inside this receding layer means content()'s layout coordinates never move
        // during the travel and the GPU scales/shifts the cached snapshot directly.
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
                        shape = TopRoundedClip(corner)
                        clip = true
                    }
                }
                .drawWithContent {
                    val snapshotSize = IntSize(size.width.toInt(), size.height.toInt())
                    val holdSnapshot = card.owner != null && !card.leavingSideways && !card.passing && card.ready
                    if (!holdSnapshot) {
                        pageSnapshotState.invalidate()
                        drawContent()
                        return@drawWithContent
                    }
                    if (card.frozen) {
                        if (!pageSnapshotState.isValid(snapshotSize)) {
                            pageSnapshot.record(snapshotSize) {
                                this@drawWithContent.drawContent()
                            }
                            pageSnapshotState.record(snapshotSize)
                        }
                        drawLayer(pageSnapshot)
                    } else {
                        drawContent()
                    }
                },
        ) {
            content()
        }

        // Full-screen dimming overlay: spans the full un-shifted bounds from y=0
        // all the way down to the player card, so the darkening covers
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


/** A page a trip of the hero ends on: an album, a playlist, an artist (of any source), the lyrics. */
private fun isHeroPageRoute(route: String?): Boolean {
    if (route == null) return false
    return route == AuraDestination.Lyrics.route || HERO_PAGE_PREFIXES.any { route.startsWith(it) }
}

private val HERO_PAGE_PREFIXES = listOf(
    "album/", "ytm_album/", "remote_album/", "playlist/", "artist/", "ytm_artist/", "remote_artist/",
)

private fun isAlbumRoute(entry: NavBackStackEntry): Boolean = isHeroPageRoute(entry.destination.route)

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
    // Every page the player opens over itself: the queue, the lyrics, an album or an artist of any source.
    return route == AuraDestination.Queue.route || isHeroPageRoute(route)
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

/** How long a page that a trip leaves from (or returns to) stays composed, for the trip's clocks to run. */
private const val HERO_KEEP_ALIVE_MS = 820

/** The player stepping aside for a page a trip of the hero goes to, and back over it. */
private const val PLAYER_STEP_ASIDE_MS = 180
private const val PLAYER_STEP_BACK_MS = 160
private const val PLAYER_LEAVE_MS = 200
private const val PLAYER_WAIT_FOR_PAGE_MS = 700L

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
    val pillGlassSettle = remember { GlassSettle() }
    val sheetCard = remember { SheetCard().also { it.pageSettle = pillGlassSettle } }
    sheetCard.pageSettle = pillGlassSettle
    val sheetFlags = remember { SheetFlags(sheetCard) }
    // The dissolve back to the player lasts as long as the outgoing page's own exit (its start delay and its slide).
    LaunchedEffect(sheetCard.passing) {
        if (sheetCard.passing) {
            kotlinx.coroutines.delay((AuraMotion.PUSH_START_DELAY_MS + AuraMotion.PUSH_MS + 60).toLong())
            sheetCard.passing = false
        }
    }
    // Every weight of the typeface is built once, off the first frame that would otherwise build it: a
    // variable font makes one instance per weight, and the player's first frame was where several of them
    // were first asked for.
    val fontResolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    LaunchedEffect(Unit) { runCatching { fontResolver.preload(com.ikko.aura.ui.theme.AuraFontFamily) } }
    // What the mini player refracts: the page itself, recorded once per frame and shared.
    val pillBackdrop = rememberLayerBackdrop()
    val immersiveBackdrop = rememberLayerBackdrop()
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
    // The navigation the listener chose: a button beside the mini player, or the pages swiped between.
    val pageSwitchButton by settingsViewModel.pageSwitchButton.collectAsStateWithLifecycle()
    androidx.compose.runtime.SideEffect { pageMotion.swipeEnabled = !pageSwitchButton }
    // Narrowed on purpose. Collecting the whole PlayerUiState here subscribed MainLayout — and so
    // the entire NavHost under it — to `positionMs`, which ticks every 500ms during playback, so
    // the whole tree recomposed four times a second while scrolling. Only the backdrop's inputs
    // are read at this level; everything position-related stays inside PlayerPillHost.
    val backdrop by rememberBackdropState(playerViewModel)
    val coverColors = com.ikko.aura.ui.theme.rememberCoverColors(backdrop.theme.hue)
    // Set by a page that is lit with colours of its own; the playing track's are used otherwise.
    val pageTheme = remember { mutableStateOf<AlbumTheme?>(null) }
    // The palette a page that is lit with one of its own asks the background for (Home's moods, an album, a card's playlist).
    val immersivePage = remember { mutableStateOf<com.ikko.aura.ui.components.ImmersivePalette?>(null) }
    // Remember the last Album/Playlist immersive palette so that when closing back to AMOLED black,
    // the page's own colours fade directly to black instead of switching to the playing track's palette mid-fade.
    val heldImmersive = remember { mutableStateOf<com.ikko.aura.ui.components.ImmersivePalette?>(null) }
    LaunchedEffect(immersivePage.value) {
        immersivePage.value?.let { heldImmersive.value = it }
    }
    var albumAuroraTheme by remember { mutableStateOf<AlbumTheme?>(null) }
    LaunchedEffect(pageTheme.value) {
        pageTheme.value?.let { albumAuroraTheme = it }
    }

    // Persistent player hosting state: opening and closing from the mini player never pushes or pops NavHost,
    // so neither the underlying page (Tabs/Album/Playlist) nor NowPlayingScreen is ever disposed or rebuilt.
    var playerOpen by remember { mutableStateOf(false) }
    var playerFromPill by remember { mutableStateOf(true) }
    var playerClosingToPill by remember { mutableStateOf(false) }
    var playerHostEntryId by remember { mutableStateOf<String?>(null) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentEntryId = backStackEntry?.id
    sheetCard.topRoute = currentRoute

    val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
    LaunchedEffect(backStack, playerHostEntryId, playerOpen) {
        if (playerOpen && playerHostEntryId != null && backStack.isNotEmpty() && backStack.none { it.id == playerHostEntryId }) {
            playerOpen = false
            playerClosingToPill = false
            playerHostEntryId = null
            sheetCard.underEntryId = null
            sheetFlags.away = false
        }
    }

    val playerLeavingSideways = playerOpen && playerHostEntryId != null && currentEntryId != null && currentEntryId != playerHostEntryId
    val playerBelow = playerLeavingSideways || backStack.any { it.destination.route == AuraDestination.NowPlaying.route }
    val showPill = currentRoute !in AuraDestination.fullScreenRoutes && !playerBelow

    fun openPlayerFromPill() {
        if (playerOpen && !playerClosingToPill && !playerLeavingSideways) return
        if (!sheetCard.acceptsOpen && !playerClosingToPill) return
        val hostId = navController.currentBackStackEntry?.id
        playerHostEntryId = hostId
        sheetCard.underEntryId = hostId
        playerFromPill = true
        playerClosingToPill = false
        playerOpen = true
        sheetFlags.openingFromPill()
    }

    val rootView = androidx.compose.ui.platform.LocalView.current
    val hero = remember { com.ikko.aura.ui.components.HeroState() }
    val playerSidewaysOffset = remember { Animatable(0f) }
    val playerSidewaysAlpha = remember { Animatable(1f) }
    // The player steps aside for a page it opens (the queue, the lyrics, an album, an artist) and comes back over it.
    // It fades in place: it used to slide a quarter of the width as well, and on the way back that left a quarter of the
    // screen uncovered on the right (a black band) while it came in. When a trip of the hero goes with it (its cover,
    // title, artist travel), it goes and comes at once, on the copy's clock, instead of waiting out the pages' own delay.
    LaunchedEffect(playerOpen, playerLeavingSideways) {
        playerSidewaysOffset.snapTo(0f)
        if (!playerOpen) {
            playerSidewaysAlpha.snapTo(1f)
        } else if (playerLeavingSideways) {
            val withHero = hero.fromPlayer && hero.phase == com.ikko.aura.ui.components.HeroPhase.Opening
            // The page it goes to is built in a few frames: the player stays until the trip is under way (the page has
            // said where things go), or the window's black showed between the two.
            if (withHero) {
                kotlinx.coroutines.withTimeoutOrNull(PLAYER_WAIT_FOR_PAGE_MS) {
                    while (!(hero.running || hero.phase != com.ikko.aura.ui.components.HeroPhase.Opening)) withFrameNanos { }
                }
            }
            playerSidewaysAlpha.animateTo(
                targetValue = 0f,
                animationSpec = if (withHero) {
                    tween(PLAYER_STEP_ASIDE_MS, easing = com.ikko.aura.ui.components.TabMotion.Exit)
                } else {
                    // Out only once the page is coming in under it, and a little slower than it: never the bare window.
                    tween(PLAYER_LEAVE_MS, delayMillis = AuraMotion.PUSH_START_DELAY_MS + THROUGH_IN_LAG_MS, easing = LinearEasing)
                },
            )
        } else if (playerSidewaysAlpha.value < 1f) {
            // Back at once, over the page still leaving: waiting for the page to have gone first left the window's
            // black between the two.
            playerSidewaysAlpha.animateTo(1f, tween(PLAYER_STEP_BACK_MS, easing = FastOutSlowInEasing))
        }
    }
    // The player is all there again (not on its way back over a page): what decides that the page under it is cut for the
    // dock. Cut while the player was still fading in, the page jumped and a black strip showed through at its foot.
    val playerFullyBack by remember { derivedStateOf { playerSidewaysAlpha.value >= 1f } }

    if (!CAPSULE_HIDDEN) {
        LaunchedEffect(capsule, pageMotion, currentRoute, playerOpen, playerClosingToPill) {
            snapshotFlow {
                val isTabs = currentRoute == AuraDestination.Tabs.route && (!playerOpen || playerClosingToPill)
                val isOpening = (playerOpen || sheetCard.owner != null) && !sheetCard.sinking
                val wanted = capsuleWanted(pageMotion.p, pageMotion.dragging, capsule.scrolledAway, isTabs)
                CapsuleTrigger(wanted, !wanted && capsule.scrolledAway && pageMotion.p in 0.95f..1.05f, !isTabs, isOpening)
            }
                .distinctUntilChanged()
                .collectLatest { (wanted, windUp, leftTabs, isOpening) ->
                    if (isOpening) {
                        capsule.shown.stop()
                        capsule.inset.stop()
                    } else if (!playerOpen || playerClosingToPill) {
                        launch { capsule.inset.animateTo(if (wanted) PageMotionSpec.CAPSULE_HEIGHT_DP + PageMotionSpec.CAPSULE_GAP_TO_PLAYER_DP else 0f, tween(300)) }
                        if (leftTabs && !wanted) capsule.shown.snapTo(0f) else capsule.shown.capsuleTo(wanted, windUp)
                    }
                }
        }

        LaunchedEffect(capsule) {
            snapshotFlow { sheetCard.openProgress >= 0.85f && !sheetCard.sinking }
                .distinctUntilChanged()
                .collect { fullyOpen ->
                    if (fullyOpen && capsule.shown.value > 0f) {
                        capsule.shown.snapTo(0f)
                    }
                }
        }
    }
    val density = LocalDensity.current

    // Search: how far the page has receded to make room for it, 0 shut, 1 open.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searchPull = remember { Animatable(if (searchOpen) 1f else 0f) }
    val scope = rememberCoroutineScope()

    fun openSearch() {
        if (searchOpen) return
        searchOpen = true
        val dur = if (pageMotion.reduceMotion) 180 else 440
        scope.launch { searchPull.animateTo(1f, tween(dur, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.9f, 0.2f, 1f))) }
    }

    fun closeSearch() {
        searchOpen = false
        val dur = if (pageMotion.reduceMotion) 150 else 380
        scope.launch { searchPull.animateTo(0f, tween(dur, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.9f, 0.2f, 1f))) }
    }

    BackHandler(enabled = searchOpen) { closeSearch() }

    val navigationBarHeight = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    // The docked mini player: a bar of its own at the foot of the screen (black, with the progress along its bottom edge); the
    // page is cut above it. Its height is not the page's to use, so nothing is reserved on top of the 16 dp of air.
    // No dock while the player is open but not all there (on its way back over a page): the page would be cut at its foot
    // and the dock's black strip show through the player still fading in.
    val hasMiniPlayerDock = (showPill || (playerOpen && !playerBelow)) && backdrop.hasTrack && !(playerOpen && !playerFullyBack)
    val dockTotalHeight = if (hasMiniPlayerDock) com.ikko.aura.ui.components.DOCK_HEIGHT + navigationBarHeight else 0.dp
    val reservedPillHeight = if (hasMiniPlayerDock) 16.dp else navigationBarHeight

    Surface(color = AuraColors.NonAmoledBlack, modifier = Modifier.fillMaxSize()) {
        PermissionGate {
        CompositionLocalProvider(
            LocalNowPlayingMesh provides backdrop.meshColors,
            com.ikko.aura.ui.theme.LocalAmoledBackgroundEnabled provides amoledBackgroundEnabled,
            LocalPlayerPillHeight provides reservedPillHeight,
            LocalAuraBackdrop provides glassBackdrop,
            LocalSearchOpener provides ::openSearch,
            com.ikko.aura.ui.components.LocalHero provides hero,
            LocalPageTheme provides pageTheme,
            com.ikko.aura.ui.theme.LocalCoverColors provides coverColors,
            LocalPageMotion provides pageMotion,
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
            it.startsWith("album/") || it.startsWith("ytm_album/") || it.startsWith("remote_album/") ||
                it.startsWith("playlist/")
        } == true
        val albumAuroraScrim by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isAlbumRoute) 0f else 1f,
            animationSpec = tween(
                durationMillis = 240,
                delayMillis = if (isAlbumRoute) com.ikko.aura.ui.theme.AuraMotion.PUSH_START_DELAY_MS else 40,
                easing = androidx.compose.animation.core.CubicBezierEasing(0.33f, 0f, 0.15f, 1f),
            ),
            label = "album-aurora-scrim",
        )
        val albumAuroraVisible = !amoledBackgroundEnabled || (albumAuroraTheme != null && (isAlbumRoute || albumAuroraScrim < 1f))
        if (albumAuroraVisible) {
            val currentTheme = if (amoledBackgroundEnabled) {
                pageTheme.value ?: albumAuroraTheme ?: backdrop.theme
            } else {
                if (isAlbumRoute || albumAuroraScrim == 0f) {
                    pageTheme.value ?: albumAuroraTheme ?: backdrop.theme
                } else {
                    pageTheme.value ?: backdrop.theme
                }
            }
            com.ikko.aura.ui.components.AuroraBackground(
                theme = currentTheme,
                active = !playerOpen && currentRoute !in AuraDestination.fullScreenRoutes && (!amoledBackgroundEnabled || isAlbumRoute || albumAuroraScrim < 1f),
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (amoledBackgroundEnabled && albumAuroraScrim > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(AuraColors.NonAmoledBlack.copy(alpha = albumAuroraScrim))
            )
        }
        val washClip = remember { androidx.compose.ui.graphics.Path() }
        val onPlayerScreen = currentRoute in AuraDestination.fullScreenRoutes
        val routeTrail = remember { object { var last: String? = null; var before: String? = null } }
        if (currentRoute != routeTrail.last) { routeTrail.before = routeTrail.last; routeTrail.last = currentRoute }
        val fadingRoute = currentRoute?.startsWith("album/") == true || currentRoute?.startsWith("artist/") == true || currentRoute?.startsWith("playlist/") == true
        val washFade by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (fadingRoute) 0f else 1f,
            animationSpec = tween(LYRICS_WASH_FADE_MS, easing = LinearEasing),
            label = "lyrics-wash-fade",
        )
        AlbumArtBackground(
            artworkUri = backdrop.artworkUri,
            artworkKey = backdrop.artworkKey,
            fallbackColors = backdrop.meshColors,
            forceArtwork = onPlayerScreen || playerOpen,
            settledModifier = Modifier.layerBackdrop(
                glassBackdrop,
                enabled = false,
            ),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val comingBack = sheetCard.passing
                    val onOwnScreen = (onPlayerScreen && currentRoute != AuraDestination.NowPlaying.route) || comingBack
                    alpha = if (onOwnScreen) washFade else (if (sheetCard.waitingToShow) 0.01f else sheetCard.cardAlpha) * washFade
                }
                .drawWithContent {
                    val isFadingRoute = fadingRoute && washFade > 0f
                    if (sheetCard.owner == null && !onPlayerScreen && !isFadingRoute) return@drawWithContent
                    val comingBack = sheetCard.passing
                    // The player's card carries its own opaque ground (PlayerCoverGround) over every pixel this would
                    // draw on during open, settled, and close. Only draw for the player's sub-screens (lyrics, queue, equaliser)
                    // or when dissolving back from them.
                    if ((currentRoute == AuraDestination.NowPlaying.route || (sheetCard.owner != null && !sheetCard.leavingSideways)) && !comingBack) {
                        return@drawWithContent
                    }
                    val edge = sheetCard.cardTop
                    if (edge <= 0f || comingBack || (onPlayerScreen && currentRoute != AuraDestination.NowPlaying.route)) {
                        drawContent()
                        return@drawWithContent
                    }
                    setToCard(washClip, edge, size, AuraMotion.cornerAt(edge).toPx())
                    clipPath(washClip) { this@drawWithContent.drawContent() }
                },
        )
        // The page ends above the dock with the corners of the phone's own screen (the radius Android reports for it, as on
        // the iKKO's), so the dock and the screen's edge read as one shape.
        val screenCorner = com.ikko.aura.ui.theme.rememberIkkoTopCornerRadius()
        val pageCutShape = remember(hasMiniPlayerDock, screenCorner) {
            if (hasMiniPlayerDock) {
                androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = screenCorner, bottomEnd = screenCorner)
            } else {
                androidx.compose.ui.graphics.RectangleShape
            }
        }
        // The dock's own ground: pure black (AMOLED), so its pixels are off. It is the strip under the page plus what the page's
        // rounded bottom corners leave bare, so the page's corners read against it (the page rests on the app's dark navy).
        if (hasMiniPlayerDock) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(dockTotalHeight + screenCorner)
                    .drawBehind {
                        val corner = screenCorner.toPx()
                        val pageBottom = size.height - dockTotalHeight.toPx()
                        val strip = androidx.compose.ui.graphics.Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }
                        val page = androidx.compose.ui.graphics.Path().apply {
                            addRoundRect(
                                androidx.compose.ui.geometry.RoundRect(
                                    0f, -corner * 2f, size.width, pageBottom,
                                    androidx.compose.ui.geometry.CornerRadius.Zero, androidx.compose.ui.geometry.CornerRadius.Zero,
                                    androidx.compose.ui.geometry.CornerRadius(corner), androidx.compose.ui.geometry.CornerRadius(corner),
                                ),
                            )
                        }
                        val bare = androidx.compose.ui.graphics.Path()
                        bare.op(strip, page, androidx.compose.ui.graphics.PathOperation.Difference)
                        drawPath(bare, Color.Black)
                    },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = dockTotalHeight)
                .clip(pageCutShape)
                .drawWithContent {
                    val coveredByPlayer = sheetCard.owner != null &&
                        !sheetCard.leavingSideways &&
                        !sheetCard.passing &&
                        sheetCard.ready &&
                        sheetCard.edge <= 0.0005f &&
                        sheetCard.cardAlpha >= 0.999f
                    if (!coveredByPlayer) {
                        drawContent()
                    }
                },
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = searchPull.value
                    if (p == 0f) return@graphicsLayer
                    val shrink = 1f - PAGE_RECEDE * p
                    scaleX = shrink
                    scaleY = shrink
                    val blurPx = SEARCH_BLUR_DP.dp.toPx() * p
                    renderEffect = if (blurPx >= 1f) {
                        androidx.compose.ui.graphics.BlurEffect(blurPx, blurPx, androidx.compose.ui.graphics.TileMode.Clamp)
                    } else {
                        null
                    }
                },
        ) {
            fun isUnderPlayer(entry: NavBackStackEntry): Boolean =
                playerOpen && playerHostEntryId != null && entry.id == playerHostEntryId

            NavHost(
                navController = navController,
                startDestination = AuraDestination.Tabs.route,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                enterTransition = {
                    val base: EnterTransition = when {
                        isSheet(targetState) -> { sheetFlags.opening = true; sheetFlags.away = false; AuraMotion.sheetEnter }
                        (isSheet(initialState) || isUnderPlayer(initialState)) && isLyricsOrAlbum(targetState) -> {
                            sheetFlags.closing = false
                            sheetFlags.away = true
                            throughEnter(fromLeft = false)
                        }
                        isUnderPlayer(initialState) -> {
                            sheetFlags.closing = false
                            sheetFlags.away = true
                            pushEnter()
                        }
                        else -> pushEnter()
                    }
                    // An album opened from a card or from the player: nothing of the page slides or fades as a whole.
                    // The cover, the title and the artist travel (the hero), what the page brings rises by itself and
                    // its background arrives on its own clock (see AlbumPageContent).
                    if (hero.armed && isAlbumRoute(targetState) && hero.matchesEntry(targetState.destination.route, targetState.arguments)) {
                        EnterTransition.None
                    } else base
                },
                exitTransition = {
                    if (isSheet(initialState)) { sheetFlags.closing = false; sheetFlags.away = true }
                    val base: ExitTransition = when {
                        isSheet(targetState) -> AuraMotion.sheetUnderExit
                        isUnderPlayer(initialState) -> {
                            sheetFlags.closing = false
                            sheetFlags.away = true
                            AuraMotion.sheetUnderExit
                        }
                        isLyricsOrAlbum(targetState) && isSheet(initialState) -> throughExit(toLeft = true, startMs = AuraMotion.PUSH_START_DELAY_MS)
                        isSheet(initialState) -> fadeOut(tween(com.ikko.aura.ui.theme.AuraMotion.PUSH_MS, delayMillis = com.ikko.aura.ui.theme.AuraMotion.PUSH_START_DELAY_MS))
                        else -> pushExit()
                    }
                    // The list stays composed until it has cleared (the ripple of the hero, 310 ms) and is not dimmed as a
                    // whole: a transparent fade that does nothing but keep the page alive for as long.
                    val fromHeroList = initialState.destination.route == AuraDestination.Tabs.route ||
                        initialState.destination.route == AuraDestination.ArtistPattern.route
                    if (hero.armed && isAlbumRoute(targetState) && fromHeroList &&
                        hero.matchesEntry(targetState.destination.route, targetState.arguments)
                    ) {
                        fadeOut(tween(HERO_KEEP_ALIVE_MS, easing = LinearEasing), targetAlpha = 0.999f)
                    } else base
                },
                popEnterTransition = {
                    if (isSheet(targetState)) { sheetFlags.opening = false; sheetFlags.away = false }
                    val base: EnterTransition = when {
                        isSheet(initialState) -> AuraMotion.sheetUnderEnter
                        isUnderPlayer(targetState) -> {
                            sheetFlags.opening = false
                            sheetFlags.away = false
                            playerFromPill = false
                            if (isLyricsOrAlbum(initialState)) {
                                sheetCard.passing = true
                            }
                            AuraMotion.sheetUnderEnter
                        }
                        isSheet(targetState) && isLyricsOrAlbum(initialState) -> { sheetCard.passing = true; throughEnter(fromLeft = true, startMs = AuraMotion.PUSH_START_DELAY_MS) }
                        isSheet(targetState) -> fadeIn(tween(com.ikko.aura.ui.theme.AuraMotion.PUSH_MS, delayMillis = com.ikko.aura.ui.theme.AuraMotion.POP_START_DELAY_MS))
                        else -> popEnter()
                    }
                    // Back to the list: it is there, and each item comes back by itself from the one that was touched.
                    val toHeroList = targetState.destination.route == AuraDestination.Tabs.route ||
                        targetState.destination.route == AuraDestination.ArtistPattern.route
                    if (isAlbumRoute(initialState) && toHeroList &&
                        hero.matchesEntry(initialState.destination.route, initialState.arguments) &&
                        (hero.canClose || hero.phase == com.ikko.aura.ui.components.HeroPhase.Closing)
                    ) {
                        EnterTransition.None
                    } else base
                },
                popExitTransition = {
                    if (isSheet(initialState)) sheetFlags.closing = !isLyricsOrAlbum(targetState)
                    val matchesClosing = isAlbumRoute(initialState) &&
                        hero.matchesEntry(initialState.destination.route, initialState.arguments)
                    if (matchesClosing && hero.canClose) hero.startClosing()
                    val base: ExitTransition = when {
                        isSheet(initialState) -> AuraMotion.sheetExit
                        (isSheet(targetState) || isUnderPlayer(targetState)) && isLyricsOrAlbum(initialState) ->
                            throughExit(toLeft = false, startMs = AuraMotion.PUSH_START_DELAY_MS)
                        else -> popExit()
                    }
                    // The album page stays until what it brought has gone (140 ms), its background has faded (140 + 380)
                    // and the cover is home (520): it is not slid or faded as a whole.
                    // Back to the player: the page stays under it while the player comes back over it (no slide, no gap).
                    val toHeroList = targetState.destination.route == AuraDestination.Tabs.route ||
                        targetState.destination.route == AuraDestination.ArtistPattern.route
                    if (matchesClosing && hero.phase == com.ikko.aura.ui.components.HeroPhase.Closing &&
                        (toHeroList || hero.fromPlayer)
                    ) {
                        fadeOut(tween(HERO_KEEP_ALIVE_MS, easing = LinearEasing), targetAlpha = 0.999f)
                    } else base
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
                    val sliding = transition.currentState != androidx.compose.animation.EnterExitState.Visible ||
                        transition.targetState != androidx.compose.animation.EnterExitState.Visible
                    UnderSheet { LyricsScreen(navController = navController, playerViewModel = playerViewModel, isTransitioning = sliding) }
                }
                composable(AuraDestination.Equalizer.route) { UnderSheet { EqualizerScreen(navController) } }
                composable(AuraDestination.NowPlaying.route) {
                    LaunchedEffect(Unit) {
                        navController.popBackStack()
                        openPlayerFromPill()
                    }
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
                            playerViewModel = playerViewModel,
                        )
                    }
                }
            }
        }
        }

            // Persistently hosted NowPlayingScreen: sits above the page's LayerBackdrop so its card motion never
            // invalidates pillBackdrop, and remains warm in composition when closed so opening and closing never
            // compose or measure the 1,670-line player or the underlying page from scratch.
            if (backdrop.hasTrack || playerOpen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        // Not placed while a page is over it: placed, its controls (invisible) would take the page's taps.
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val visible = !playerLeavingSideways || playerSidewaysAlpha.value > 0f
                            if (visible) {
                                layout(placeable.width, placeable.height) {
                                    placeable.place(0, 0)
                                }
                            } else {
                                layout(0, 0) {}
                            }
                        }
                        .graphicsLayer { alpha = playerSidewaysAlpha.value }
                        .drawWithContent {
                            if (playerSidewaysAlpha.value > 0f) {
                                drawContent()
                            }
                        },
                ) {
                    NowPlayingScreen(
                        navController = navController,
                        viewModel = playerViewModel,
                        sheetOpening = playerFromPill,
                        sheetClosing = playerClosingToPill,
                        leavingSideways = playerLeavingSideways,
                        sheetCard = sheetCard,
                        active = playerOpen,
                        onRequestClose = {
                            playerClosingToPill = true
                        },
                        onClosed = {
                            playerOpen = false
                            playerClosingToPill = false
                            playerHostEntryId = null
                            sheetCard.underEntryId = null
                            sheetFlags.opening = false
                            sheetFlags.closing = false
                            sheetFlags.away = false
                        },
                        onNavigateFromPlayer = { route ->
                            val hostId = playerHostEntryId ?: navController.currentBackStackEntry?.id
                            playerHostEntryId = hostId
                            sheetCard.underEntryId = hostId
                            sheetFlags.closing = false
                            sheetFlags.away = true
                            navController.navigate(route)
                        },
                    )
                }
            }

            // The cover that travels from a card to the album page (and back): over the pages, under the player and search.
            com.ikko.aura.ui.components.HeroOverlay(hero, reduceMotion = pageMotion.reduceMotion)

            // Search, over everything the page is doing. Composed only while it is on its way in
            // or already there, so a shut search costs nothing at all.
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
                    pillAlpha.snapTo(0f)
                }
            }
            val isTabsRoute = currentRoute == AuraDestination.Tabs.route
            // A trip of the hero belongs to the page it ended on. Back to where it left from (the list, or the player over
            // it), even while still on its way out: everything returns. Anywhere else: it is simply over.
            LaunchedEffect(currentRoute) {
                val onHeroPage = isHeroPageRoute(currentRoute)
                if (hero.canClose && !onHeroPage) {
                    if (currentRoute == AuraDestination.Tabs.route) hero.startClosing() else hero.reset()
                }
            }
            val hasPill = (showPill || (playerOpen && !playerBelow)) && backdrop.hasTrack
            val arrival = remember { Animatable(if (backdrop.hasTrack) 1f else 0f) }
            LaunchedEffect(backdrop.hasTrack) {
                if (backdrop.hasTrack) arrival.capsuleTo(true) else arrival.capsuleTo(false, windUp = false)
            }
            val capsuleBottomDp by androidx.compose.animation.core.animateDpAsState(
                targetValue = capsuleBottom(reservedPillHeight, navigationBarHeight, hasPill),
                animationSpec = androidx.compose.animation.core.spring(
                    PageMotionSpec.CAPSULE_SHOW_DAMPING, PageMotionSpec.CAPSULE_SHOW_STIFFNESS,
                ),
                label = "capsule-bottom",
            )

            // How visible the capsule is, from the route, the search and the player's card. Read by its layer and by its
            // drawing, so both follow the same states.
            fun capsuleAlpha(): Float {
                val isTabs = currentRoute == AuraDestination.Tabs.route
                if (CAPSULE_HIDDEN) return 0f
                return searchClear(searchPull.value, !searchOpen) * when {
                    !isTabs || playerBelow -> 0f
                    sheetCard.owner == null && playerOpen && sheetFlags.fromPill -> 1f
                    sheetCard.owner == null -> if (isTabs) 1f else 0f
                    sheetCard.waitingToShow || (playerOpen && !sheetCard.ready) -> 1f
                    !sheetCard.sinking -> ((0.22f - sheetCard.openProgress) / 0.05f).coerceIn(0f, 1f)
                    else -> if (sheetCard.edge > 0.72f) 1f else 0f
                }
            }

            CompositionLocalProvider(LocalGlassSettle provides pillGlassSettle) {
                if (!CAPSULE_HIDDEN) {
                    val capsuleEnabled = isTabsRoute && !playerOpen && !searchOpen
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
                                    return@graphicsLayer
                                }
                                alpha = targetAlpha
                            }
                            .drawWithContent { if (capsuleAlpha() > 0f) drawContent() },
                    )
                }

                fun pillBaseAlpha(): Float {
                    if (!showPill) return 0f
                    val waiting = sheetCard.waitingToShow ||
                        (playerOpen && !playerLeavingSideways && sheetCard.owner == null && sheetFlags.fromPill)
                    return when {
                        waiting -> 1f
                        sheetCard.owner != null -> {
                            // The mini player rides with the card's top edge and is faded out near the top (see its layer): it
                            // does not cut out as the card covers it.
                            if (sheetCard.edge >= 1f && playerOpen && !sheetCard.holding) 0f else 1f
                        }
                        playerOpen -> 0f
                        else -> pillAlpha.value
                    }
                }

                if (backdrop.hasTrack) {
                    // Also while the finger is pulling the player up from it: the player opens under the finger, and the bar keeps its gesture.
                    val pillInteractive = (showPill && (!playerOpen || playerClosingToPill) && !searchOpen) || sheetCard.holding
                    PlayerPillHost(
                        playerViewModel = playerViewModel,
                        enabled = pillInteractive,
                        showPageSwitch = pageSwitchButton,
                        onPageSwitch = {
                            if (navController.currentBackStackEntry?.destination?.route != AuraDestination.Tabs.route) {
                                navController.popBackStack(AuraDestination.Tabs.route, inclusive = false)
                            }
                        },
                        onPrewarm = {},
                        onPrewarmCancel = {},
                        onOpenNowPlaying = ::openPlayerFromPill,
                        // The player follows the finger up from the mini player, as the other players do: the card is put
                        // where the finger is; let go, it goes on to the top (past a third of the way, or flung) or back.
                        onOpenDragStart = {
                            if (sheetCard.acceptsOpen) {
                                openPlayerFromPill()
                                if (playerOpen) sheetCard.holding = true
                            }
                        },
                        onOpenDrag = { upFraction ->
                            if (sheetCard.holding) {
                                sheetCard.dragTo((AuraMotion.SHEET_PILL_TOP_FRACTION - upFraction).coerceIn(0f, AuraMotion.SHEET_PILL_TOP_FRACTION))
                            }
                        },
                        onOpenDragEnd = { commit, velocityPxS ->
                            if (sheetCard.holding) {
                                // The speed the finger left with goes on into the card's own motion (as a fraction of the
                                // screen's height per second), so letting go does not change the pace.
                                sheetCard.releaseVelocity = velocityPxS / rootView.height.coerceAtLeast(1)
                                sheetCard.holding = false
                                if (!commit) playerClosingToPill = true
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .graphicsLayer {
                                val pAlpha = pillBaseAlpha()
                                if (pAlpha <= 0f) {
                                    alpha = 0f
                                    return@graphicsLayer
                                }
                                val a = arrival.value
                                // Where the card's top edge is: under the finger while it pulls, else the card's own.
                                val top = AuraMotion.SHEET_PILL_TOP_FRACTION
                                val edgeNow = when {
                                    sheetCard.holding -> sheetCard.dragEdge
                                    sheetCard.owner != null && sheetCard.ready -> sheetCard.edge.coerceAtMost(top)
                                    else -> top
                                }
                                val rise = (top - edgeNow).coerceIn(0f, top)
                                translationY = (1f - a) * PageMotionSpec.CAPSULE_RISE_DP.dp.toPx() - rise * rootView.height
                                // Gone by the time the card is up, so it never sits on the player's own controls.
                                val leaving = ((edgeNow - 0.08f) / 0.30f).coerceIn(0f, 1f)
                                alpha = pAlpha * leaving * (1.6f * a).coerceIn(0f, 1f) * searchClear(searchPull.value, !searchOpen)
                            }
                            .drawWithContent {
                                if (pillBaseAlpha() > 0f && arrival.value > 0f) {
                                    drawContent()
                                }
                            },
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
 * Owns the docked mini-player's subscription to the player state.
 */
@Composable
private fun PlayerPillHost(
    playerViewModel: PlayerViewModel,
    onOpenNowPlaying: () -> Unit,
    onPrewarm: () -> Unit,
    onPrewarmCancel: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showPageSwitch: Boolean = false,
    onPageSwitch: () -> Unit = {},
    onOpenDragStart: () -> Unit = {},
    onOpenDrag: (Float) -> Unit = {},
    onOpenDragEnd: (Boolean, Float) -> Unit = { _, _ -> },
) {
    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val track = uiState.currentTrack ?: return
    val positionState = playerViewModel.positionMs.collectAsStateWithLifecycle()
    val durationState = playerViewModel.durationMs.collectAsStateWithLifecycle()

    val dockAccent = com.ikko.aura.ui.components.rememberPlayerAccent(followPage = false)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // An opaque bar, navigation bar included: it rides over the card as its handle and hides the card at rest.
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
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
            onOpenDragStart = onOpenDragStart,
            onOpenDrag = onOpenDrag,
            onOpenDragEnd = onOpenDragEnd,
            enabled = enabled,
            accentColor = dockAccent,
            progressProvider = {
                val dur = durationState.value
                if (dur > 0L) (positionState.value.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
            },
            onSeek = { f ->
                val dur = durationState.value
                if (dur > 0L) playerViewModel.seekTo((dur * f).toLong())
            },
            hasRightButton = showPageSwitch,
            endSlot = {
                if (showPageSwitch) {
                    PageSwitchPill(
                        enabled = enabled,
                        beforeSwitch = onPageSwitch,
                    )
                }
            },
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
val LocalLibraryHeaderOffsetPx = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableFloatState?> { null }

@Composable
private fun TabPager(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    motion: PageMotionState,
) {
    val libraryCollapseOffsetPx = rememberSaveable { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val openSearch = LocalSearchOpener.current
    val density = LocalDensity.current
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val maxCollapsePx = with(density) { (statusBarTop + 48.dp).toPx() }

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
    CompositionLocalProvider(LocalLibraryHeaderOffsetPx provides libraryCollapseOffsetPx) {
        Box(Modifier.fillMaxSize()) {
            PagedContainer(
                state = motion,
                pages = pages,
            )
            // One header for Home and Library, outside the pager: it stays put while the pages slide under it.
            val settingsVm: SettingsViewModel = hiltViewModel()
            val profileUri by settingsVm.profileImageUri.collectAsStateWithLifecycle()
            // It still folds away with the library's scroll, as before: slid up and faded by the same offset the
            // library's header takes, weighted by how much of the Library page is showing (none on Home).
            val unitPx = with(density) { com.ikko.aura.ui.components.designUnit().toPx() }
            val fadeSpanPx = (com.ikko.aura.ui.components.PageHeader.SEARCH_TOP +
                com.ikko.aura.ui.components.PageHeader.SEARCH_HEIGHT) * unitPx
            com.ikko.aura.ui.components.CommonHeader(
                profileUri = profileUri,
                onSearch = openSearch,
                onProfile = { navController.navigate(AuraDestination.Settings.route) { launchSingleTop = true } },
                modifier = Modifier
                    .heroRipple(fast = true)
                    .offset {
                        val libWeight = motion.p.coerceIn(0f, 1f)
                        IntOffset(0, (libraryCollapseOffsetPx.floatValue * libWeight).roundToInt())
                    }
                    .graphicsLayer {
                        val libWeight = motion.p.coerceIn(0f, 1f)
                        val dy = libraryCollapseOffsetPx.floatValue * libWeight
                        alpha = (1f + dy / fadeSpanPx).coerceIn(0f, 1f)
                    },
            )
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
            // Above the keyboard: the panel is the height that is left, so the prompt is centred in what can be seen
            // and not under the keys. From the top of the screen: the field is laid out in the design's own units.
            .imePadding()
            .graphicsLayer {
                // The field is the header's own, carried over: it must be there from the first frame, not fade in.
                alpha = (progressProvider() * 4f).coerceAtMost(1f)
            },
    ) {
        // Built only once the gesture has committed: the field takes focus and raises the
        // keyboard the moment it exists, which is not something to do halfway through a drag that
        // may yet be pulled back.
        // Kept while the search closes: it has its own way out (the board's close is not its open played backwards).
        SearchPanel(
            navController = navController,
            playerViewModel = playerViewModel,
            onNavigate = onDismiss,
            closing = !open,
            progressProvider = progressProvider,
        )
    }
}

/** How much the page shrinks and slides as it recedes. */
private const val PAGE_RECEDE = 0f

/** The blur of the page behind the search, at full pull. */
private const val SEARCH_BLUR_DP = 0f

/**
 * Laid over the page while the search is open. Not a blur of the page: the background behind the
 * window is a surface of its own that a blur cannot sample, and a scrim over the page alone printed
 * a dark plate across the aurora. A light scrim keeps the aurora and dims the page enough to read
 * the field over it.
 */
private val SEARCH_SCRIM = Color.Black

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
private const val IMMERSIVE_HOLD_MS = 320L
private const val CAPSULE_HIDDEN = true
private const val CAPSULE_PARKED_PX = 20_000f
