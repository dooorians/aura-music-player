package com.ikko.aura.ui.screens.nowplaying

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
import com.ikko.aura.ui.components.AURORA_PIXELS
import com.ikko.aura.ui.components.AURORA_H
import com.ikko.aura.ui.components.AURORA_W
import com.ikko.aura.ui.components.neutralArt
import com.ikko.aura.ui.components.AuroraRenderer
import com.ikko.aura.ui.components.AlbumTheme
import com.ikko.aura.ui.components.auraMenuSurface
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.SweepLyricLine
import com.ikko.aura.ui.components.WavyProgressBar
import com.ikko.aura.ui.components.indexAt
import com.ikko.aura.ui.components.rememberLiveLyricClock
import com.ikko.aura.ui.components.LiquidGlassPanel
import com.ikko.aura.ui.components.horizontalEdgeBlur
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

private val SCREEN_PADDING = 14.dp
/** How far down the screen the cover reaches before it has melted into the flat colour: about to the progress bar. */
private const val COVER_HEIGHT_FRACTION = 0.58f

/** The gap between the groups of the player's controls: the bar and its lyric, the transport, the utility row. */
private val GROUP_GAP = 6.dp

private val HERO_ART_SIZE = 120.dp
/**
 * The touch area of the playback controls, made for a thumb: as tall as the glyphs' neighbourhood allows, side by side with no
 * gap between them, and reaching down to the bottom edge. The extra is mostly *above* the glyphs, because that is where the
 * finger lands. The glyphs themselves sit where they always did, [TRANSPORT_GLYPH_CENTER] above the row's bottom.
 */
private val TRANSPORT_HEIGHT = 96.dp
private val TRANSPORT_GLYPH_CENTER = 40.dp

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
    /** The record's own deep colour, for the card's face. */
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lyricsState by lyricsViewModel.uiState.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.durationMs.collectAsStateWithLifecycle()

    var showOutputPicker by remember { mutableStateOf(false) }
    val queueSheet = rememberQueueSheetState()

    LaunchedEffect(uiState.currentTrack?.id) {
        lyricsViewModel.loadFor(uiState.currentTrack)
    }

    if (showOutputPicker) {
        AudioOutputPicker(
            outputs = uiState.audioOutput.available,
            selectedDeviceId = uiState.audioOutput.selectedDeviceId,
            activeName = uiState.audioOutput.deviceName,
            onSelect = {
                viewModel.selectAudioOutput(it)
                showOutputPicker = false
            },
            onDismiss = { showOutputPicker = false },
        )
    }

    // The player as a card, in Apple's way (see AuraMotion). [sheetCard] says where the card's top edge is,
    // as a fraction of the screen's height — 0 up, 1 no card — and the rest of the app arranges itself
    // around it: the page under the card is drawn only above that edge, and the mini player and the tab
    // capsule stay away while it covers them.
    //
    // This screen owns it for as long as it is composed, and gives it back when it leaves. One animation
    // drives it, keyed on what the card is doing, so a close started while the open was still running
    // cancels the open rather than fighting it for the same number.
    // The player is six panels of live glass, and each of them works out a fresh sample of the backdrop
    // whenever it moves — six blur-and-lens chains on every frame of the travel, which is what made the card
    // arrive and leave in steps. While it travels they are held still instead, at the picture they will rest
    // with: see [GlassSettle].
    val settle = remember { GlassSettle() }
    var cardHeightPx by remember { mutableIntStateOf(0) }
    // Set the first time this screen actually draws. Composing the player takes several frames, and a card
    // that started rising before then was an empty grey rectangle for the first of them, filling in
    // afterwards — which is the blink one sees on opening. Not snapshot state: it is written from the draw.
    val drawnOnce = remember { MutableStateFlow(false) }
    // Whether the card has arrived at the top. Until then it takes no touches (see the card's pointerInput).
    var landed by remember { mutableStateOf(!sheetOpening) }

    val token = remember { Any() }
    // Whether the card is shown at no opacity at all: a close has taken it all the way into the mini player (the layer
    // alpha below is then exactly 0) — or whether another player has taken the card. A closed player is still composed
    // for a moment after its close, and a tap on the mini player in that moment opened a new one: its claim set the card
    // back to not ready, this one drew again, and its last picture (the time, the wave and the lyric of the close)
    // rode up over the new player until the navigation let it go. Derived, so the draw that reads it is invalidated
    // once, when it flips, and not on every frame of the fade.
    val cardCleared by remember {
        derivedStateOf { (sheetCard.ready && sheetCard.cardAlpha <= 0f) || sheetCard.owner !== token }
    }
    remember(token) {
        // Opening from the mini player, the card stays hidden until this screen has drawn (see
        // SheetCard.ready): the mini player holds the place until then.
        sheetCard.claim(
            token,
            if (sheetOpening) AuraMotion.SHEET_PILL_TOP_FRACTION else 0f,
            ready = !sheetOpening,
        )
        token
    }
    DisposableEffect(token) { onDispose { sheetCard.release(token) } }

    // Set the moment this screen itself closes the player (the swipe down, the back button), so the card starts
    // going down at once rather than whenever the navigation has worked out what its own pop was. Deduced from
    // the navigation alone, the close waited 100–190 ms for the page under it to be rebuilt and this screen to
    // be recomposed (latency), and could be read as "leaving sideways" if that recomposition came before the
    // navigation's own transition had said it was a close (no animation at all).
    var closeRequested by remember { mutableStateOf(false) }
    fun close() {
        if (closeRequested) return
        closeRequested = true
        navController.popBackStack()
    }
    val phase = when {
        closeRequested || sheetClosing -> SheetPhase.Closing
        // Left for a page of its own (an album, the queue, the lyrics): there is no card any more, and the
        // player leaves whole, by its own sideways transition.
        leavingSideways -> SheetPhase.Away
        else -> SheetPhase.Settled
    }
    settle.isClosing = phase == SheetPhase.Closing || phase == SheetPhase.Away
    // The system back button, the same way: without this it went to NavHost's own pop and waited like the swipe.
    BackHandler(enabled = phase == SheetPhase.Settled && sheetCard.owner === token) { close() }
    LaunchedEffect(phase) {
        if (phase != SheetPhase.Settled) {
            queueSheet.snapClosed()
            sheetCard.markReady()
        }
        sheetCard.sinking = phase == SheetPhase.Closing
        when (phase) {
            SheetPhase.Away -> sheetCard.edge = 1f
            SheetPhase.Closing -> {
                // Down only once there is a page under the card to be uncovered. The page is let go shortly after
                // the player opens (AuraMotion.SHEET_HOLD_MS) and is composed again by the pop: that takes a few
                // frames, and a card already on its way down would have shown nothing around it meanwhile.
                // Usually ~60 ms; bounded, so the card always goes.
                withTimeoutOrNull(PAGE_WAIT_MS) {
                    snapshotFlow { sheetCard.pagesComposed }.first { it > 0 }
                    withFrameNanos { }
                }
                // Already at rest, so the picture the glass has is the one to keep: nothing to compensate.
                // In a try/finally: a close interrupted mid-flight (opened again, or left sideways, before
                // it finished) cancels this coroutine inside animate(), and without the finally the glass
                // was left frozen for good — a stuck picture that never went live again.
                settle.frozen = true
                sheetCard.frozen = true
                try {
                    animate(
                        initialValue = sheetCard.edge.coerceAtMost(AuraMotion.SHEET_PILL_TOP_FRACTION),
                        targetValue = AuraMotion.SHEET_PILL_TOP_FRACTION,
                        animationSpec = tween(AuraMotion.SHEET_EXIT_MS, easing = AuraMotion.EaseInOut),
                    ) { value, _ -> sheetCard.edge = value }
                } finally {
                    settle.frozen = false
                    sheetCard.frozen = false
                }
            }
            SheetPhase.Settled -> if (sheetCard.edge > 0f && sheetCard.edge < 1f) {
                // Not before this screen has drawn: composing the player takes several frames, and an
                // animation begun in the meantime spends its first moves unseen and then jumps.
                // The first draw can still be a half-built screen, so a couple of frames more: what rises has
                // to be the finished player, never an empty card that fills in afterwards.
                // Bounded, so a card can never stay hidden: past it the player shows as it is.
                withTimeoutOrNull(READY_TIMEOUT_MS) {
                    drawnOnce.first { it }
                    repeat(3) { withFrameNanos { } }
                }
                // One frame drawn as the card will rest, then held there for the whole rise: the glass samples
                // exactly where each panel will rest (see GlassSettle.restShift), so the picture it holds is
                // the final one and letting go at the end changes nothing.
                settle.offset = Offset(0f, sheetCard.edge * cardHeightPx)
                withFrameNanos { }
                settle.frozen = true
                sheetCard.frozen = true
                try {
                    animate(
                        initialValue = sheetCard.edge,
                        targetValue = 0f,
                        animationSpec = spring(1f, AuraMotion.SHEET_STIFFNESS, 0.0002f),
                    ) { value, _ ->
                        sheetCard.edge = value
                        // Shown — and the mini player gone — in the first frame the card has actually moved,
                        // never sitting still at the pill's place: all the waiting (drawing, rendering, taking
                        // the glass's resting picture) happened while it was still unseen.
                        if (!sheetCard.ready && value < AuraMotion.SHEET_PILL_TOP_FRACTION) {
                            sheetCard.markReady()
                        }
                    }
                } finally {
                    settle.frozen = false
                    sheetCard.frozen = false
                    settle.offset = Offset.Zero
                    sheetCard.markReady()
                    landed = true
                }
            }
        }
    }
    CompositionLocalProvider(LocalGlassSettle provides settle) {
    Box(
        Modifier
            .fillMaxSize()
            // Where the card's content sits when the card is at rest (outside its movement), and the content
            // itself (inside it): what the glass needs to sample where it will rest. See GlassSettle.
            .onGloballyPositioned { settle.restOrigin = it.positionInWindow() }
            .graphicsLayer {
                val o = sheetCard.edge.let { if (it >= 1f) 0f else it }
                translationY = o * size.height
                val s = 1f - AuraMotion.SHEET_NARROWING * o
                scaleX = s
                scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                // Top corners: rounded while it is a card, straightening as it comes up; on the way down they
                // settle to the pill's own. The bottom ones are rounded too, and never seen (the card's bottom is below
                // the screen's): the same radius on all four is a shape the GPU clips by itself, where top-only
                // corners made Skia draw a mask on the CPU and upload it on every frame of the travel.
                val corner = AuraMotion.cornerAt(o).toPx()
                shape = RoundedCornerShape(corner)
                clip = o > 0.0005f
                // Gone by the time it reaches the mini player, which takes its place — on the way down only.
                // Coming up it stays opaque: a layer that is see-through at all has to be drawn to a buffer of
                // its own and composited, and doing that to a full screen, every frame, is dear.
                // Still getting ready: in place and rendered — its glass, its covers, all of it — but at 1%, under
                // the mini player, so the first frame anyone sees is a finished one. Moved off screen instead, it
                // was composed but never rendered, and its first real render was the frame it appeared in:
                // 48 ms, and empty.
                alpha = if (!sheetCard.ready) WARMING_ALPHA else sheetCard.cardAlpha
            }
            // No face of its own: through its whole travel the card shows the player's own background — the
            // cover's wash, drawn behind the page and cut to the card's shape (see MainLayout) — rather than an
            // opaque grey that only gave way to it at the very top.
            .onSizeChanged { cardHeightPx = it.height }
            // Deaf until it has landed. Rapid taps on the mini player do not stop at the mini player: the card rises
            // under the same finger, and the taps that followed the first one landed on whatever was passing under
            // it — the cover (the album page), the lyric bubble (the lyrics), the artist, next, a track in the queue.
            // Each one either sent the player away mid-rise, cutting the card off where it was, or changed the track
            // mid-rise, a new cover and its glass to render during the animation: the stutter that came and went
            // over repeated spamming, depending on what the finger happened to hit. Taken here, in the Initial pass,
            // before any control inside sees them.
            .pointerInput(landed) {
                if (landed) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .onGloballyPositioned { settle.anchor = it }
            .drawWithContent {
                // Nothing drawn once the card is fully cleared at the end of a close. The player stays composed for
                // another ~0.2 s until the navigation lets it go, and its six glass panels were re-recorded and put
                // through their blur and lens on nearly every frame of that time (traced) for a card at alpha 0.
                if (!cardCleared) drawContent()
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
        recordForGlass = !sheetCard.frozen,
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
                        val slop = viewConfiguration.touchSlop
                        val lifted = drag(down.id) { change ->
                            val delta = change.positionChange()
                            dx += delta.x
                            dy += delta.y
                            if (!started && hypot(dx, dy) > slop) started = true
                            if (started) {
                                change.consume()
                                if (!sheetDrag && dy < 0f && abs(dy) > abs(dx)) sheetDrag = true
                                if (sheetDrag) queueSheet.dragBy(delta.y)
                                // A swipe down closes the player as soon as it has gone far enough, not when the finger
                                // lifts. Closing starts by rebuilding the page under the card (a 35–40 ms frame): done
                                // while the finger is still travelling, that frame lands on a screen that is standing
                                // still, and the card is already free to go down when the finger is up.
                                if (!sheetDrag && dy >= threshold && dy > abs(dx)) close()
                            }
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
            // Evenly distributed rather than stacked with fixed gaps: every block below has a
            // fixed height, so on this panel the leftover would otherwise pool into one dead gap
            // in the middle. Spreading it reproduces the mockup's even rhythm, and it absorbs the
            // lyric capsule appearing and disappearing without anything else jumping.
            // The controls stack up from the bottom with one even gap between groups, and the title takes whatever room
            // is left above them (sitting at its bottom, right over the lyrics): nothing is spread to fill the screen, so
            // the rhythm is the same on any height.
            verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Where the sound goes, top left, and the queue, top right: under the status bar (the scaffold keeps the
            // inset), at the page margins, over the cover's dark fade.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = SCREEN_PADDING - 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                UtilityIcon(
                    icon = CupertinoIcons.Headphones,
                    contentDescription = "Sortie audio : ${uiState.audioOutput.deviceName ?: uiState.audioOutput.type.label()}",
                    active = false,
                    onClick = { showOutputPicker = true },
                    glyphSize = 22.dp,
                )
                UtilityIcon(
                    icon = CupertinoIcons.Queue,
                    contentDescription = "Ouvrir la file d'attente",
                    active = false,
                    onClick = { navController.navigate(AuraDestination.Queue.route) },
                    glyphSize = 22.dp,
                )
            }

            Box(Modifier.weight(1f).padding(horizontal = SCREEN_PADDING).offset(y = TRANSPORT_EXTRA), contentAlignment = Alignment.BottomStart) {
                HeroPanel(
                    currentTrack = uiState.currentTrack,
                    queueIndex = uiState.queueIndex,
                    isFavorite = uiState.isFavorite,
                    onFavoriteToggle = viewModel::toggleFavorite,
                    automixEnabled = uiState.automixEnabled,
                    onAutomixToggle = viewModel::toggleAutomix,
                    onOpenAlbum = { albumId ->
                        val playing = uiState.currentTrack
                        if (sheetCard.cardTop <= 10f) {
                            if (playing != null && YtmLibrary.isStreamed(playing)) {
                                // A streamed song's album is the source's, and only known if it came through one.
                                playing.streamedAlbumRoute()?.let { navController.navigate(it) }
                            } else {
                                navController.navigate(AuraDestination.AlbumPattern.route(albumId.toString()))
                            }
                        }
                    },
                    onOpenArtist = { artistId ->
                        val playing = uiState.currentTrack
                        if (sheetCard.cardTop <= 10f) {
                            if (playing != null && YtmLibrary.isStreamed(playing)) {
                                navController.navigate(playing.streamedArtistRoute())
                            } else {
                                navController.navigate(AuraDestination.ArtistPattern.route(artistId.toString()))
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
                        pauseAnimation = sheetCard.frozen,
                        // The line being sung is the way into the lyrics.
                        onClick = { if (sheetCard.cardTop <= 10f) navController.navigate(AuraDestination.Lyrics.route) },
                    )
                }
                WavyProgressBar(
                    // Providers, read inside the bar: the column around it no longer recomposes on every tick.
                    positionMs = { positionMs },
                    durationMs = { durationMs },
                    isPlaying = uiState.isPlaying,
                    onSeek = viewModel::seekTo,
                    // The full width of the screen, drawn inset by the page's margin: its touch area reaches the edges
                    // and is kept out of Android's back gesture — grabbing the bar from the side went back instead.
                    edgeInset = SCREEN_PADDING,
                )
            }

            // 8 dp less on the sides: the shuffle and repeat glyphs sit ~13 dp inside their 48 dp touch areas, so they then
            // line up with the page margin.
            Box(Modifier.padding(horizontal = SCREEN_PADDING - 8.dp), contentAlignment = Alignment.Center) {
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
    pauseAnimation: Boolean,
    onClick: () -> Unit,
) {
    val lines = (lyricsState as? LyricsUiState.Available)?.lines
    // The line is chosen from the player's own position on every frame, not from the value published twice a
    // second: it changes when it is sung. Only a change of line is ever read in composition.
    val clock = rememberLiveLyricClock(
        livePosition,
        isPlaying,
        active = lines != null && !pauseAnimation,
    )
    var currentLine by remember(lines) { androidx.compose.runtime.mutableStateOf(lines?.let { it.getOrNull(it.indexAt(clock.longValue)) }) }
    LaunchedEffect(lines) {
        if (lines == null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { lines.getOrNull(lines.indexAt(clock.longValue)) }
            .distinctUntilChanged { a, b -> a?.timeMs == b?.timeMs }
            .collect { currentLine = it }
    }

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
                    positionProvider = { clock.longValue },
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
 * The player's ground: the album pages' aurora across the whole screen, with the cover over its top, dissolving into it —
 * reaching about to the progress bar. The aurora is the app's own (see [AuroraRenderer]), drawn once as a still picture
 * for the cover playing, with the same veil the live background has.
 *
 * What the player's glass looks through: the picture playing is recorded into the shared glass backdrop, so the buttons
 * refract exactly what is drawn behind them.
 *
 * The next track's picture is made ahead of time, while this one plays, so a change of track finds it ready: the new
 * picture is then at once the recorded one, and the old one is drawn over it, outside the recording, and dissolves away.
 * Drawn that way the glass runs its work once for the change rather than on every frame of the fade — it looks through
 * the new picture a few hundred milliseconds early, under a tint and a blur, which nobody can see.
 */
@Composable
private fun PlayerCoverGround(track: Track?, nextTrack: Track?, solid: Color, recordForGlass: Boolean) {
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

    // Nothing in the player looks through glass any more, so the ground is no longer recorded for it: that recording was a
    // second pass over the whole picture for a backdrop nobody samples.
    Box(Modifier.fillMaxSize().background(solid)) {
        current?.let { GroundLayer(it) }
        outgoing?.let { old ->
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) { GroundLayer(old) }
        }
        // Keeps the clock and the battery readable over a pale cover.
        Box(
            Modifier
                .fillMaxWidth()
                // Reaches below the status bar over the row of icons too, so they read on a pale cover.
                .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp)
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.5f), 0.6f to Color.Black.copy(alpha = 0.25f), 1f to Color.Transparent)),
        )
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

private fun bakeGround(art: android.graphics.Bitmap?, cover: android.graphics.Bitmap?, w: Int, h: Int): android.graphics.Bitmap {
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val smooth = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    // The aurora, filling the screen, then the veil it is always drawn with, for the white type that sits on it.
    val aurora = stillAurora(art)
    canvas.drawBitmap(aurora, null, android.graphics.Rect(0, 0, w, h), smooth)
    val veil = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x1A000000, 0x33000000, 0x6B000000), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), veil)

    // The cover across the top, cropped to fill, then erased from the bottom up.
    if (cover != null) {
        val coverH = (h * COVER_HEIGHT_FRACTION).toInt()
        val layer = canvas.saveLayer(0f, 0f, w.toFloat(), coverH.toFloat(), null)
        val scale = maxOf(w.toFloat() / cover.width, coverH.toFloat() / cover.height)
        val dw = cover.width * scale
        val dh = cover.height * scale
        val dst = android.graphics.RectF((w - dw) / 2f, (coverH - dh) / 2f, (w + dw) / 2f, (coverH + dh) / 2f)
        canvas.drawBitmap(cover, null, dst, smooth)
        val erase = android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, coverH.toFloat(),
                intArrayOf(-1, -1, 0x9EFFFFFF.toInt(), 0x24FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.30f, 0.55f, 0.80f, 1f), android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), coverH.toFloat(), erase)
        canvas.restoreToCount(layer)
    }
    return out
}

/** The aurora of a cover as one still picture, the way the app's background makes it (see [AuroraRenderer]). */
private fun stillAurora(art: android.graphics.Bitmap?): android.graphics.Bitmap {
    val renderer = AuroraRenderer(art ?: neutralArt(AlbumTheme.Neutral))
    renderer.render(AURORA_STILL_S)
    val pixels = IntArray(AURORA_PIXELS) { i ->
        (0xFF shl 24) or
            ((renderer.outR[i] shr 8).coerceIn(0, 255) shl 16) or
            ((renderer.outG[i] shr 8).coerceIn(0, 255) shl 8) or
            (renderer.outB[i] shr 8).coerceIn(0, 255)
    }
    return android.graphics.Bitmap.createBitmap(pixels, AURORA_W, AURORA_H, android.graphics.Bitmap.Config.ARGB_8888)
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

/** The size the cover is read at for the ground: it fills the screen's width, so a little over the screen's own. */
private const val GROUND_COVER_PX = 1080

/** The size a cover is read at to make its aurora: it is scaled to 64 px and blurred, so no more is needed. */
private const val AURORA_ART_PX = 192

/** Where in its slow drift the still aurora is taken: any moment of it is the same kind of picture. */
private const val AURORA_STILL_S = 12f

/** Cover, title and artist — the mockup's dominant block. */
@Composable
private fun HeroPanel(
    currentTrack: Track?,
    queueIndex: Int,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (Long) -> Unit,
    isFavorite: Boolean,
    onFavoriteToggle: () -> Unit,
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
                // The star is centred on the whole block, so it sits between the title and the artist whether the title
                // takes one line or two; the names take the width the star leaves them.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        // Three destinations from one block: the cover opens the lyrics, the title
                        // opens the album it is on, the artist opens the artist. Each is the thing
                        // the words already name, so nothing needs a chevron to say so.
                        Text(
                            text = shown?.title ?: "Aucun titre",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            lineHeight = 27.sp,
                            color = rememberPlayerAccent(),
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
                            style = MaterialTheme.typography.bodyLarge,
                            color = rememberPlayerAccent().copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
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
                    // Automix, then the favourite, at the right of the block. Nudged out 8 dp: the glyphs sit ~13 dp inside
                    // their 48 dp touch areas, so the last one then lines up with the page margin the progress bar ends on.
                    UtilityIcon(
                        icon = CupertinoIcons.AutoAwesome,
                        contentDescription = "Automix",
                        active = automixEnabled,
                        onClick = onAutomixToggle,
                        glyphSize = 23.dp,
                    )
                    Box(Modifier.offset(x = 8.dp)) {
                        UtilityIcon(
                            icon = CupertinoIcons.Star,
                            filledIcon = CupertinoIcons.StarFilled,
                            contentDescription = "Favori",
                            active = isFavorite,
                                            onClick = onFavoriteToggle,
                        )
                    }
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
    // Shuffle and repeat flank the three main controls, smaller than them (they are modes, not actions), spread over the
    // width with equal gaps. Five touch areas of 48-76 dp take under 300 dp of the ~400 available, so nothing is squeezed.
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
                touchWidth = 56.dp,
                touchHeight = TRANSPORT_GLYPH_CENTER * 2,
            )
        }
        TransportTile(
            weight = 1f,
            icon = CupertinoIcons.SkipPrevious,
            contentDescription = "Précédent",
            skipDirection = -1,
            onClick = onPrevious,
        )
        TransportTile(
            weight = 1.15f,
            icon = if (isPlaying) CupertinoIcons.Pause else CupertinoIcons.Play,
            playing = isPlaying,
            contentDescription = if (isPlaying) "Pause" else "Lecture",
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onPlayPause()
            },
        )
        TransportTile(
            weight = 1f,
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
                touchWidth = 56.dp,
                touchHeight = TRANSPORT_GLYPH_CENTER * 2,
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
    weight: Float,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    glyphColor: Color = rememberPlayerAccent(),
    /** 1 for "next", -1 for "previous": the two triangles then chase on a press, as on iOS. 0 for the play button. */
    skipDirection: Int = 0,
    /** For the play / pause tile: whether it is playing. The glyph then morphs between the two instead of swapping. */
    playing: Boolean? = null,
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
            .weight(weight)
            .fillMaxHeight()
            .clip(ButtonShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        val glyphModifier = Modifier.padding(bottom = TRANSPORT_GLYPH_CENTER - 17.dp).size(34.dp)
        // The halo is drawn behind the glyph, centred on it, about twice its size.
        Box(
            modifier = glyphModifier.drawBehind {
                val a = halo.value
                if (a > 0.002f) drawCircle(Color.White.copy(alpha = a), radius = size.minDimension * 1.3f / 2f * 1.45f)
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
            tint = if (active) activeTint else rememberPlayerAccent().copy(alpha = 0.55f),
            modifier = Modifier.size(glyphSize),
        )
    }
}

private fun AudioOutputType.label(): String = when (this) {
    AudioOutputType.PHONE_SPEAKER -> "Haut-parleur"
    AudioOutputType.WIRED_HEADSET -> "Casque filaire"
    AudioOutputType.BLUETOOTH -> "Bluetooth"
    AudioOutputType.USB_DAC -> "DAC USB-C"
}

/**
 * Where the music comes out, and how to send it somewhere else.
 *
 * A plain dialog rather than a liquid-glass sheet: the glass samples a backdrop layer recorded
 * in the main window, and a dialog is a window of its own — the coordinates would not line up
 * and the panel would refract the wrong part of the screen.
 *
 * "Automatique" is a first-class choice, not the absence of one. Picking a device pins playback
 * to it; the system's own routing — which follows a headset being plugged in — is what Aura does
 * otherwise, and there has to be a way back to it.
 */
@Composable
private fun AudioOutputPicker(
    outputs: List<AudioOutput>,
    selectedDeviceId: Int?,
    activeName: String?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .auraMenuSurface(PanelShape)
                .padding(vertical = 10.dp),
        ) {
            Text(
                text = "Sortie audio",
                style = MaterialTheme.typography.titleSmall,
                color = AuraColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            )

            OutputRow(
                label = "Automatique",
                detail = activeName?.takeIf { selectedDeviceId == null },
                selected = selectedDeviceId == null,
                onClick = { onSelect(null) },
            )
            outputs.forEach { output ->
                OutputRow(
                    label = output.name,
                    detail = if (output.supportsHiRes) "Haute résolution" else null,
                    selected = output.deviceId == selectedDeviceId,
                    onClick = { onSelect(output.deviceId) },
                )
            }
        }
    }
}

@Composable
private fun OutputRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) AuraColors.TextPrimary else AuraColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = AuraColors.TextTertiary)
            }
        }
        if (selected) {
            Icon(
                CupertinoIcons.Check,
                contentDescription = "Sélectionné",
                tint = AuraColors.TextPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
