package com.ikko.aura.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikko.aura.data.model.Track
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraMotion
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.CupertinoIcons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.ikko.aura.ui.theme.LocalAuraBatterySaver
import com.ikko.aura.ui.theme.LibraryTypography
import com.ikko.aura.ui.theme.LocalCoverColors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.launch

// Corners about a quarter of the pill's height, as in the reference: a rounded rectangle, not a stadium.
private const val RIM_STRENGTH = 0.55f

private val PillShape = RoundedCornerShape(16.dp)

// Proportions taken from iOS's mini player, scaled to this screen's width (the two are the same
// width once the screen margins are the same fraction of it, which is what makes the ratios carry
// over): a capsule 46dp tall, artwork 30dp, text set at 12sp.
private val PILL_HEIGHT = 64.dp
private val ART_SIZE = 50.dp
private val ART_SHAPE = RoundedCornerShape(12.dp)
// 7 dp rather than the spec's 5: at 5 the cover's corners ran into the curve of the pill's end and were
// clipped by it, most of all at the bottom-left, where the pill's rim highlight also lies over them.
private val START_INSET = 7.dp
private val ART_TEXT_GAP = 10.dp
private val END_INSET = 17.7.dp

// The transport glyphs are drawn in a 22dp box, centred, so play and pause sit on the same axis.
private val GLYPH_SIZE = 30.dp
private val GLYPH_GAP = 22.dp

// The two glyphs are drawn apart, but the taps are not: each keeps a zone as wide as the glyph
// plus its share of the gap on either side, and the boundary between them is the midpoint.
private val NEXT_ZONE = END_INSET + GLYPH_SIZE + GLYPH_GAP / 2
private val PLAY_ZONE = NEXT_ZONE + GLYPH_SIZE + GLYPH_GAP

/**
 * The pill is made of the player's own liquid glass (see [LiquidGlassPanel]): what scrolls beneath it
 * is refracted at its edge, blurred and lifted in colour, and its rim is the same lit edge the
 * player's tiles have. Its darkening matches theirs, so it reads as one of them.
 *
 * What it samples is the page's own content, not the background — the background lives on a surface
 * of its own behind the window, out of reach of any effect — so over an empty stretch of page it is
 * clear glass with its rim, and over rows and covers it bends them.
 */
private val PillGlassTint = Color(0xFF080C1C)
private const val PILL_GLASS_ALPHA = 0.4f

/**
 * Deeper than the player's own 8dp: the player's panels sit over a smooth backdrop, the pill over
 * rows of text and covers, and white type on top of those has to stay readable when a pale cover
 * scrolls beneath it.
 */
private const val PILL_BLUR_DP = 16f

private const val HALO_MS = 720
private const val PRESSED_SCALE = 1.045f
private const val GLYPH_SWAP_MS = 120

/**
 * The mini player. Two buttons sit at the right — play/pause, then next — and **everywhere else opens the player**.
 * Swipe left/right moves through the queue, swipe up opens the player too.
 *
 * Zones are derived from the laid-out width inside the gesture handler rather than from clickable
 * children, so a single pointer handler owns taps and drags and the two can't fight over a gesture
 * that starts as a press and becomes a swipe.
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
    /**
     * The finger has been down on the part of the pill that opens the player for [PREWARM_AFTER_MS] and is still there:
     * the player can begin to be built now, while the tap finishes, instead of after it. [onPrewarmCancel] if the
     * gesture turned out to be something else (a swipe to another track, a press let go off the pill).
     */
    onPrewarm: () -> Unit = {},
    onPrewarmCancel: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // Whether the player was started on the finger-down, and has not yet been confirmed by a tap or an upward swipe.
    var prewarmed by remember { mutableStateOf(false) }
    // How far the finger has travelled since it went down, in pixels. Read at the moment the player would be started:
    // a finger that is already moving is on its way to a swipe, not to a tap. Not snapshot state: nothing draws from it.
    val travel = remember { floatArrayOf(0f) }

    var haloCenter by remember { mutableStateOf(Offset.Unspecified) }
    val halo = remember { Animatable(1f) }
    val scale = remember { Animatable(1f) }
    var touchPivot by remember { mutableStateOf(TransformOrigin.Center) }
    var pressAnimJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val motion = LocalPageMotion.current
    val cover = LocalCoverColors.current
    // The recorded press of the transport glyphs, on the pill's two: a soft round halo under the finger and, on "next", the
    // two triangles running one slot along; the play / pause glyph sinks a little and morphs.
    val playHalo = remember { Animatable(0f) }
    val nextHalo = remember { Animatable(0f) }
    val chase = remember { Animatable(1f) }
    val playMorph by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(if (isPlaying) 220 else 340, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "pill-play-morph",
    )
    val accent = rememberPlayerAccent(onDarkGlass = true)
    LiquidGlassPanel(
        shape = PillShape,
        tint = PillGlassTint,
        tintAlpha = PILL_GLASS_ALPHA,
        blurRadiusDp = PILL_BLUR_DP,
        dropShadow = false,
        edge = false,
        modifier = modifier
            .fillMaxWidth()
            .height(PILL_HEIGHT)
            .graphicsLayer {
                val w = motion?.takeIf { !it.reduceMotion }
                    ?.let { min(abs(it.velocity) / PageMotionSpec.PLAYER_STRETCH_FULL_AT_PAGES_S, 1f) } ?: 0f
                val s = scale.value
                scaleX = s * (1f + PageMotionSpec.PLAYER_STRETCH_X * w)
                scaleY = s * (1f - PageMotionSpec.PLAYER_STRETCH_Y * w)
                transformOrigin = touchPivot
            }
            .clip(PillShape)
            .drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) }
            .sheen(
                // The light slides across as the pages travel between two neighbours: 0 at one, 1 at the other.
                position = { val p = motion?.p ?: 0f; 1.3f - 1.7f * (p - kotlin.math.floor(p)).coerceIn(-0.2f, 1.2f) },
                strength = { val m = motion; if (m == null || m.reduceMotion || !m.inTransit) 0f else 1f },
            )
            // A radial gradient rather than a flat disc, so the halo has no visible rim: it is
            // brightest under the finger and dissolves to nothing at its edge. Drawn after the
            // content and clipped by the pill's own shape, it spreads out from where you touched
            // and is cut cleanly at the rounded edge.
            .drawWithContent {
                drawContent()
                val centre = haloCenter
                val progress = halo.value
                if (centre == Offset.Unspecified || progress >= 1f) return@drawWithContent
                val radius = hypot(size.width, size.height) * progress
                if (radius <= 0f) return@drawWithContent
                // Squared falloff: holds its presence through the spread, then lets go gently
                // instead of cutting out.
                val strength = 0.20f * (1f - progress) * (1f - progress)
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to AuraColors.TextPrimary.copy(alpha = strength),
                            0.55f to AuraColors.TextPrimary.copy(alpha = strength * 0.5f),
                            1.0f to Color.Transparent,
                        ),
                        center = centre,
                        radius = radius,
                    ),
                    radius = radius,
                    center = centre,
                )
            }
            .pointerInput(Unit) {
                // Watches, and takes nothing: the finger's path, for the prewarm decision above.
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
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val nextZoneStart = size.width - NEXT_ZONE.toPx()
                val playZoneStart = size.width - PLAY_ZONE.toPx()
                detectTapGestures(
                    onPress = { offset ->
                        val pressStart = System.currentTimeMillis()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        if (w > 0f && h > 0f) {
                            touchPivot = TransformOrigin(
                                (offset.x / w).coerceIn(0f, 1f),
                                (offset.y / h).coerceIn(0f, 1f),
                            )
                        }
                        haloCenter = offset
                        val zone = when {
                            offset.x >= nextZoneStart -> 2
                            offset.x >= playZoneStart -> 1
                            else -> 0
                        }
                        if (zone == 1) scope.launch { playHalo.snapTo(HALO_ALPHA) }
                        if (zone == 2) {
                            scope.launch { nextHalo.snapTo(HALO_ALPHA) }
                            scope.launch {
                                chase.snapTo(0f)
                                chase.animateTo(1f, tween(CHASE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                            }
                        }
                        // Only on the part that opens the player, and only once the finger has stayed: a press that is
                        // about to become a swipe has usually moved by then.
                        val prewarm = if (offset.x < playZoneStart) {
                            scope.launch {
                                kotlinx.coroutines.delay(PREWARM_AFTER_MS)
                                if (travel[0] <= PREWARM_MAX_TRAVEL.toPx()) {
                                    prewarmed = true
                                    onPrewarm()
                                }
                            }
                        } else null
                        val spread = scope.launch {
                            halo.snapTo(0f)
                            halo.animateTo(1f, tween(HALO_MS, easing = LinearOutSlowInEasing))
                        }
                        pressAnimJob?.cancel()
                        pressAnimJob = scope.launch {
                            scale.animateTo(
                                targetValue = PRESSED_SCALE,
                                animationSpec = spring(
                                    dampingRatio = 0.82f, // Apple interactive soft cushion
                                    stiffness = 160f,
                                ),
                            )
                        }
                        val released = tryAwaitRelease()
                        prewarm?.cancel()
                        scope.launch { playHalo.animateTo(0f, tween(HALO_FADE_MS)) }
                        scope.launch { nextHalo.animateTo(0f, tween(HALO_FADE_MS)) }
                        if (released) {
                            val elapsed = System.currentTimeMillis() - pressStart
                            pressAnimJob?.cancel()
                            pressAnimJob = scope.launch {
                                val minSinkMs = 100L
                                if (elapsed < minSinkMs) {
                                    kotlinx.coroutines.delay(minSinkMs - elapsed)
                                }
                                // A hair of rebound at the end. The spring's default stopping threshold (0.01 of scale) is bigger than
                                // the whole press (0.045) and its rebound, so it was declared done at ~1.01 and snapped the
                                // rest — the hard stop. A threshold of 0.0004 lets it run its last tenth of a percent.
                                scale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = 0.52f,
                                        stiffness = 150f,
                                        visibilityThreshold = 0.0004f,
                                    ),
                                )
                            }
                        } else {
                            spread.cancel()
                            halo.snapTo(1f)
                            pressAnimJob?.cancel()
                            pressAnimJob = scope.launch {
                                scale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = 0.82f,
                                        stiffness = 160f,
                                        visibilityThreshold = 0.0004f,
                                    ),
                                )
                            }
                        }
                    },
                    onTap = { offset ->
                        prewarmed = false // a tap on the opening part confirms what the press began
                        when {
                            offset.x >= nextZoneStart -> onNext()
                            offset.x >= playZoneStart -> onTogglePlay()
                            else -> onOpen()
                        }
                    },
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // One drag detector for both axes rather than two competing ones: the dominant
                // axis at release decides, so a slightly diagonal swipe still does what was meant.
                val threshold = 40.dp.toPx()
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        val wasPrewarmed = prewarmed
                        prewarmed = false
                        when {
                            abs(dx) > abs(dy) && dx <= -threshold -> { if (wasPrewarmed) onPrewarmCancel(); onNext() }
                            abs(dx) > abs(dy) && dx >= threshold -> { if (wasPrewarmed) onPrewarmCancel(); onPrevious() }
                            dy <= -threshold -> onOpen()
                            else -> if (wasPrewarmed) onPrewarmCancel()
                        }
                    },
                    onDragCancel = { if (prewarmed) { prewarmed = false; onPrewarmCancel() } },
                ) { change, drag ->
                    dx += drag.x
                    dy += drag.y
                    change.consume()
                }
            }
            ,
    ) {
        // The glass's edge, lit like Apple's: bright at two corners, a hairline along the sides.
        Spacer(Modifier.matchParentSize().clip(PillShape).pillEdge(PillShape))
    Row(
        modifier = Modifier.fillMaxSize().padding(start = START_INSET, end = END_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = queueIndex to track,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)))
                    .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> snap() })
            },
            label = "pill-art",
        ) { (_, shown) ->
            AlbumArt(
                uri = shown.artworkUri,
                contentDescription = shown.album,
                shape = ART_SHAPE,
                modifier = Modifier.size(ART_SIZE),
                targetSizeDp = ART_SIZE,
                cacheKey = shown.albumId.toString(),
            )
        }

        AnimatedContent(
            targetState = queueIndex to track,
            transitionSpec = {
                (fadeIn(tween(150)) togetherWith fadeOut(tween(150)))
                    .using(SizeTransform(clip = false) { _, _ -> snap() })
            },
            modifier = Modifier.weight(1f).padding(start = ART_TEXT_GAP, end = ART_TEXT_GAP),
            label = "pill-track",
        ) { (_, shown) ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = shown.title,
                    style = LibraryTypography.PlayerTitle,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                Text(
                    text = shown.artist,
                    style = LibraryTypography.PlayerArtist,
                    color = accent.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
            }
        }

        // Not IconButtons: their own clickable would compete with the pill-wide pointer handler
        // for gestures that start here and turn into a swipe. Their tap zones are handled above;
        // these only draw the glyphs.
        Box(
            modifier = Modifier.size(GLYPH_SIZE).drawBehind {
                val a = playHalo.value
                if (a > 0.002f) drawCircle(Color.White.copy(alpha = a), radius = size.minDimension * 0.95f)
            },
            contentAlignment = Alignment.Center,
        ) {
            val sink = 1f - 0.12f * (playHalo.value / HALO_ALPHA).coerceIn(0f, 1f)
            Icon(
                imageVector = CupertinoIcons.Play,
                contentDescription = if (isPlaying) null else "Lecture",
                tint = accent,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val sc = (1f - 0.65f * playMorph) * sink
                    scaleX = sc; scaleY = sc; alpha = 1f - playMorph
                },
            )
            Icon(
                imageVector = CupertinoIcons.Pause,
                contentDescription = if (isPlaying) "Pause" else null,
                tint = accent,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val sc = (0.35f + 0.65f * playMorph) * sink
                    scaleX = sc; scaleY = sc; alpha = playMorph
                },
            )
        }
        Spacer(Modifier.width(GLYPH_GAP))
        Box(
            modifier = Modifier.size(GLYPH_SIZE).drawBehind {
                val a = nextHalo.value
                if (a > 0.002f) drawCircle(Color.White.copy(alpha = a), radius = size.minDimension * 0.95f)
            },
        ) {
            SkipGlyph(
                forward = true,
                color = accent,
                progress = { chase.value },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "Titre suivant" },
            )
        }
    }
    }
}

/** How long a finger stays on the opening part of the pill before the player starts to be built. */
private const val PREWARM_AFTER_MS = 40L

/** A finger that has moved more than this by then is going somewhere else, and the player is not started. */
private val PREWARM_MAX_TRAVEL = 3.dp
