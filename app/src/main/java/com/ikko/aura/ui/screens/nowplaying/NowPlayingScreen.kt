package com.ikko.aura.ui.screens.nowplaying

import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import com.ikko.aura.ui.components.backdrop.GlassSettle
import com.ikko.aura.ui.components.backdrop.LocalGlassSettle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.snapshotFlow
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.toIntSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.lyrics.LyricLine
import com.ikko.aura.data.model.AudioOutput
import com.ikko.aura.data.model.AudioOutputState
import com.ikko.aura.data.model.AudioOutputType
import com.ikko.aura.data.model.Track
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.navigation.streamedAlbumRoute
import com.ikko.aura.navigation.streamedArtistRoute
import com.ikko.aura.source.ytm.YtmLibrary
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.backdrop.backdrops.layerBackdrop
import com.ikko.aura.ui.components.backdrop.backdrops.LayerBackdrop
import com.ikko.aura.ui.components.LocalAuraBackdrop
import com.ikko.aura.ui.components.AlbumArtCache
import androidx.compose.ui.graphics.asImageBitmap
import com.ikko.aura.ui.components.AURORA_H
import com.ikko.aura.ui.components.AlbumTheme
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.auraMenuSurface
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.SweepLyricLine
import com.ikko.aura.ui.components.WavyProgressBar
import com.ikko.aura.ui.components.indexAt
import com.ikko.aura.ui.components.rememberLiveLyricClock
import com.ikko.aura.ui.components.LiquidGlassPanel
import com.ikko.aura.ui.components.horizontalEdgeBlur
import com.ikko.aura.ui.components.DesignMenu
import com.ikko.aura.ui.components.DesignMenuDivider
import com.ikko.aura.ui.components.DesignMenuRow
import com.ikko.aura.ui.components.GlassMenuState
import com.ikko.aura.ui.components.glassMenuAnchor
import com.ikko.aura.ui.components.rememberGlassMenuState
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.components.rememberPlayerAccent
import com.ikko.aura.ui.components.SkipGlyph
import com.ikko.aura.ui.components.HALO_ALPHA
import com.ikko.aura.ui.components.HALO_FADE_MS
import com.ikko.aura.ui.components.CHASE_MS
import com.ikko.aura.ui.theme.AuraMotion
import com.ikko.aura.ui.components.FillableIcon
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.LyricsUiState
import com.ikko.aura.viewmodel.LyricsViewModel
import com.ikko.aura.viewmodel.PlayerUiState
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.keyAt
import com.ikko.aura.viewmodel.RepeatMode
import kotlin.math.abs
import kotlin.math.hypot
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture

private val PanelShape = RoundedCornerShape(24.dp)
private val ButtonShape = RoundedCornerShape(20.dp)
private val ArtShape = RoundedCornerShape(14.dp)
private val ChipShape = RoundedCornerShape(50)

private val SCREEN_PADDING = 16.dp

/** The gap between the groups of the player's controls: the bar and its lyric, the transport, the utility row. */
private val GROUP_GAP = 6.dp

private val HERO_ART_SIZE = 120.dp
/**
 * The touch area of the playback controls, made for a thumb: as tall as the glyphs' neighbourhood allows, side by side with no
 * gap between them, and reaching down to the bottom edge. The extra is mostly *above* the glyphs, because that is where the
 * finger lands. The glyphs sit 69 design px above the row's bottom, as drawn.
 */
private val TRANSPORT_HEIGHT = 96.dp

/**
 * How much further up the row's top edge is than it was (56 dp tall, 12 dp clear below it). The things above it are drawn
 * that much lower than they are laid out, so the extra touch area grows up over the room under the time labels rather than
 * pushing the bar, the lyrics and the title up.
 */
private val TRANSPORT_EXTRA = TRANSPORT_HEIGHT - 56.dp - 12.dp
/** A flick's speed, in px/s, standing in for "fast enough" when a swipe is counted by its length. */
private const val FLICK_PX_S = 2000f

/** How far a transport button sinks under the finger. */
private const val PRESSED_SCALE = 0.96f

/**
 * The play button's wash. Fixed rather than pulled from the artwork palette: it is the only
 * saturated thing on the screen and the one control that has to read identically on every cover,
 * and a palette-derived tint would regularly land on the same hue as the backdrop behind it.
 */
internal val PlayTint = Color(0xFFF3C7D5)
internal val PlayGlyph = Color(0xFF6E1B36)
private val NeutralGlassTint = Color(0xFF121212)

/** What the card is doing: one animation at a time, keyed on this. */
private enum class SheetPhase { Settled, Closing, Away }

/** How visible the card is while it renders unseen before showing: enough to be rendered, not to be seen. */
private const val WARMING_ALPHA = 0.01f

/** The longest a closing card waits for the page under it to be composed again before it goes down anyway. */
private const val PAGE_WAIT_MS = 250L

/** The longest the card waits, hidden, for the player to draw before it shows anyway. */
private const val READY_TIMEOUT_MS = 400L

@Composable
fun NowPlayingScreen(
    navController: NavHostController,
    viewModel: PlayerViewModel = hiltViewModel(),
    lyricsViewModel: LyricsViewModel = hiltViewModel(),
    /** True when this screen is being opened from the mini player, so its card should rise into place. */
    sheetOpening: Boolean = false,
    /** True while it is being closed back to the mini player, so its card should sink to it. */
    sheetClosing: Boolean = false,
    /** True while it leaves for a page of its own rather than for the mini player. */
    leavingSideways: Boolean = false,
    /** The shared card; see below. */
    sheetCard: com.ikko.aura.SheetCard = remember { com.ikko.aura.SheetCard() },
    /** True when the player is active (open, opening, closing, or under a sideways destination). */
    active: Boolean = true,
    /** Called when the user swipes down or presses Back to close the player to the pill. */
    onRequestClose: () -> Unit = { navController.popBackStack() },
    /** Called once the close-to-pill animation has finished and released the card. */
    onClosed: () -> Unit = {},
    /** Called when navigating from the player to one of its own destinations (Queue, Lyrics, Album, Artist). */
    onNavigateFromPlayer: (String) -> Unit = { navController.navigate(it) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lyricsState by lyricsViewModel.uiState.collectAsStateWithLifecycle()
    val isLiked by viewModel.isLiked.collectAsStateWithLifecycle()
    val albumScope = androidx.compose.runtime.rememberCoroutineScope()
    val closeScope = androidx.compose.runtime.rememberCoroutineScope()
    val composeView = androidx.compose.ui.platform.LocalView.current
    val albumContext = androidx.compose.ui.platform.LocalContext.current
    val positionMsState = viewModel.positionMs.collectAsStateWithLifecycle()
    val durationMsState = viewModel.durationMs.collectAsStateWithLifecycle()

    val outputMenuState = rememberGlassMenuState()
    val queueSheet = rememberQueueSheetState()

    LaunchedEffect(uiState.currentTrack?.id) {
        lyricsViewModel.loadFor(uiState.currentTrack)
    }

    AudioOutputPicker(
        state = outputMenuState,
        outputs = uiState.audioOutput.available,
        selectedDeviceId = uiState.audioOutput.selectedDeviceId,
        activeName = uiState.audioOutput.deviceName,
        onSelect = {
            viewModel.selectAudioOutput(it)
        },
    )

    // The player as a card, in Apple's way (see AuraMotion). [sheetCard] says where the card's top edge is,
    // as a fraction of the screen's height — 0 up, 1 no card — and the rest of the app arranges itself
    // around it: the page under the card is drawn only above that edge, and the mini player and the tab
    // capsule stay away while it covers them.
    //
    // This screen owns it for as long as it is active, and gives it back when it leaves. One animation
    // drives it, keyed on what the card is doing, so a close started while the open was still running
    // cancels the open rather than fighting it for the same number.
    val settle = remember { GlassSettle() }
    var cardHeightPx by remember { mutableIntStateOf(0) }
    // Set the first time this screen actually draws. Composing the player takes several frames, and a card
    // that started rising before then was an empty grey rectangle for the first of them, filling in
    // afterwards — which is the blink one sees on opening. Not snapshot state: it is written from the draw.
    val drawnOnce = remember { MutableStateFlow(false) }
    var warmedUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        drawnOnce.first { it }
        withFrameNanos { }
        warmedUp = true
    }
    // Whether the card has arrived at the top. Until then it takes no touches (see the card's pointerInput).
    // Held in a plain array rather than snapshot state so flipping it at the end of the spring never recomposes the player.
    val landedRef = remember { booleanArrayOf(active && !sheetOpening) }
    val activeState = androidx.compose.runtime.rememberUpdatedState(active)
    val leavingSidewaysState = androidx.compose.runtime.rememberUpdatedState(leavingSideways)
    val onRequestCloseState = androidx.compose.runtime.rememberUpdatedState(onRequestClose)
    val onClosedState = androidx.compose.runtime.rememberUpdatedState(onClosed)
    val onNavigateFromPlayerState = androidx.compose.runtime.rememberUpdatedState(onNavigateFromPlayer)
    val frozenPositionRef = remember { longArrayOf(0L) }

    val token = remember { Any() }
    val wasActiveRef = remember { booleanArrayOf(false) }
    if (active && !wasActiveRef[0]) {
        wasActiveRef[0] = true
        frozenPositionRef[0] = positionMsState.value
        val alreadyWarm = drawnOnce.value
        landedRef[0] = !sheetOpening
        sheetCard.claim(
            token,
            if (sheetOpening) AuraMotion.SHEET_PILL_TOP_FRACTION else 0f,
            ready = !sheetOpening || alreadyWarm,
        )
        if (sheetOpening && alreadyWarm) {
            settle.frozen = true
            sheetCard.frozen = true
        }
    } else if (!active && wasActiveRef[0]) {
        wasActiveRef[0] = false
        landedRef[0] = false
        sheetCard.release(token)
    }
    DisposableEffect(token) { onDispose { sheetCard.release(token) } }

    // Set the moment this screen itself closes the player (the swipe down, the back button), so the card starts
    // going down at once rather than whenever the navigation has worked out what its own pop was.
    var closeRequested by remember { mutableStateOf(false) }
    if (!active || (!sheetClosing && closeRequested && !sheetCard.sinking && sheetCard.owner === token && sheetCard.edge >= AuraMotion.SHEET_PILL_TOP_FRACTION)) {
        closeRequested = false
    }
    LaunchedEffect(active, sheetClosing) {
        if (!active || !sheetClosing) closeRequested = false
    }
    fun close() {
        if (!activeState.value || closeRequested) return
        closeRequested = true
        landedRef[0] = false
        onRequestCloseState.value()
    }
    val phase = when {
        !active -> SheetPhase.Away
        closeRequested || sheetClosing -> SheetPhase.Closing
        // Left for a page of its own (an album, the queue, the lyrics): there is no card any more, and the
        // player leaves whole, by its own sideways transition.
        leavingSideways -> SheetPhase.Away
        else -> SheetPhase.Settled
    }
    settle.isClosing = phase == SheetPhase.Closing || phase == SheetPhase.Away
    // The system back button, the same way: without this it went to NavHost's own pop and waited like the swipe.
    BackHandler(enabled = active && phase == SheetPhase.Settled && sheetCard.owner === token) { close() }
    LaunchedEffect(active, phase) {
        if (!active) {
            landedRef[0] = false
            return@LaunchedEffect
        }
        if (phase != SheetPhase.Settled) {
            landedRef[0] = false
            queueSheet.snapClosed()
            sheetCard.markReady()
        }
        sheetCard.sinking = phase == SheetPhase.Closing
        when (phase) {
            SheetPhase.Away -> sheetCard.edge = 1f
            SheetPhase.Closing -> {
                // Down only once there is a page under the card to be uncovered. When the underlying page is kept
                // alive (pagesComposed > 0), start the descent immediately with zero frame wait.
                if (sheetCard.pagesComposed <= 0) {
                    withTimeoutOrNull(PAGE_WAIT_MS) {
                        snapshotFlow { sheetCard.pagesComposed }.first { it > 0 }
                        withFrameNanos { }
                    }
                }
                settle.frozen = true
                sheetCard.frozen = true
                val handedVelocity = sheetCard.releaseVelocity
                sheetCard.releaseVelocity = 0f
                try {
                    animate(
                        initialValue = sheetCard.edge.coerceAtMost(AuraMotion.SHEET_PILL_TOP_FRACTION),
                        targetValue = AuraMotion.SHEET_PILL_TOP_FRACTION,
                        initialVelocity = handedVelocity,
                        // From the finger's release: the same spring as the opening, carrying its speed. By a tap or the
                        // back button: the eased fall.
                        animationSpec = if (handedVelocity != 0f) spring(1f, AuraMotion.SHEET_STIFFNESS, 0.0002f)
                        else tween(AuraMotion.SHEET_EXIT_MS, easing = AuraMotion.EaseInOut),
                    ) { value, _ -> sheetCard.edge = value }
                    sheetCard.release(token)
                    onClosedState.value()
                } finally {
                    settle.frozen = false
                    sheetCard.frozen = false
                }
            }
            SheetPhase.Settled -> {
                if (sheetCard.edge >= 1f) {
                    // Returning from a sideways destination (Lyrics, Queue, Album, Artist): card is already full screen.
                    sheetCard.edge = 0f
                    sheetCard.markReady()
                    landedRef[0] = true
                } else if (sheetCard.edge > 0f && sheetCard.edge < 1f) {
                    if (!drawnOnce.value) {
                        withTimeoutOrNull(READY_TIMEOUT_MS) {
                            drawnOnce.first { it }
                            withFrameNanos { }
                        }
                    }
                    // The finger is still pulling the card up from the mini player: it is shown (the player has drawn)
                    // and left where the finger puts it, and goes on from there once it is let go.
                    if (sheetCard.holding) {
                        sheetCard.markReady()
                        snapshotFlow { sheetCard.holding }.first { !it }
                    }
                    settle.offset = Offset(0f, sheetCard.edge * cardHeightPx)
                    settle.frozen = true
                    sheetCard.frozen = true
                    try {
                        sheetCard.markReady()
                        val handedVelocity = sheetCard.releaseVelocity
                        sheetCard.releaseVelocity = 0f
                        animate(
                            initialValue = sheetCard.edge,
                            targetValue = 0f,
                            initialVelocity = handedVelocity,
                            animationSpec = spring(1f, AuraMotion.SHEET_STIFFNESS, 0.0002f),
                        ) { value, _ ->
                            sheetCard.edge = value
                            if (!sheetCard.ready && value < AuraMotion.SHEET_PILL_TOP_FRACTION) {
                                sheetCard.markReady()
                            }
                        }
                    } finally {
                        settle.frozen = false
                        sheetCard.frozen = false
                        settle.offset = Offset.Zero
                        sheetCard.markReady()
                        landedRef[0] = true
                    }
                } else {
                    sheetCard.markReady()
                    landedRef[0] = true
                }
            }
        }
    }
    val playerTint = remember(uiState.albumTheme.solid) {
        androidx.compose.ui.graphics.lerp(uiState.albumTheme.solid, Color.Black, 0.5f).copy(alpha = 0.34f)
    }
    val groundPicture = remember { androidx.compose.runtime.mutableStateOf<GroundPicture?>(null) }
    CompositionLocalProvider(LocalGlassSettle provides settle, LocalPlayerGlassTint provides playerTint, LocalGroundPicture provides groundPicture) {
    Box(
        Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                if (activeState.value || !warmedUp) {
                    layout(placeable.width, placeable.height) {
                        placeable.place(0, 0)
                    }
                } else {
                    layout(0, 0) {}
                }
            }
            // Where the card's content sits when the card is at rest (outside its movement), and the content
            // itself (inside it): what the glass needs to sample where it will rest. See GlassSettle.
            .onGloballyPositioned { settle.restOrigin = it.positionInWindow() }
            .graphicsLayer {
                val isActive = activeState.value
                val isWarmed = warmedUp
                val rawEdge = sheetCard.edge
                val isReady = sheetCard.ready
                val cAlpha = sheetCard.cardAlpha
                if (!isActive) {
                    alpha = if (!isWarmed) WARMING_ALPHA else 0f
                    return@graphicsLayer
                }
                val o = if (rawEdge >= 1f) 0f else rawEdge
                translationY = o * size.height
                val s = 1f - AuraMotion.SHEET_NARROWING * o
                scaleX = s
                scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                val corner = AuraMotion.cornerAt(o).toPx()
                shape = RoundedCornerShape(corner)
                clip = o > 0.0005f
                alpha = if (!isReady) WARMING_ALPHA else cAlpha
            }
            .onSizeChanged { cardHeightPx = it.height }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (activeState.value && !landedRef[0]) {
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .onGloballyPositioned { settle.anchor = it }
            .drawWithContent {
                if (!activeState.value) {
                    if (!drawnOnce.value) {
                        drawContent()
                        drawnOnce.value = true
                    }
                    return@drawWithContent
                }
                val cleared = (sheetCard.ready && sheetCard.cardAlpha <= 0f) || sheetCard.owner !== token
                if (!cleared) drawContent()
                if (!drawnOnce.value) drawnOnce.value = true
            },
    ) {
    // Keep the player's full content in its own retained layer. The parent layer above is
    // responsible only for the sheet transform; changing its position, scale or shape no
    // longer invalidates the artwork, text and glass panels inside this layer.
    Box(Modifier.graphicsLayer { }) {
    // The player's own ground: the cover across the top, melting into one flat colour of the record below it. Drawn over
    // the shared backdrop (the blurred wash and its aurora are no longer seen here).
    PlayerCoverGround(
        track = uiState.currentTrack,
        nextTrack = uiState.queue.getOrNull(uiState.queueIndex + 1),
        solid = uiState.albumTheme.solid,
    )
    GradientScreenScaffold(applyContentInsets = true) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Mirrors the pill: the same swipes keep working once the player is open, so the
                // gesture you used to get here is the one that gets you back. Swipe down closes,
                // left/right move through the queue. The mockup has no close button and neither
                // does this — the gesture and the system back are how it is dismissed.
                .pointerInput(Unit) {
                    val threshold = 56.dp.toPx()
                    awaitEachGesture {
                        // A touch that begins on a control, or on the seek bar, is theirs: they have already taken its press,
                        // and this skips it. Taken as a swipe instead, a thumb that rolled a few millimetres while pressing a
                        // button had its press cancelled from here — the button seemed to work one time in two.
                        val down = awaitFirstDown(requireUnconsumed = true)
                        var dx = 0f
                        var dy = 0f
                        var started = false
                        // Set once a vertical drag has taken hold of the queue sheet: it follows the finger up, and
                        // what the gesture means is then decided by the sheet, not by the swipe-down-to-close below.
                        var sheetDrag = false
                        // Set while the card follows the finger down: the player closes under it (as it opened under it),
                        // and what happens on release depends on how far and how fast it went.
                        var followClose = false
                        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                        val slop = viewConfiguration.touchSlop
                        val lifted = drag(down.id) { change ->
                            val delta = change.positionChange()
                            dx += delta.x
                            dy += delta.y
                            tracker.addPointerInputChange(change)
                            if (!started && hypot(dx, dy) > slop) started = true
                            if (started) {
                                change.consume()
                                if (!sheetDrag && dy < 0f && abs(dy) > abs(dx)) sheetDrag = true
                                if (sheetDrag) queueSheet.dragBy(delta.y)
                                if (!sheetDrag && !followClose && dy > 0f && dy > abs(dx) && activeState.value && !closeRequested) {
                                    followClose = true
                                    sheetCard.holding = true
                                }
                                if (followClose) {
                                    sheetCard.dragTo((dy / composeView.height.coerceAtLeast(1)).coerceIn(0f, AuraMotion.SHEET_PILL_TOP_FRACTION))
                                }
                            }
                        }
                        if (followClose) {
                            val vy = tracker.calculateVelocity().y
                            // A short flick is enough: what decides is where it was going, not how far.
                            val commit = lifted && (dy >= 120.dp.toPx() || (vy > 350f && dy >= 10.dp.toPx()))
                            sheetCard.releaseVelocity = vy / composeView.height.coerceAtLeast(1)
                            sheetCard.holding = false
                            if (commit) {
                                // Closes from where the finger left it.
                                close()
                            } else {
                                // Not far enough: back up to the top.
                                closeScope.launch {
                                    val handed = sheetCard.releaseVelocity
                                    sheetCard.releaseVelocity = 0f
                                    animate(
                                        initialValue = sheetCard.edge,
                                        targetValue = 0f,
                                        initialVelocity = handed,
                                        animationSpec = spring(1f, AuraMotion.SHEET_STIFFNESS, 0.0002f),
                                    ) { value, _ -> if (!closeRequested) sheetCard.dragTo(value) }
                                }
                            }
                            return@awaitEachGesture
                        }
                        if (!started) return@awaitEachGesture
                        when {
                            !lifted -> if (sheetDrag) queueSheet.settle(0f)
                            sheetDrag -> queueSheet.settle(if (dy <= -threshold) -FLICK_PX_S else 0f)
                            abs(dx) > abs(dy) && dx <= -threshold -> viewModel.skipNext()
                            abs(dx) > abs(dy) && dx >= threshold -> viewModel.skipPrevious()
                            dy >= threshold -> close()
                        }
                    }
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Ouvrir la file d'attente") { queueSheet.open(); true },
                    )
                }
                // The side margin is on each block rather than here, so the seek bar alone can span the screen's
                // full width to catch a thumb at the edge (it draws itself inset by the same margin).
                // Nothing under the playback controls: their touch area itself reaches down to the system bar's inset.
                .padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Where the sound goes, top left, and the queue, top right: under the status bar (the scaffold keeps the
            // inset), at the page margins, over the cover's dark fade.
            // At 110 design px from the top, as drawn (the column starts under the status bar, and 10 dp lower).
            val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = designUnit() * 40f)
                    .offset(y = designUnit() * 110f - statusTop - 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PlayerRoundButton(
                    icon = CupertinoIcons.Headphones,
                    contentDescription = "Sortie audio : ${uiState.audioOutput.deviceName ?: uiState.audioOutput.type.label()}",
                    onClick = { outputMenuState.open() },
                    left = true,
                    modifier = Modifier.glassMenuAnchor(outputMenuState),
                )
                PlayerRoundButton(
                    icon = CupertinoIcons.Queue,
                    contentDescription = "Ouvrir la file d'attente",
                    onClick = { onNavigateFromPlayerState.value(AuraDestination.Queue.route) },
                    left = false,
                )
            }

            Box(Modifier.weight(1f).padding(horizontal = SCREEN_PADDING).offset(y = TRANSPORT_EXTRA), contentAlignment = Alignment.BottomStart) {
                HeroPanel(
                    currentTrack = uiState.currentTrack,
                    queueIndex = uiState.queueIndex,
                    isFavorite = uiState.isFavorite,
                    onFavoriteToggle = viewModel::toggleFavorite,
                    isLiked = isLiked,
                    onLikeToggle = viewModel::toggleLike,
                    automixEnabled = uiState.automixEnabled,
                    onAutomixToggle = viewModel::toggleAutomix,
                    onOpenAlbum = { albumId ->
                        val playing = uiState.currentTrack
                        if (sheetCard.cardTop <= 10f) {
                            if (playing != null && YtmLibrary.isStreamed(playing)) {
                                // A streamed song's album is the source's: the one it came through, or else the one found by its name.
                                albumScope.launch {
                                    val route = viewModel.streamedAlbumRouteOf(playing)
                                    if (route != null) onNavigateFromPlayerState.value(route)
                                    else android.widget.Toast.makeText(albumContext, "Album introuvable", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                onNavigateFromPlayerState.value(AuraDestination.AlbumPattern.route(albumId.toString()))
                            }
                        }
                    },
                    onOpenArtist = { artistId ->
                        val playing = uiState.currentTrack
                        if (sheetCard.cardTop <= 10f) {
                            if (playing != null && YtmLibrary.isStreamed(playing)) {
                                onNavigateFromPlayerState.value(playing.streamedArtistRoute())
                            } else {
                                onNavigateFromPlayerState.value(AuraDestination.ArtistPattern.route(artistId.toString()))
                            }
                        }
                    },
                )
            }

            // Above the playback row in the stacking order: where their touch areas overlap — the transport reaches up under
            // the bar — the bar wins, so a touch on the seek bar can never press pause, next or previous.
            Column(Modifier.zIndex(1f).offset(y = TRANSPORT_EXTRA)) {
                // Nudged down into the bar's own touch margin, so the line reads as sitting on it.
                Box(Modifier.padding(horizontal = SCREEN_PADDING).offset(y = 6.dp)) {
                    LyricStrip(
                        lyricsState = lyricsState,
                        livePosition = viewModel::livePositionMs,
                        isPlaying = uiState.isPlaying,
                        playerActive = active && !leavingSideways,
                        pauseAnimation = { !activeState.value || sheetCard.frozen || leavingSidewaysState.value },
                        // The line being sung is the way into the lyrics.
                        onClick = { if (sheetCard.cardTop <= 10f) onNavigateFromPlayerState.value(AuraDestination.Lyrics.route) },
                    )
                }
                WavyProgressBar(
                    // Providers, read inside the bar: the column around it no longer recomposes on every tick.
                    // While the card is frozen (traveling) or parked, return the frozen timestamp without subscribing
                    // to positionMsState so 1-second boundary crossings never trigger TextStringSimpleNode::measure mid-flight.
                    positionMs = {
                        if (!activeState.value || sheetCard.frozen || leavingSidewaysState.value) {
                            frozenPositionRef[0]
                        } else {
                            positionMsState.value.also { frozenPositionRef[0] = it }
                        }
                    },
                    durationMs = { durationMsState.value },
                    isPlaying = uiState.isPlaying,
                    onSeek = viewModel::seekTo,
                    // The full width of the screen, drawn inset by the page's margin: its touch area reaches the edges
                    // and is kept out of Android's back gesture — grabbing the bar from the side went back instead.
                    edgeInset = SCREEN_PADDING,
                )
            }

            // 8 dp less on the sides: the shuffle and repeat glyphs sit ~13 dp inside their 48 dp touch areas, so they then
            // line up with the page margin.
            Box(Modifier.padding(horizontal = designUnit() * 40f), contentAlignment = Alignment.Center) {
                TransportRow(
                    isPlaying = uiState.isPlaying,
                    shuffleEnabled = uiState.shuffleEnabled,
                    repeatMode = uiState.repeatMode,
                    onShuffleToggle = viewModel::toggleShuffle,
                    onPrevious = viewModel::skipPrevious,
                    onPlayPause = viewModel::togglePlayPause,
                    onNext = viewModel::skipNext,
                    onRepeatCycle = viewModel::cycleRepeatMode,
                )
            }

        }
    }
        }
    QueueSheet(queueSheet, uiState, viewModel, navController)
    }
    }
}

/**
 * The line of synced lyrics being sung, on one line above the progress bar, lighting up word by word.
 *
 * No capsule and no glass: the text alone, left-aligned, cut with an ellipsis when it is too long — never wrapped,
 * so it can never change the height of the row. The row is always reserved, whether or not there is a line in it, so
 * the furniture around it stays put when the lyrics come and go (they pass through Loading on every new track).
 * A new line slides up into place as the old one leaves upward (BitChord's way of turning the page).
 *
 * Shows three dots through an instrumental stretch — what the full lyrics screen draws for a break.
 */
@Composable
private fun LyricStrip(
    lyricsState: LyricsUiState,
    livePosition: () -> Long,
    isPlaying: Boolean,
    playerActive: Boolean,
    pauseAnimation: () -> Boolean,
    onClick: () -> Unit,
) {
    val lines = (lyricsState as? LyricsUiState.Available)?.lines
    val readPos = androidx.compose.runtime.rememberUpdatedState(livePosition)
    val readPaused = androidx.compose.runtime.rememberUpdatedState(pauseAnimation)
    var currentLine by remember(lines) {
        val initialPos = livePosition() + if (isPlaying) com.ikko.aura.ui.components.LYRIC_LEAD_MS else 0L
        androidx.compose.runtime.mutableStateOf(lines?.let { it.getOrNull(it.indexAt(initialPos)) })
    }
    LaunchedEffect(lines, isPlaying, playerActive) {
        if (lines == null || !playerActive) return@LaunchedEffect
        while (true) {
            if (readPaused.value()) {
                kotlinx.coroutines.delay(100L)
                continue
            }
            val pos = readPos.value() + if (isPlaying) com.ikko.aura.ui.components.LYRIC_LEAD_MS else 0L
            val idx = lines.indexAt(pos)
            val line = lines.getOrNull(idx)
            if (currentLine?.timeMs != line?.timeMs) currentLine = line
            val nextStamp = lines.getOrNull(idx + 1)?.timeMs
            val waitMs = if (isPlaying && nextStamp != null) {
                (nextStamp - pos).coerceIn(16L, 150L)
            } else {
                150L
            }
            kotlinx.coroutines.delay(waitMs)
        }
    }
    val clock = rememberLiveLyricClock(
        livePosition,
        isPlaying,
        active = playerActive && isPlaying && currentLine?.isWordSynced == true,
    )
    val frozenClockRef = remember { longArrayOf(clock.longValue) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LYRIC_SLOT_HEIGHT)
            // Opens the full lyrics. No ripple: the page it opens is the answer to the tap.
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                enabled = currentLine != null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = currentLine,
            transitionSpec = {
                // Keyed on the line's stamp below: the enclosing state ticks twice a second, and without it every tick
                // would restart the slide on the same words.
                val slide = tween<IntOffset>(LYRIC_SLIDE_MS, easing = FastOutSlowInEasing)
                val fade = tween<Float>(LYRIC_SLIDE_MS, easing = FastOutSlowInEasing)
                (fadeIn(fade) + slideInVertically(slide) { (it * 0.35f).toInt() })
                    .togetherWith(fadeOut(fade) + slideOutVertically(slide) { -(it * 0.35f).toInt() })
                    .using(SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() })
            },
            contentKey = { it?.timeMs },
            contentAlignment = Alignment.Center,
            label = "lyric-line",
        ) { shown ->
            when {
                shown == null -> Spacer(Modifier.width(1.dp))
                shown.isGap -> Text(
                    text = "• • •",
                    style = MaterialTheme.typography.titleMedium,
                    color = AuraColors.TextSecondary,
                )
                else -> SweepLyricLine(
                    line = shown,
                    positionProvider = {
                        if (readPaused.value()) {
                            frozenClockRef[0]
                        } else {
                            clock.longValue.also { frozenClockRef[0] = it }
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    // BitChord's: unsung words at 55% white, sung ones plain white, the sung part simply filling in.
                    litColor = rememberPlayerAccent(),
                    dimColor = rememberPlayerAccent().copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    plain = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** How far the up-next row scrolls before its start is fully softened. */
private val UP_NEXT_EDGE_RAMP = 24.dp

/** The line sliding up into place; short enough that a quick couplet does not lag behind. */
private const val LYRIC_SLIDE_MS = 340

/** One line of lyric — the row the column always reserves for it. */
private val LYRIC_SLOT_HEIGHT = 32.dp

private fun List<LyricLine>.currentLineAt(positionMs: Long): LyricLine? =
    lastOrNull { it.timeMs <= positionMs }

/**
 * The player's ground, the "F" immersive one: the cover across the top, melting into the record's flat colour, with soft
 * pools of the cover's own colours under the controls and a grain over it all (see [bakeGround]). Drawn once as a still
 * picture per record.
 *
 * Nothing in the player looks through glass: the buttons' frosted look is drawn over this picture (tint, gradient, rim),
 * not blurred from it, so the ground is not recorded for them.
 *
 * The next track's picture is made ahead of time, while this one plays, so a change of track finds it ready; the old one
 * dissolves away over the new.
 */
@Composable
private fun PlayerCoverGround(track: Track?, nextTrack: Track?, solid: Color) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // The picture is baked at the size of the screen, once per record, so that drawing it is one image and nothing else.
    val metrics = context.resources.displayMetrics
    val groundW = metrics.widthPixels
    val groundH = metrics.heightPixels
    val key = track?.albumId?.toString()
    val nextKey = nextTrack?.albumId?.toString()

    var current by remember { mutableStateOf(groundCache.get(key)) }
    var outgoing by remember { mutableStateOf<GroundPicture?>(null) }
    val fade = remember { androidx.compose.animation.core.Animatable(0f) }

    LaunchedEffect(key) {
        if (track == null) return@LaunchedEffect
        val picture = groundCache.get(key) ?: loadGround(context, track, groundW, groundH) ?: return@LaunchedEffect
        if (picture === current) return@LaunchedEffect
        val before = current
        outgoing = before
        current = picture
        if (before != null) {
            fade.snapTo(1f)
            fade.animateTo(0f, tween(GROUND_FADE_MS, easing = FastOutSlowInEasing))
        }
        outgoing = null
    }
    // While this one plays: after the change has settled, so the work is not done in the middle of it.
    LaunchedEffect(nextKey) {
        if (nextTrack == null || groundCache.get(nextKey) != null) return@LaunchedEffect
        kotlinx.coroutines.delay(GROUND_PREFETCH_DELAY_MS)
        loadGround(context, nextTrack, groundW, groundH)
    }

    // The two round buttons at the top take their blurred backdrop from the picture itself (see PlayerRoundButton).
    val holder = LocalGroundPicture.current
    androidx.compose.runtime.SideEffect { if (holder != null && holder.value !== current) holder.value = current }
    Box(Modifier.fillMaxSize().then(if (current == null) Modifier.background(solid) else Modifier)) {
        current?.let { GroundLayer(it) }
        outgoing?.let { old ->
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) { GroundLayer(old) }
        }
    }
}

/** One picture of the ground: a single, already-composed image. */
@Composable
private fun GroundLayer(picture: GroundPicture) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawImage(
                    picture.baked,
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                )
            },
    )
}

/**
 * The ground of one record, composed once into a single image: the aurora over the whole screen with its veil, the cover
 * across the top erased from the bottom up so that it melts into it. Drawn as a loose stack — an image, a gradient, an
 * offscreen layer with an erasing pass — it was several full-screen passes per frame of the card's travel, and that is what
 * made opening and closing the player stutter. As one bitmap it is a single textured quad.
 */
@androidx.compose.runtime.Immutable
private class GroundPicture(val baked: androidx.compose.ui.graphics.ImageBitmap)

private suspend fun loadGround(context: android.content.Context, track: Track, width: Int, height: Int): GroundPicture? {
    val key = track.albumId.toString()
    groundCache.get(key)?.let { return it }
    val cover = AlbumArtCache.peek(key, GROUND_COVER_PX, derive = true)
        ?: AlbumArtCache.load(context, track.artworkUri, GROUND_COVER_PX, key, shrink = true)
    val art = AlbumArtCache.load(context, track.artworkUri, AURORA_ART_PX, key)
    val baked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { bakeGround(art, cover, width, height) }
    // Handed to the GPU now, off the main thread, rather than at the first frame that draws it — inside the opening.
    baked.prepareToDraw()
    return GroundPicture(baked.asImageBitmap()).also { groundCache.put(key, it) }
}

/**
 * The "F" immersive ground, composed once into one image (design measures, 1080 px wide):
 *  - the flat deep colour of the record;
 *  - the cover, square across the top and bleeding off it ([GROUND_COVER_TOP] px);
 *  - the cover melting into that colour (a long eight-stop fade from 300 to 1060 px);
 *  - six large soft pools of the cover's own colours in the lower half, the glow the controls sit on;
 *  - a dark fade at the very top, for the clock and the first row of buttons;
 *  - a fine grain over everything.
 * The pools are drawn as radial gradients (a disc blurred by 110 px, which is what the design draws), so there is no blur
 * to run at draw time.
 */
private fun bakeGround(art: android.graphics.Bitmap?, cover: android.graphics.Bitmap?, w: Int, h: Int): android.graphics.Bitmap {
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val smooth = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    val u = w / 1080f
    // Taller screens than the design's 1240 keep the lower half's measures from the bottom, so the pools stay under the controls.
    val extra = h / u - 1240f

    val look = groundLook(art)
    canvas.drawColor(look.solid)

    // The cover, centre-cropped to a square of the screen's width: sharp at the top, going soft as it goes down (a blurred
    // copy under it, the sharp one dissolving into it), and only then into the record's flat colour — so the melt has no
    // visible block edge.
    if (cover != null) {
        val side = minOf(cover.width, cover.height)
        val src = android.graphics.Rect((cover.width - side) / 2, (cover.height - side) / 2, (cover.width + side) / 2, (cover.height + side) / 2)
        val top = GROUND_COVER_TOP * u
        val square = android.graphics.RectF(0f, top, w.toFloat(), top + w.toFloat())
        // A blurred copy: the picture taken down to a few dozen pixels and drawn back up smoothly, twice.
        val small = runCatching {
            val cropped = android.graphics.Bitmap.createBitmap(cover, src.left, src.top, side, side)
            val a1 = android.graphics.Bitmap.createScaledBitmap(cropped, 48, 48, true)
            android.graphics.Bitmap.createScaledBitmap(a1, 18, 18, true)
        }.getOrNull()
        if (small != null) {
            canvas.drawBitmap(small, null, square, smooth)
            // A veil of the flat colour over the blurred copy, so it reads as a soft wash and not a second picture.
            val wash = android.graphics.Paint().apply { color = (look.solid and 0x00FFFFFF) or (0x55 shl 24) }
            canvas.drawRect(0f, 0f, w.toFloat(), square.bottom, wash)
        }
        // The sharp cover, dissolving from 25 % down to 70 % of the square.
        val layer = canvas.saveLayer(0f, 0f, w.toFloat(), square.bottom, null)
        canvas.drawBitmap(cover, src, square, smooth)
        val mask = android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            shader = android.graphics.LinearGradient(
                0f, top + 0.18f * w, 0f, top + 0.78f * w,
                intArrayOf(-1, 0xE0FFFFFF.toInt(), 0x90FFFFFF.toInt(), 0x40FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.25f, 0.5f, 0.78f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), square.bottom, mask)
        canvas.restoreToCount(layer)
    }

    // The melt into the flat colour: longer and later, over the blurred part.
    val fadeTop = 520f * u
    val fadeBottom = 1120f * u
    val c = look.solid
    fun withAlpha(a: Float) = (c and 0x00FFFFFF) or ((a * 255f).toInt().coerceIn(0, 255) shl 24)
    val fade = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, fadeTop, 0f, fadeBottom,
            intArrayOf(withAlpha(0f), withAlpha(0.10f), withAlpha(0.28f), withAlpha(0.52f), withAlpha(0.76f), withAlpha(0.92f), withAlpha(1f)),
            floatArrayOf(0f, 0.18f, 0.36f, 0.55f, 0.74f, 0.9f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, fadeTop, w.toFloat(), fadeBottom, fade)
    // Everything below the fade is the flat colour (a taller screen than the design's).
    val flat = android.graphics.Paint().apply { color = c }
    canvas.drawRect(0f, fadeBottom, w.toFloat(), h.toFloat(), flat)

    // The pools of light: left, top, size, opacity, in design px.
    val pools = arrayOf(
        floatArrayOf(-290f, 410f, 820f, 0.85f),
        floatArrayOf(600f, 460f, 760f, 0.80f),
        floatArrayOf(90f, 610f, 900f, 0.75f),
        floatArrayOf(-310f, 830f, 800f, 0.90f),
        floatArrayOf(600f, 840f, 800f, 0.90f),
        floatArrayOf(240f, 930f, 640f, 0.50f),
    )
    pools.forEachIndexed { i, p ->
        val size = p[2] * u
        val cx = (p[0] + p[2] / 2f) * u
        val cy = (p[1] + extra + p[2] / 2f) * u
        val r = size / 2f
        val reach = r + 110f * u
        val colour = look.pools[i % look.pools.size]
        fun a(f: Float) = (colour and 0x00FFFFFF) or ((p[3] * f * 255f).toInt().coerceIn(0, 255) shl 24)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                cx, cy, reach,
                intArrayOf(a(1f), a(0.96f), a(0.55f), a(0.14f), a(0f)),
                floatArrayOf(0f, (r - 70f * u) / reach, r / reach, (r + 55f * u) / reach, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, reach, paint)
    }

    // The dark fade at the top: the clock, the battery and the first row of buttons read over any cover.
    val scrim = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, 0f, 260f * u, 0x6B000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, w.toFloat(), 260f * u, scrim)

    // The grain, overlaid at 14 %.
    val grain = android.graphics.Paint().apply {
        shader = android.graphics.BitmapShader(grainTile, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.OVERLAY)
        alpha = (0.14f * 255f).toInt()
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), grain)
    return out
}

/** A fixed tile of grey noise, repeated over the ground. */
private val grainTile: android.graphics.Bitmap by lazy {
    val rnd = java.util.Random(11)
    val px = IntArray(256 * 256) { val v = rnd.nextInt(256); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
    android.graphics.Bitmap.createBitmap(px, 256, 256, android.graphics.Bitmap.Config.ARGB_8888)
}

private class GroundLook(val solid: Int, val pools: IntArray)

/**
 * The flat colour of the record and the colours its pools of light are made of, from the cover itself: the pools are its
 * own swatches (the most present first), kept deep enough that white type reads on them, and the flat colour is the
 * cover's overall hue taken dark — the one the rest of the app rests on. A cover with no colour gets a grey-blue.
 */
private fun groundLook(art: android.graphics.Bitmap?): GroundLook {
    val swatches = art?.let {
        runCatching {
            androidx.palette.graphics.Palette.from(it).maximumColorCount(24).resizeBitmapArea(64 * 64).generate().swatches
        }.getOrNull()
    }.orEmpty()
        .filter { s -> FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(s.rgb, it) }.let { it[1] > 0.18f && it[2] in 0.08f..0.92f } }
        .sortedByDescending { it.population }
    // The flat colour: the most present coloured swatch taken dark (the quick way: no aurora is rendered for it, so a
    // change of record is not waiting on one). A cover with no colour gets a grey-blue.
    val solid = swatches.firstOrNull()?.rgb?.let { rgb ->
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(rgb, hsl)
        hsl[1] = hsl[1].coerceIn(0.30f, 0.60f)
        hsl[2] = 0.16f
        androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    } ?: 0xFF1B2230.toInt()
    fun tone(rgb: Int, lightness: Float): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(rgb, hsl)
        hsl[1] = hsl[1].coerceIn(0.35f, 0.72f)
        hsl[2] = lightness
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }
    val base = swatches.map { it.rgb }.ifEmpty { listOf(solid) }
    // Alternating mid and deep tones of the cover's colours, as the design's pools alternate teal, blue, plum, red.
    val lightness = floatArrayOf(0.25f, 0.31f, 0.22f, 0.20f, 0.27f, 0.36f)
    val pools = IntArray(6) { i -> tone(base[i % base.size], lightness[i]) }
    return GroundLook(solid, pools)
}

/**
 * Makes the player's ground for the playing record, and the next, while nobody is looking: so that when the player opens
 * it is already there, and the opening carries none of the decoding, the aurora, the composing or the upload. Lives with
 * the app's layout, not with the player (which does not exist yet at that point).
 */
@Composable
fun PlayerGroundWarmer(playerViewModel: PlayerViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val metrics = context.resources.displayMetrics
    val tracks by remember(playerViewModel) {
        playerViewModel.uiState
            .map { it.currentTrack to it.queue.getOrNull(it.queueIndex + 1) }
            .distinctUntilChanged { a, b -> a.first?.albumId == b.first?.albumId && a.second?.albumId == b.second?.albumId }
    }.collectAsStateWithLifecycle(null to null)
    LaunchedEffect(tracks) {
        // Once the track change has settled, and the rest of the screen with it.
        kotlinx.coroutines.delay(GROUND_PREFETCH_DELAY_MS)
        tracks.first?.let { loadGround(context, it, metrics.widthPixels, metrics.heightPixels) }
        tracks.second?.let { loadGround(context, it, metrics.widthPixels, metrics.heightPixels) }
    }
}

/** The last few pictures, by album: the next one made ahead, and the ones just left, so going back is free. */
private val groundCache = object {
    private val map = object : LinkedHashMap<String, GroundPicture>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GroundPicture>?) = size > 4
    }
    @Synchronized fun get(key: String?): GroundPicture? = key?.let { map[it] }
    @Synchronized fun put(key: String, picture: GroundPicture) { map[key] = picture }
}

/** The dissolve from one picture to the next. */
private const val GROUND_FADE_MS = 500

/** Long enough that the work is not done in the middle of a track change. */
private const val GROUND_PREFETCH_DELAY_MS = 600L

/** The size the cover is read at for the ground: matches PlayerViewModel.NOTIFICATION_COVER_PX so the cache hits 100%. */
private const val GROUND_COVER_PX = 1024

/** The size a cover is read at to make its aurora: it is scaled to 64 px and blurred, so no more is needed. */
private const val AURORA_ART_PX = 192

/** Where the cover's square starts, in design px: it bleeds off the top, as the design draws it. */
private const val GROUND_COVER_TOP = -70f

/** Cover, title and artist — the mockup's dominant block. */
@Composable
private fun HeroPanel(
    currentTrack: Track?,
    queueIndex: Int,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (Long) -> Unit,
    isFavorite: Boolean,
    onFavoriteToggle: () -> Unit,
    isLiked: Boolean,
    onLikeToggle: () -> Unit,
    automixEnabled: Boolean,
    onAutomixToggle: () -> Unit,
) {
    // The cover, title and artist slide through the queue as one block across the panel's whole width.
    var panelWidthPx by remember { mutableIntStateOf(0) }
    // No glass behind the cover and the names any more: they sit straight on the page, over the dark fade at its top.
    Box(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { panelWidthPx = it.width }
            // No card around it, so no padding of its own: the text starts on the page margin, the same line the
            // progress bar starts on.
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The title and artist sit at the foot of the cover, on the part of it that has melted into the flat colour,
        // the artist right under the title; a title that needs a second line takes it.
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
            AnimatedContent(
                targetState = queueIndex to currentTrack,
                transitionSpec = {
                    (fadeIn(tween(150)) togetherWith fadeOut(tween(150)))
                        .using(SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() })
                },
                contentAlignment = Alignment.BottomStart,
                label = "hero-label",
            ) { (_, shown) ->
                // The names at the left, the toolbar (Automix, favourite) at the right, its top at the title's.
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(designUnit() * 25f)) {
                    Column(Modifier.weight(1f)) {
                        // Three destinations from one block: the title opens the album it is on, the artist opens the artist.
                        // Each is the thing the words already name, so nothing needs a chevron to say so.
                        Text(
                            text = shown?.title ?: "Aucun titre",
                            style = MaterialTheme.typography.titleLarge.copy(
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    Color.Black.copy(alpha = 0.35f), Offset(0f, 4f), 24f,
                                ),
                            ),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = designSp(60f),
                            lineHeight = designSp(68f),
                            letterSpacing = designSp(60f * -0.02f),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .then(
                                    if (shown == null) {
                                        Modifier
                                    } else {
                                        Modifier.clickable { onOpenAlbum(shown.albumId) }
                                    },
                                ),
                        )
                        Text(
                            text = shown?.artist ?: "—",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.3f), Offset(0f, 3f), 16f),
                            ),
                            fontWeight = FontWeight.Bold,
                            fontSize = designSp(38f),
                            lineHeight = designSp(46f),
                            color = rememberPlayerAccent().copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = designUnit() * 6f)
                                .clip(RoundedCornerShape(6.dp))
                                .then(
                                    if (shown == null) {
                                        Modifier
                                    } else {
                                        Modifier.clickable { onOpenArtist(shown.artistId) }
                                    },
                                ),
                        )
                    }
                    PlayerToolbar(
                        automixEnabled = automixEnabled,
                        onAutomixToggle = onAutomixToggle,
                        isLiked = isLiked,
                        onLikeToggle = onLikeToggle,
                        isFavorite = isFavorite,
                        onFavoriteToggle = onFavoriteToggle,
                        modifier = Modifier.offset(y = designUnit() * -8f),
                    )
                }
            }
        }

        }    }
}

@Composable
private fun TransportRow(
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onShuffleToggle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeatCycle: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val u = designUnit()
    // As drawn: the glyphs' centre line 69 design px above the row's foot; shuffle and repeat in 100 px areas, previous and
    // next in 120, the frosted play button 136 across — spread over the width with equal gaps.
    val glyphCenter = u * 69f
    Row(
        modifier = Modifier.fillMaxWidth().height(TRANSPORT_HEIGHT),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FlankIcon {
            UtilityIcon(
                icon = CupertinoIcons.Shuffle,
                contentDescription = "Lecture aléatoire",
                active = shuffleEnabled,
                onClick = onShuffleToggle,
                glyphSize = 21.dp,
                touchWidth = u * 100f,
                touchHeight = glyphCenter * 2,
                dot = true,
            )
        }
        TransportTile(
            width = u * 120f,
            glyphCenter = glyphCenter,
            glyphSize = 34.dp,
            icon = CupertinoIcons.SkipPrevious,
            contentDescription = "Précédent",
            skipDirection = -1,
            onClick = onPrevious,
        )
        TransportTile(
            width = u * 136f,
            glyphCenter = glyphCenter,
            glyphSize = 34.dp,
            icon = if (isPlaying) CupertinoIcons.Pause else CupertinoIcons.Play,
            playing = isPlaying,
            contentDescription = if (isPlaying) "Pause" else "Lecture",
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onPlayPause()
            },
        )
        TransportTile(
            width = u * 120f,
            glyphCenter = glyphCenter,
            glyphSize = 34.dp,
            icon = CupertinoIcons.SkipNext,
            contentDescription = "Suivant",
            skipDirection = 1,
            onClick = onNext,
        )
        FlankIcon {
            UtilityIcon(
                icon = if (repeatMode == RepeatMode.ONE) CupertinoIcons.RepeatOne else CupertinoIcons.Repeat,
                contentDescription = "Répéter",
                active = repeatMode != RepeatMode.OFF,
                onClick = onRepeatCycle,
                glyphSize = 21.dp,
                touchWidth = u * 100f,
                touchHeight = glyphCenter * 2,
                dot = true,
            )
        }
    }
}

/** The shuffle and repeat icons, with their glyphs on the same line as the main controls' (their areas share the tall row). */
@Composable
private fun FlankIcon(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxHeight(),
        contentAlignment = Alignment.BottomCenter,
    ) { content() }
}

@Composable
private fun RowScope.TransportTile(
    width: Dp,
    /** How far above the row's foot the glyph's centre line is. */
    glyphCenter: Dp,
    glyphSize: Dp,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    glyphColor: Color = rememberPlayerAccent(),
    /** 1 for "next", -1 for "previous": the two triangles then chase on a press, as on iOS. 0 for the play button. */
    skipDirection: Int = 0,
    /** For the play / pause tile: whether it is playing. The glyph then morphs between the two instead of swapping. */
    playing: Boolean? = null,
    /** The frosted round button behind the glyph (the play button). */
    framed: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // The press, as recorded: a soft round halo shows at once under the finger and fades out after it; on skip buttons the
    // two triangles run one slot along (a new one comes in behind, the front one leaves), on play the glyph dims a little.
    val halo = remember { androidx.compose.animation.core.Animatable(0f) }
    val chase = remember { androidx.compose.animation.core.Animatable(1f) }
    // The chase runs to its end even when the finger is up before it: it has an effect of its own, started by the press.
    var pressCount by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(pressed) {
        if (pressed) {
            pressCount++
            halo.snapTo(HALO_ALPHA)
        } else {
            halo.animateTo(0f, tween(HALO_FADE_MS))
        }
    }
    androidx.compose.runtime.LaunchedEffect(pressCount) {
        if (pressCount > 0 && skipDirection != 0) {
            chase.snapTo(0f)
            chase.animateTo(1f, tween(CHASE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }

    // Just the glyph: no glass, no fill, no outline — the tile is only the touch area around it.
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clip(ButtonShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        if (framed) {
            // The button the glyph sits in: 136 design px across, centred on the glyph.
            Box(
                Modifier
                    .padding(bottom = glyphCenter - width / 2)
                    .size(width)
                    .playerFrosted(CircleShape),
            )
        }
        val glyphModifier = Modifier.padding(bottom = glyphCenter - glyphSize / 2).size(glyphSize)
        // The halo is drawn behind the glyph, centred on it, about twice its size.
        Box(
            modifier = glyphModifier.drawBehind {
                val a = halo.value
                if (a > 0.002f) drawCircle(Color.White.copy(alpha = a), radius = size.minDimension * 1.3f / 2f * 1.0f)
            },
            contentAlignment = Alignment.Center,
        ) {
            if (skipDirection != 0) {
                SkipGlyph(
                    forward = skipDirection > 0,
                    color = glyphColor,
                    progress = { chase.value },
                    modifier = Modifier.fillMaxSize().semantics { this.contentDescription = contentDescription },
                )
            } else if (playing != null) {
                // As recorded: the glyph sinks a little under the finger; on the change, the one leaving shrinks to a third of
                // its size and fades while the other grows out of that same small size and lights up. Pause arriving is quick
                // (about 0.22 s), play arriving a little slower (about 0.34 s).
                val morph by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (playing) 1f else 0f,
                    animationSpec = tween(if (playing) 220 else 340, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    label = "play-pause-morph",
                )
                val sink by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (pressed) 0.88f else 1f,
                    animationSpec = tween(90),
                    label = "play-sink",
                )
                val pauseScale = (0.35f + 0.65f * morph) * sink
                val playScale = (1f - 0.65f * morph) * sink
                Icon(
                    imageVector = CupertinoIcons.Play,
                    contentDescription = if (playing) null else contentDescription,
                    tint = glyphColor,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = playScale; scaleY = playScale
                        alpha = (1f - morph).coerceIn(0f, 1f) * (if (pressed) 0.9f else 1f)
                    },
                )
                Icon(
                    imageVector = CupertinoIcons.Pause,
                    contentDescription = if (playing) contentDescription else null,
                    tint = glyphColor,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = pauseScale; scaleY = pauseScale
                        alpha = morph.coerceIn(0f, 1f) * (if (pressed) 0.9f else 1f)
                    },
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = glyphColor,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}




/** The dark wash under the player's frosted buttons: the record's flat colour, taken darker, at about a third. */
private val LocalPlayerGlassTint = androidx.compose.runtime.staticCompositionLocalOf { Color(0x52081824) }

/** The ground picture now showing, so the two round buttons can look through to a blurred piece of it. */
private val LocalGroundPicture = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableState<GroundPicture?>?> { null }

private val FrostedWhiteGradient = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.08f)))

/**
 * The design's frosted button, drawn over the ground (nothing is blurred: the player's ground is a still picture):
 * a soft shadow, a dark wash, a white gradient from 22 % to 8 %, a rim at 28 % and a light line along the top edge.
 */
@Composable
private fun Modifier.playerFrosted(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val u = designUnit()
    val tint = LocalPlayerGlassTint.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rim = with(density) { (u * 1.5f).toPx() }
    val line = with(density) { (u * 2f).toPx() }
    return this
        .androidx_shadow(u * 10f, shape)
        .clip(shape)
        .background(tint)
        .background(FrostedWhiteGradient)
        .drawWithContent {
            drawContent()
            drawGlassRim(shape, rim, line)
        }
}

/** The rim at 28 % all round, and a light line along the top that fades out down the sides instead of being cut. */
private fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.drawGlassRim(
    shape: androidx.compose.ui.graphics.Shape,
    rim: Float,
    line: Float,
) {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, Color.White.copy(alpha = 0.26f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = rim * 2f))
    drawOutline(
        outline,
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.42f),
            0.22f to Color.White.copy(alpha = 0.14f),
            0.5f to Color.Transparent,
            startY = 0f, endY = size.height,
        ),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = line * 2f),
    )
}

private fun Modifier.androidx_shadow(elevation: Dp, shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.22f))

/**
 * A round frosted button of the design, 112 across, its glyph 44: what is behind it is the ground's own picture, blurred
 * by 26 px and saturated 1.6 times (the design's backdrop-filter), under the dark wash, the white gradient, the rim and the
 * light along the top. Only these two buttons look through; the picture they take is the one at their place, drawn behind
 * them, so nothing is recorded.
 */
@Composable
private fun PlayerRoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    left: Boolean,
    modifier: Modifier = Modifier,
) {
    val u = designUnit()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val metrics = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
    val picture = LocalGroundPicture.current?.value
    val diameter = u * 112f
    val sizePx = with(density) { diameter.roundToPx() }
    val unitPx = metrics.widthPixels / 1080f
    // Where the button sits on the screen, as drawn: 40 px from its edge, 110 from the top.
    val originX = if (left) (40f * unitPx).toInt() else metrics.widthPixels - (40f * unitPx).toInt() - sizePx
    val originY = (110f * unitPx).toInt()
    // Blur only the local patch around the button (sizePx + 3*sigma margin on each side) rather than a full 1080x1240 FBO.
    val padPx = (78f * unitPx).toInt()
    val patchSize = sizePx + 2 * padPx
    val blur = remember(unitPx) {
        val blurEffect = android.graphics.RenderEffect.createBlurEffect(26f * unitPx, 26f * unitPx, android.graphics.Shader.TileMode.CLAMP)
        val saturate = android.graphics.RenderEffect.createColorFilterEffect(
            android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(1.6f) }),
            blurEffect,
        )
        saturate.asComposeRenderEffect()
    }
    val tint = LocalPlayerGlassTint.current
    val rimPx = with(density) { (u * 1.5f).toPx() }
    val linePx = with(density) { (u * 2f).toPx() }
    Box(
        modifier = modifier
            .size(diameter)
            .androidx_shadow(u * 10f, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(
                            androidx.compose.ui.unit.Constraints.fixed(patchSize, patchSize),
                        )
                        layout(sizePx, sizePx) { placeable.place(-padPx, -padPx) }
                    }
                    .graphicsLayer { renderEffect = blur }
                    .drawBehind {
                        drawImage(
                            picture.baked,
                            dstOffset = androidx.compose.ui.unit.IntOffset(-(originX - padPx), -(originY - padPx)),
                            dstSize = androidx.compose.ui.unit.IntSize(metrics.widthPixels, metrics.heightPixels),
                        )
                    },
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(tint)
                .background(FrostedWhiteGradient)
                .drawWithContent {
                    drawContent()
                    drawGlassRim(CircleShape, rimPx, linePx)
                },
        )
        Icon(icon, contentDescription = contentDescription, tint = rememberPlayerAccent(), modifier = Modifier.size(u * 44f))
    }
}

/** The title's two actions, Automix then the favourite: the glyphs alone, on the ground. */
@Composable
private fun PlayerToolbar(
    automixEnabled: Boolean,
    onAutomixToggle: () -> Unit,
    isLiked: Boolean,
    onLikeToggle: () -> Unit,
    isFavorite: Boolean,
    onFavoriteToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val u = designUnit()
    val accent = rememberPlayerAccent()
    Row(
        modifier = modifier.height(u * 112f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(u * 104f, u * 100f).clip(CircleShape).clickable(onClick = onAutomixToggle),
            contentAlignment = Alignment.Center,
        ) {
            FillableIcon(
                outline = CupertinoIcons.AutoAwesome,
                filled = CupertinoIcons.AutoAwesome,
                active = automixEnabled,
                contentDescription = "Automix",
                tint = if (automixEnabled) accent else accent.copy(alpha = 0.75f),
                modifier = Modifier.size(u * 44f),
            )
        }
        // The like: tells the recommendations "more of this" (kept on the device); it is not the favourite, which is a playlist.
        Box(
            Modifier.size(u * 104f, u * 100f).clip(CircleShape).clickable(onClick = onLikeToggle),
            contentAlignment = Alignment.Center,
        ) {
            FillableIcon(
                outline = CupertinoIcons.Heart,
                filled = CupertinoIcons.HeartFilled,
                active = isLiked,
                contentDescription = if (isLiked) "Je n'aime plus" else "J'aime",
                tint = if (isLiked) accent else accent.copy(alpha = 0.9f),
                modifier = Modifier.size(u * 44f),
            )
        }
        Box(
            Modifier.size(u * 104f, u * 100f).clip(CircleShape).clickable(onClick = onFavoriteToggle),
            contentAlignment = Alignment.Center,
        ) {
            FillableIcon(
                outline = CupertinoIcons.Star,
                filled = CupertinoIcons.StarFilled,
                active = isFavorite,
                contentDescription = "Favori",
                tint = if (isFavorite) accent else accent.copy(alpha = 0.9f),
                modifier = Modifier.size(u * 44f),
            )
        }
    }
}

@Composable
private fun UtilityIcon(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    activeTint: Color = rememberPlayerAccent(),
    filledIcon: ImageVector = icon,
    /** The glyph's own size: the touch area stays 48 dp. A little different per icon, to even out how heavy they look. */
    glyphSize: Dp = 21.dp,
    /** The touch area (the glyph is centred in it). */
    touchWidth: Dp = 48.dp,
    touchHeight: Dp = 48.dp,
    /** A small dot under the glyph while the mode is on (shuffle, repeat), as drawn. */
    dot: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(touchWidth, touchHeight)
            .clip(ChipShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FillableIcon(
            outline = icon,
            filled = filledIcon,
            active = active,
            contentDescription = contentDescription,
            tint = if (active) activeTint else rememberPlayerAccent().copy(alpha = 0.64f),
            modifier = Modifier.size(glyphSize),
        )
        if (dot && active) {
            Box(
                Modifier
                    .offset(y = 30.dp)
                    .size(designUnit() * 8f)
                    .background(activeTint, CircleShape),
            )
        }
    }
}

private fun AudioOutputType.label(): String = when (this) {
    AudioOutputType.PHONE_SPEAKER -> "Haut-parleur"
    AudioOutputType.WIRED_HEADSET -> "Casque filaire"
    AudioOutputType.BLUETOOTH -> "Bluetooth"
    AudioOutputType.USB_DAC -> "DAC USB-C"
}

/**
 * The audio output picker, styled as a [DesignMenu] anchored to the headphone button.
 * "Automatique" is a first-class choice, not the absence of one. Picking a device pins playback
 * to it; the system's own routing — which follows a headset being plugged in — is what Aura does
 * otherwise, and there has to be a way back to it.
 */
@Composable
private fun AudioOutputPicker(
    state: GlassMenuState,
    outputs: List<AudioOutput>,
    selectedDeviceId: Int?,
    activeName: String?,
    onSelect: (Int?) -> Unit,
) {
    val accent = rememberPlayerAccent()
    DesignMenu(
        state = state,
        width = 250.dp,
        shade = 0.24f,
        alignStart = true,
    ) {
        val choose: (Int?) -> () -> Unit = { id -> { state.close(); onSelect(id) } }
        DesignMenuRow(
            label = "Automatique",
            subtitle = activeName?.takeIf { selectedDeviceId == null },
            icon = CupertinoIcons.Headphones,
            selected = selectedDeviceId == null,
            accent = accent,
            onClick = choose(null),
        )
        if (outputs.isNotEmpty()) {
            DesignMenuDivider()
        }
        outputs.forEach { output ->
            val icon = when (output.type) {
                AudioOutputType.BLUETOOTH -> CupertinoIcons.Headphones
                AudioOutputType.WIRED_HEADSET -> CupertinoIcons.Headphones
                AudioOutputType.USB_DAC -> CupertinoIcons.MusicNote
                AudioOutputType.PHONE_SPEAKER -> CupertinoIcons.Headphones
            }
            DesignMenuRow(
                label = output.name,
                subtitle = if (output.supportsHiRes) "Haute résolution" else null,
                icon = icon,
                selected = output.deviceId == selectedDeviceId,
                accent = accent,
                onClick = choose(output.deviceId),
            )
        }
    }
}
