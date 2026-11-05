package com.ikko.aura.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.ikko.aura.data.model.Track
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LibraryTypography
import kotlin.math.abs
import kotlinx.coroutines.launch

internal val DOCK_HEIGHT = 78.dp
private val HORIZONTAL_INSET = 16.dp
private val POLE_SIZE = 44.dp
private val ART_SIZE = 46.dp
private val ART_SHAPE = RoundedCornerShape(11.dp)
private val ART_TEXT_GAP = 12.dp

private val GLYPH_SIZE = 30.dp
private val GLYPH_GAP = 14.dp
private val RIGHT_SLOT_GAP = 12.dp
private val BAR_TOUCH_HEIGHT = 26.dp

/**
 * Option A docked mini-player (no liquid glass, no blur):
 * - Left: rounded-square album cover (45dp) + Title & Artist right next to it
 * - Right: Play/Pause icon + Next icon (+ optional PageSwitchPill)
 * - Bottom edge: full-width linear progress bar at the very bottom of the screen
 */
@Composable
fun FloatingPlayerPill(
    track: Track,
    queueIndex: Int,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentColor: Color = Color(0xFF2AD868),
    progressProvider: () -> Float = { 0f },
    /** The bar was scrubbed to this fraction of the track. */
    onSeek: (Float) -> Unit = {},
    onPrewarm: () -> Unit = {},
    onPrewarmCancel: () -> Unit = {},
    /** A pull up on the bar: started, then how far up it has gone as a fraction of the screen's height, then let go ([commit]: carry on up). */
    onOpenDragStart: () -> Unit = {},
    onOpenDrag: (Float) -> Unit = {},
    onOpenDragEnd: (Boolean, Float) -> Unit = { _, _ -> },
    hasRightButton: Boolean = true,
    endSlot: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    var prewarmed by remember { mutableStateOf(false) }
    val travel = remember { floatArrayOf(0f) }

    val playHalo = remember { Animatable(0f) }
    val nextHalo = remember { Animatable(0f) }
    val nextChase = remember { Animatable(1f) }
    val artScale = remember { Animatable(1f) }

    val playMorph by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(if (isPlaying) 220 else 320, easing = FastOutSlowInEasing),
        label = "dock-play-morph",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DOCK_HEIGHT),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                // The bar owns the top 16 dp (it sits at 8, as on the board); the content is centred in what is left.
                .padding(start = HORIZONTAL_INSET, end = HORIZONTAL_INSET, top = 16.dp, bottom = 4.dp)
                .then(
                    if (enabled) {
                        Modifier
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val change = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull() ?: continue
                                        when {
                                            change.pressed && !change.previousPressed -> travel[0] = 0f
                                            change.pressed -> travel[0] += (change.position - change.previousPosition).getDistance()
                                        }
                                    }
                                }
                            }
                            .pointerInput(hasRightButton) {
                                val rightSlotPx = if (hasRightButton) (POLE_SIZE + RIGHT_SLOT_GAP).toPx() else 0f
                                val nextWidthPx = (GLYPH_SIZE + GLYPH_GAP / 2).toPx()
                                val playWidthPx = (GLYPH_SIZE + GLYPH_GAP / 2 + 8.dp).toPx()
                                detectTapGestures(
                                    onPress = { offset ->
                                        val rightSlotStart = size.width - rightSlotPx
                                        val nextZoneStart = rightSlotStart - nextWidthPx
                                        val playZoneStart = nextZoneStart - playWidthPx
                                        // Zone: 1 = Play/Pause, 2 = Next, 0 = Open player, -1 = Right slot
                                        val zone = when {
                                            hasRightButton && offset.x >= rightSlotStart -> -1
                                            offset.x >= nextZoneStart -> 2
                                            offset.x >= playZoneStart -> 1
                                            else -> 0
                                        }
                                        when (zone) {
                                            1 -> scope.launch { playHalo.snapTo(HALO_ALPHA) }
                                            2 -> {
                                                scope.launch { nextHalo.snapTo(HALO_ALPHA) }
                                                scope.launch {
                                                    nextChase.snapTo(0f)
                                                    nextChase.animateTo(1f, tween(CHASE_MS, easing = FastOutSlowInEasing))
                                                }
                                            }
                                            0 -> scope.launch {
                                                artScale.animateTo(0.94f, spring(dampingRatio = 0.8f, stiffness = 220f))
                                            }
                                        }
                                        val prewarm = if (zone == 0) {
                                            scope.launch {
                                                kotlinx.coroutines.delay(PREWARM_AFTER_MS)
                                                if (travel[0] <= PREWARM_MAX_TRAVEL.toPx()) {
                                                    prewarmed = true
                                                    onPrewarm()
                                                }
                                            }
                                        } else null
                                        tryAwaitRelease()
                                        prewarm?.cancel()
                                        scope.launch { playHalo.animateTo(0f, tween(HALO_FADE_MS)) }
                                        scope.launch { nextHalo.animateTo(0f, tween(HALO_FADE_MS)) }
                                        scope.launch { artScale.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = 180f)) }
                                    },
                                    onTap = { offset ->
                                        prewarmed = false
                                        val rightSlotStart = size.width - rightSlotPx
                                        val nextZoneStart = rightSlotStart - nextWidthPx
                                        val playZoneStart = nextZoneStart - playWidthPx
                                        when {
                                            hasRightButton && offset.x >= rightSlotStart -> Unit
                                            offset.x >= nextZoneStart -> onNext()
                                            offset.x >= playZoneStart -> onTogglePlay()
                                            else -> onOpen()
                                        }
                                    },
                                )
                            }
                            .pointerInput(Unit) {
                                val threshold = 40.dp.toPx()
                                val slop = 6.dp.toPx()
                                val commitDistance = 120.dp.toPx()
                                val flickMin = 10.dp.toPx()
                                var dx = 0f
                                var dy = 0f
                                var vertical: Boolean? = null
                                var pulling = false
                                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                                detectDragGestures(
                                    onDragStart = { dx = 0f; dy = 0f; vertical = null; pulling = false; tracker.resetTracking() },
                                    onDragEnd = {
                                        val wasPrewarmed = prewarmed
                                        prewarmed = false
                                        if (pulling) {
                                            val vy = tracker.calculateVelocity().y
                                            pulling = false
                                            scope.launch { playHalo.snapTo(0f); artScale.snapTo(1f) }
                                            // A short flick is enough: what decides is where it was going, not how far.
                                            val up = -dy
                                            onOpenDragEnd(
                                                up >= commitDistance || (vy < -FLICK_PX_S && up >= flickMin),
                                                vy,
                                            )
                                            return@detectDragGestures
                                        }
                                        when {
                                            abs(dx) > abs(dy) && dx <= -threshold -> {
                                                if (wasPrewarmed) onPrewarmCancel()
                                                onNext()
                                            }
                                            abs(dx) > abs(dy) && dx >= threshold -> {
                                                if (wasPrewarmed) onPrewarmCancel()
                                                onPrevious()
                                            }
                                            else -> if (wasPrewarmed) onPrewarmCancel()
                                        }
                                    },
                                    onDragCancel = {
                                        if (pulling) {
                                            pulling = false
                                            onOpenDragEnd(false, 0f)
                                        }
                                        if (prewarmed) {
                                            prewarmed = false
                                            onPrewarmCancel()
                                        }
                                    },
                                ) { change, drag ->
                                    dx += drag.x
                                    dy += drag.y
                                    tracker.addPointerInputChange(change)
                                    if (vertical == null && kotlin.math.hypot(dx, dy) > slop) vertical = abs(dy) > abs(dx)
                                    if (vertical == true && dy < 0f) {
                                        if (!pulling) {
                                            pulling = true
                                            // Released by the pull: no longer a press that is about to be a tap.
                                            onOpenDragStart()
                                        }
                                        onOpenDrag(-dy / view.height.coerceAtLeast(1).toFloat())
                                    }
                                    change.consume()
                                }
                            }
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: Album cover thumbnail
            Box(
                modifier = Modifier
                    .size(ART_SIZE)
                    .graphicsLayer {
                        val s = artScale.value
                        scaleX = s
                        scaleY = s
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                AnimatedContent(
                    targetState = queueIndex to track,
                    transitionSpec = {
                        (fadeIn(tween(150)) togetherWith fadeOut(tween(150)))
                            .using(SizeTransform(clip = false) { _, _ -> snap() })
                    },
                    label = "dock-art",
                ) { (_, shown) ->
                    AlbumArt(
                        uri = shown.artworkUri,
                        contentDescription = shown.album,
                        shape = ART_SHAPE,
                        modifier = Modifier
                            .size(ART_SIZE)
                            .border(0.75.dp, Color.White.copy(alpha = 0.12f), ART_SHAPE),
                        targetSizeDp = ART_SIZE,
                        cacheKey = shown.albumId.toString(),
                    )
                }
            }

            // Middle: Title + Artist right next to the album cover
            AnimatedContent(
                targetState = queueIndex to track,
                transitionSpec = {
                    (fadeIn(tween(150)) togetherWith fadeOut(tween(150)))
                        .using(SizeTransform(clip = false) { _, _ -> snap() })
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = ART_TEXT_GAP, end = ART_TEXT_GAP),
                label = "dock-track",
            ) { (_, shown) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = shown.title,
                        style = LibraryTypography.PlayerTitle,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.basicMarquee(iterations = 1),
                    )
                    Text(
                        text = shown.artist,
                        style = LibraryTypography.PlayerArtist,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.basicMarquee(iterations = 1),
                    )
                }
            }

            // Right: Clean Play/Pause + Next icons (no white circle or circular progress ring)
            Box(
                modifier = Modifier
                    .size(GLYPH_SIZE)
                    .drawBehind {
                        val a = playHalo.value
                        if (a > 0.002f) drawCircle(Color.White.copy(alpha = a), radius = size.minDimension * 0.95f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                // The press's sink, read inside the layers below: the halo fading out redraws the glyphs, it does not recompose them.
                val sink = { 1f - 0.12f * (playHalo.value / HALO_ALPHA).coerceIn(0f, 1f) }
                Icon(
                    imageVector = CupertinoIcons.Play,
                    contentDescription = if (isPlaying) null else "Lecture",
                    tint = Color.White,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val sc = (1f - 0.65f * playMorph) * sink()
                            scaleX = sc
                            scaleY = sc
                            alpha = 1f - playMorph
                        },
                )
                Icon(
                    imageVector = CupertinoIcons.Pause,
                    contentDescription = if (isPlaying) "Pause" else null,
                    tint = Color.White,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val sc = (0.35f + 0.65f * playMorph) * sink()
                            scaleX = sc
                            scaleY = sc
                            alpha = playMorph
                        },
                )
            }

            Spacer(Modifier.width(GLYPH_GAP))

            Box(
                modifier = Modifier
                    .size(GLYPH_SIZE)
                    .drawBehind {
                        val a = nextHalo.value
                        if (a > 0.002f) drawCircle(Color.White.copy(alpha = a), radius = size.minDimension * 0.95f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                SkipGlyph(
                    forward = true,
                    color = Color.White,
                    progress = { nextChase.value },
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "Titre suivant" },
                )
            }

            if (hasRightButton) {
                Spacer(Modifier.width(RIGHT_SLOT_GAP))
                Box(
                    modifier = Modifier.size(POLE_SIZE),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    endSlot()
                }
            }
        }

        // Progress bar along the TOP of the dock (board: 8 px at 20 px from the top, 40 px in from the sides). Held, it
        // thickens like the player's bar, and the finger scrubs it; the seek lands on release.
        var scrub by remember { mutableStateOf<Float?>(null) }
        val thick by animateFloatAsState(
            targetValue = if (scrub != null) 1f else 0f,
            animationSpec = tween(160, easing = FastOutSlowInEasing),
            label = "dock-bar-thick",
        )
        val barInset = 16.dp
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(BAR_TOUCH_HEIGHT)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                            val inset = barInset.toPx()
                            val span = (size.width - 2 * inset).coerceAtLeast(1f)
                            fun frac(x: Float) = ((x - inset) / span).coerceIn(0f, 1f)
                            down.consume()
                            scrub = frac(down.position.x)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                ch.consume()
                                if (!ch.pressed) break
                                scrub = frac(ch.position.x)
                            }
                            scrub?.let(onSeek)
                            scrub = null
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = barInset.toPx()
                val h = (3.2.dp.toPx()) + (4.dp.toPx()) * thick
                val top = 8.dp.toPx() - 2.dp.toPx() * thick
                val width = size.width - 2 * inset
                val progress = (scrub ?: progressProvider()).coerceIn(0f, 1f)
                val r = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.16f),
                    topLeft = Offset(inset, top), size = Size(width, h), cornerRadius = r,
                )
                if (progress > 0.001f) {
                    drawRoundRect(
                        color = accentColor,
                        topLeft = Offset(inset, top), size = Size(width * progress, h), cornerRadius = r,
                    )
                }
            }
        }
    }
}

/** A release faster than this is a flick: it goes the way it was thrown, whatever the distance. */
private const val FLICK_PX_S = 350f
private const val PREWARM_AFTER_MS = 40L
private val PREWARM_MAX_TRAVEL = 3.dp
