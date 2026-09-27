package com.ikko.aura.ui.screens.nowplaying

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.SweepLyricLine
import com.ikko.aura.ui.components.WavyProgressBar
import com.ikko.aura.ui.components.rememberLyricClock
import com.ikko.aura.ui.components.LiquidGlassPanel
import com.ikko.aura.ui.components.horizontalEdgeBlur
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraMotion
import com.ikko.aura.ui.components.FillableIcon
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.LyricsUiState
import com.ikko.aura.viewmodel.LyricsViewModel
import com.ikko.aura.viewmodel.PlayerUiState
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.RepeatMode
import kotlin.math.abs

private val PanelShape = RoundedCornerShape(24.dp)
private val ButtonShape = RoundedCornerShape(20.dp)
private val ArtShape = RoundedCornerShape(14.dp)
private val ChipShape = RoundedCornerShape(50)

private val SCREEN_PADDING = 14.dp
private val HERO_ART_SIZE = 96.dp
private val TRANSPORT_HEIGHT = 58.dp
private val UP_NEXT_HEIGHT = 62.dp

/** How far a transport button sinks under the finger. */
private const val PRESSED_SCALE = 0.96f

/**
 * The play button's wash. Fixed rather than pulled from the artwork palette: it is the only
 * saturated thing on the screen and the one control that has to read identically on every cover,
 * and a palette-derived tint would regularly land on the same hue as the backdrop behind it.
 */
private val PlayTint = Color(0xFFF3C7D5)
private val PlayGlyph = Color(0xFF6E1B36)
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
        if (phase != SheetPhase.Settled) sheetCard.markReady()
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
                try {
                    animate(
                        initialValue = sheetCard.edge.coerceAtMost(AuraMotion.SHEET_PILL_TOP_FRACTION),
                        targetValue = AuraMotion.SHEET_PILL_TOP_FRACTION,
                        animationSpec = tween(AuraMotion.SHEET_EXIT_MS, easing = AuraMotion.EaseInOut),
                    ) { value, _ -> sheetCard.edge = value }
                } finally {
                    settle.frozen = false
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
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = {
                            when {
                                abs(dx) > abs(dy) && dx <= -threshold -> viewModel.skipNext()
                                abs(dx) > abs(dy) && dx >= threshold -> viewModel.skipPrevious()
                                dy >= threshold -> close()
                            }
                        },
                    ) { change, drag ->
                        dx += drag.x
                        dy += drag.y
                        change.consume()
                    }
                }
                // The side margin is on each block rather than here, so the seek bar alone can span the screen's
                // full width to catch a thumb at the edge (it draws itself inset by the same margin).
                .padding(vertical = 10.dp),
            // Evenly distributed rather than stacked with fixed gaps: every block below has a
            // fixed height, so on this panel the leftover would otherwise pool into one dead gap
            // in the middle. Spreading it reproduces the mockup's even rhythm, and it absorbs the
            // lyric capsule appearing and disappearing without anything else jumping.
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.padding(horizontal = SCREEN_PADDING), contentAlignment = Alignment.Center) {
                LyricCapsule(
                    lyricsState = lyricsState,
                    positionMs = { positionMs },
                    isPlaying = uiState.isPlaying,
                    // The line being sung is the way into all of them.
                    onClick = { if (sheetCard.cardTop <= 10f) navController.navigate(AuraDestination.Lyrics.route) },
                )
            }

            Box(Modifier.padding(horizontal = SCREEN_PADDING), contentAlignment = Alignment.Center) {
                HeroPanel(
                    currentTrack = uiState.currentTrack,
                    queueIndex = uiState.queueIndex,
                    onOpenAlbum = { albumId ->
                        if (sheetCard.cardTop <= 10f) navController.navigate(AuraDestination.AlbumPattern.route(albumId.toString()))
                    },
                    onOpenArtist = { artistId ->
                        if (sheetCard.cardTop <= 10f) navController.navigate(AuraDestination.ArtistPattern.route(artistId.toString()))
                    },
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

            Box(Modifier.padding(horizontal = SCREEN_PADDING), contentAlignment = Alignment.Center) {
                TransportRow(
                    isPlaying = uiState.isPlaying,
                    onPrevious = viewModel::skipPrevious,
                    onPlayPause = viewModel::togglePlayPause,
                    onNext = viewModel::skipNext,
                )
            }

            Box(Modifier.padding(horizontal = SCREEN_PADDING), contentAlignment = Alignment.Center) {
                UtilityRow(
                    isFavorite = uiState.isFavorite,
                    automixEnabled = uiState.automixEnabled,
                    shuffleEnabled = uiState.shuffleEnabled,
                    repeatMode = uiState.repeatMode,
                    audioOutput = uiState.audioOutput,
                    onFavoriteToggle = viewModel::toggleFavorite,
                    onAutomixToggle = viewModel::toggleAutomix,
                    onShuffleToggle = viewModel::toggleShuffle,
                    onRepeatCycle = viewModel::cycleRepeatMode,
                    onPickOutput = { showOutputPicker = true },
                )
            }

            Box(Modifier.padding(horizontal = SCREEN_PADDING), contentAlignment = Alignment.Center) {
                UpNextPanel(
                    queue = uiState.queue,
                    queueIndex = uiState.queueIndex,
                    onOpenQueue = { navController.navigate(AuraDestination.Queue.route) },
                    onJumpTo = viewModel::jumpToQueueIndex,
                )
            }
        }
    }
    }
    }
}

/**
 * The floating line of synced lyrics at the top, lighting up word by word as it is sung.
 *
 * **One capsule, not one per line.** The glass, its rim and its shadow are a single surface that
 * stays put and changes width; only the text inside it changes. Cross-fading whole capsules — the
 * obvious spelling, and what this did first — put two rims and two shadows on screen through
 * every transition, which reads as a glitch rather than as a line changing. Here the width
 * animates from the old line's to the new one's while the two texts dissolve through each other
 * in place, so the bubble grows into the new line.
 *
 * Absent entirely when the track has no lyrics. **Present, showing three dots, through an
 * instrumental stretch of a track that does** — that is the same thing the full lyrics screen
 * draws for a break, and a capsule that vanished for eight bars and came back would read as the
 * lyrics having failed rather than as the singing having stopped.
 */
@Composable
private fun LyricCapsule(lyricsState: LyricsUiState, positionMs: () -> Long, isPlaying: Boolean, onClick: () -> Unit) {
    // Read here rather than by the player's column: a playback tick recomposes the bubble alone.
    val position = positionMs()
    val lines = (lyricsState as? LyricsUiState.Available)?.lines
    val currentLine = remember(lines, position) { lines?.currentLineAt(position) }
    // No words on screen, no clock: a gap capsule draws three static dots and a track with no
    // lyrics at all draws nothing, and neither has a sweep for the per-frame clock to feed.
    val clock = rememberLyricClock(
        positionMs = position,
        isPlaying = isPlaying,
        active = currentLine != null && !currentLine.isGap,
    )

    // The capsule's row is always there, whether or not there is a capsule in it.
    //
    // Without this the column — laid out with SpaceBetween — redistributed all five of its gaps
    // the moment the capsule appeared or went away, so every panel on the screen moved and each
    // one re-rendered its glass for the length of the animation. And it happened twice per track
    // change rather than once: the lyrics pass through Loading on every new track, which reads
    // here as "no lyrics", so the capsule dropped out and came back even between two tracks that
    // both have them. Reserving the row means the furniture stays put and only the capsule fades.
    //
    // A minimum rather than a fixed height, because a long line still wraps to two and the
    // capsule has to be allowed to grow into it.
    Box(
        modifier = Modifier.heightIn(min = LYRIC_SLOT_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
    AnimatedVisibility(
        visible = currentLine != null,
        // Faded in only, never scaled. The lyrics usually arrive while the card is still rising, which is
        // when the glass takes the picture it keeps until the card lands (see GlassSettle): a capsule still
        // growing from 92% sat a few pixels from where it would rest, so its glass took a slightly wrong
        // picture and corrected itself just after landing — a flash from one look to another. It still
        // shrinks as it leaves: nothing is being pictured then.
        enter = fadeIn(tween(AuraMotion.PEER_MS)),
        exit = fadeOut(tween(AuraMotion.PEER_MS)) + scaleOut(tween(AuraMotion.PEER_MS), targetScale = 0.92f),
    ) {
        LiquidGlassPanel(
            shape = ChipShape,
            contentAlignment = Alignment.Center,
            // Opens the full lyrics. No ripple across the glass: the page it opens is the answer to the tap.
            modifier = Modifier
                .clip(ChipShape)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            AnimatedContent(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                targetState = currentLine,
                transitionSpec = {
                    (fadeIn(tween(LYRIC_CROSSFADE_MS)) togetherWith fadeOut(tween(LYRIC_CROSSFADE_MS)))
                        // clip = false so a line still overflowing the capsule mid-resize is
                        // drawn rather than cut off — the capsule catching up to the text is
                        // what the movement is, and clipping it hides exactly that.
                        .using(SizeTransform(clip = false) { _, _ -> tween(LYRIC_RESIZE_MS, easing = FastOutSlowInEasing) })
                },
                // Keyed on the line's own stamp: the enclosing state ticks twice a second, and
                // without this every tick would restart the cross-fade on the same words.
                contentKey = { it?.timeMs },
                label = "lyric-line",
            ) { shown ->
                when {
                    shown == null -> Spacer(Modifier.width(1.dp))
                    shown.isGap -> Text(
                        text = "• • •",
                        style = MaterialTheme.typography.labelLarge,
                        color = AuraColors.TextSecondary,
                    )
                    else -> SweepLyricLine(
                        line = shown,
                        positionProvider = { clock.longValue },
                        style = MaterialTheme.typography.labelLarge,
                        litColor = AuraColors.TextPrimary,
                        dimColor = AuraColors.TextTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = LYRIC_MAX_WIDTH),
                    )
                }
            }
        }
    }
    }
}

/** How far the up-next row scrolls before its start is fully softened. */
private val UP_NEXT_EDGE_RAMP = 24.dp

/** Long enough to read as a dissolve, short enough that a quick couplet does not lag behind. */
private const val LYRIC_CROSSFADE_MS = 180

/** The capsule growing into the new line. Slower than the dissolve, so the width leads. */
private const val LYRIC_RESIZE_MS = 260

/** What is left of the panel's width once the screen and capsule padding are taken out. */
private val LYRIC_MAX_WIDTH = 372.dp

/** One line of lyric plus the capsule's own padding — the row the column always reserves. */
private val LYRIC_SLOT_HEIGHT = 36.dp

private fun List<LyricLine>.currentLineAt(positionMs: Long): LyricLine? =
    lastOrNull { it.timeMs <= positionMs }

/** Cover, title and artist — the mockup's dominant block. */
@Composable
private fun HeroPanel(
    currentTrack: Track?,
    queueIndex: Int,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (Long) -> Unit,
) {
    // The cover, title and artist slide through the queue as one block across the panel's whole width.
    var panelWidthPx by remember { mutableIntStateOf(0) }
    LiquidGlassPanel(shape = PanelShape, modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { panelWidthPx = it.width }
            .clip(PanelShape)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The cover opens its album, as a cover does everywhere else in the app; the lyrics are opened from
        // the lyric bubble above.
        AnimatedContent(
            targetState = queueIndex to currentTrack,
                transitionSpec = {
                                    AuraMotion.heroSlide(forward = targetState.first >= initialState.first, distancePx = panelWidthPx)
                },
            modifier = Modifier.size(HERO_ART_SIZE),
            label = "hero-art",
        ) { (_, shown) ->
            AlbumArt(
                uri = shown?.artworkUri,
                contentDescription = shown?.album,
                shape = ArtShape,
                targetSizeDp = HERO_ART_SIZE,
                cacheKey = shown?.albumId?.toString(),
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (shown == null) Modifier else Modifier.clickable { onOpenAlbum(shown.albumId) }),
            )
        }

        Spacer(Modifier.width(12.dp))

        // No "En lecture" chip above the title any more (removed at the user's request): the title and artist
        // sit centred beside the cover on their own.
        Column(modifier = Modifier.weight(1f).height(HERO_ART_SIZE), verticalArrangement = Arrangement.Center) {
            AnimatedContent(
                targetState = queueIndex to currentTrack,
                transitionSpec = {
                                    AuraMotion.heroSlide(forward = targetState.first >= initialState.first, distancePx = panelWidthPx)
                },
                contentAlignment = Alignment.CenterStart,
                label = "hero-label",
            ) { (_, shown) ->
                Column {
                    // Three destinations from one block: the cover opens the lyrics, the title
                    // opens the album it is on, the artist opens the artist. Each is the thing
                    // the words already name, so nothing needs a chevron to say so.
                    Text(
                        text = shown?.title ?: "Aucun titre",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        lineHeight = 25.sp,
                        color = AuraColors.TextPrimary,
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraColors.TextSecondary,
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
            }
        }
    }    }
}

@Composable
private fun TransportRow(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().height(TRANSPORT_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TransportTile(
            weight = 1f,
            icon = CupertinoIcons.SkipPrevious,
            contentDescription = "Précédent",
            onClick = onPrevious,
        )
        TransportTile(
            weight = 1.25f,
            icon = if (isPlaying) CupertinoIcons.Pause else CupertinoIcons.Play,
            contentDescription = if (isPlaying) "Pause" else "Lecture",
            tint = PlayTint,
            tintAlpha = 0.82f,
            glyphColor = PlayGlyph,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onPlayPause()
            },
        )
        TransportTile(
            weight = 1f,
            icon = CupertinoIcons.SkipNext,
            contentDescription = "Suivant",
            onClick = onNext,
        )
    }
}

@Composable
private fun RowScope.TransportTile(
    weight: Float,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = NeutralGlassTint,
    tintAlpha: Float = 0.4f,
    glyphColor: Color = AuraColors.TextPrimary,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // The pill's press depth, on the transport. Sinks under the finger and springs back with a
    // little overshoot — the overshoot is what makes it read as a physical press rather than a
    // colour change. Deliberately soft: at a stiffer spring the sink-and-return is over almost
    // before the finger registers it, which reads as a flicker.
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessVeryLow,
            visibilityThreshold = 0.0004f,
        ),
        label = "transport-press-depth",
    )

    LiquidGlassPanel(
        shape = ButtonShape,
        tint = tint,
        tintAlpha = tintAlpha,
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(ButtonShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = glyphColor,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** Audio source on the left, favourite / shuffle / repeat on the right. */
@Composable
private fun UtilityRow(
    isFavorite: Boolean,
    automixEnabled: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    audioOutput: AudioOutputState,
    onFavoriteToggle: () -> Unit,
    onAutomixToggle: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatCycle: () -> Unit,
    onPickOutput: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(ChipShape)
                .clickable(onClick = onPickOutput)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = CupertinoIcons.Headphones,
                contentDescription = "Changer la sortie audio",
                tint = AuraColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = audioOutput.deviceName ?: audioOutput.type.label(),
                style = MaterialTheme.typography.labelMedium,
                color = AuraColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp),
            )
            Icon(
                imageVector = CupertinoIcons.ChevronDown,
                contentDescription = null,
                tint = AuraColors.TextTertiary,
                modifier = Modifier.size(16.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            UtilityIcon(
                icon = CupertinoIcons.Heart,
                filledIcon = CupertinoIcons.HeartFilled,
                contentDescription = "Favori",
                active = isFavorite,
                activeTint = AuraColors.AccentPink,
                onClick = onFavoriteToggle,
            )
            UtilityIcon(
                icon = CupertinoIcons.AutoAwesome,
                contentDescription = "Automix",
                active = automixEnabled,
                onClick = onAutomixToggle,
            )
            UtilityIcon(
                icon = CupertinoIcons.Shuffle,
                contentDescription = "Lecture aléatoire",
                active = shuffleEnabled,
                onClick = onShuffleToggle,
            )
            UtilityIcon(
                icon = if (repeatMode == RepeatMode.ONE) CupertinoIcons.RepeatOne else CupertinoIcons.Repeat,
                contentDescription = "Répéter",
                active = repeatMode != RepeatMode.OFF,
                onClick = onRepeatCycle,
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
    activeTint: Color = AuraColors.TextPrimary,
    filledIcon: ImageVector = icon,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(ChipShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FillableIcon(
            outline = icon,
            filled = filledIcon,
            active = active,
            contentDescription = contentDescription,
            tint = if (active) activeTint else AuraColors.TextTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The queue preview along the bottom edge: a label, then the covers that come after this one. */
@Composable
private fun UpNextPanel(
    queue: List<Track>,
    queueIndex: Int,
    onOpenQueue: () -> Unit,
    onJumpTo: (Int) -> Unit,
) {
    val upcoming = remember(queue, queueIndex) {
        if (queueIndex < 0) emptyList() else queue.drop(queueIndex + 1)
    }

    LiquidGlassPanel(
        shape = PanelShape,
        modifier = Modifier.fillMaxWidth().height(UP_NEXT_HEIGHT),
    ) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            // The glass clips its own backdrop to the shape but not the content drawn over it,
            // so without this the row's last cover runs straight through the rounded corner.
            .clip(PanelShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "À suivre",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = AuraColors.TextPrimary,
            modifier = Modifier
                .clip(ChipShape)
                .clickable(onClick = onOpenQueue)
                .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        )

        if (upcoming.isEmpty()) {
            Text(
                text = "Fin de la file",
                style = MaterialTheme.typography.bodySmall,
                color = AuraColors.TextTertiary,
            )
        } else {
            val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
            val rampPx = with(androidx.compose.ui.platform.LocalDensity.current) { UP_NEXT_EDGE_RAMP.toPx() }
            LazyRow(
                state = rowState,
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    // What passes either end blurs a little and fades rather than being cut off by a straight
                    // line: the start once the row has moved, the end while there is more to come.
                    .horizontalEdgeBlur(
                        start = {
                            if (rowState.firstVisibleItemIndex > 0) 1f
                            else (rowState.firstVisibleItemScrollOffset / rampPx).coerceIn(0f, 1f)
                        },
                        end = { if (rowState.canScrollForward) 1f else 0f },
                    ),
                contentPadding = PaddingValues(end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(upcoming, key = { _, track -> track.id }) { index, track ->
                    UpNextItem(
                        track = track,
                        onClick = { onJumpTo(queueIndex + 1 + index) },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun UpNextItem(track: Track, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            uri = track.artworkUri,
            contentDescription = track.album,
            shape = RoundedCornerShape(8.dp),
            targetSizeDp = 34.dp,
            cacheKey = track.albumId.toString(),
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.widthIn(max = 104.dp)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.labelMedium,
                color = AuraColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.labelSmall,
                color = AuraColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                .clip(PanelShape)
                .background(AuraColors.SurfaceVariantDark)
                .border(0.5.dp, AuraColors.GlassBorder, PanelShape)
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
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(ChipShape)
                    .background(AuraColors.TextPrimary),
            )
        }
    }
}
