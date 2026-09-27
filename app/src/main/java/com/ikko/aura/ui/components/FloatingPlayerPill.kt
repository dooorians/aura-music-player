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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.ikko.aura.ui.theme.LibraryTypography
import com.ikko.aura.ui.theme.LocalCoverColors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.launch

private val PillShape = AuraShapes.MiniPlayer

// Proportions taken from iOS's mini player, scaled to this screen's width (the two are the same
// width once the screen margins are the same fraction of it, which is what makes the ratios carry
// over): a capsule 46dp tall, artwork 30dp, text set at 12sp.
private val PILL_HEIGHT = 47.5.dp
private val ART_SIZE = 37.dp
private val ART_SHAPE = RoundedCornerShape(10.dp)
// 7 dp rather than the spec's 5: at 5 the cover's corners ran into the curve of the pill's end and were
// clipped by it, most of all at the bottom-left, where the pill's rim highlight also lies over them.
private val START_INSET = 7.dp
private val ART_TEXT_GAP = 10.dp
private val END_INSET = 17.7.dp

// The transport glyphs are drawn in a 22dp box, centred, so play and pause sit on the same axis.
private val GLYPH_SIZE = 22.dp
private val GLYPH_GAP = 20.6.dp

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
private const val PILL_GLASS_ALPHA = 0.56f

/**
 * Deeper than the player's own 8dp: the player's panels sit over a smooth backdrop, the pill over
 * rows of text and covers, and white type on top of those has to stay readable when a pale cover
 * scrolls beneath it.
 */
private const val PILL_BLUR_DP = 16f

private const val HALO_MS = 720
private const val PRESSED_SCALE = 0.97f

/**
 * How close to 1 the press spring runs before it is called done. The default, 0.01, is a third of
 * this spring's whole travel: the bounce was cut off with the pill still visibly a hair small, and it
 * snapped the rest of the way.
 */
private const val PRESS_REST_THRESHOLD = 0.0004f
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
) {
    val scope = rememberCoroutineScope()

    var pressed by remember { mutableStateOf(false) }
    var haloCenter by remember { mutableStateOf(Offset.Unspecified) }
    val halo = remember { Animatable(1f) }

    // Sinks slightly under the finger, then springs back — the overshoot on release is what makes
    // it read as a physical press rather than a colour change.
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        // Deliberately soft: at StiffnessMedium the sink-and-return was over almost before the
        // finger registered it, which read as a flicker rather than a press.
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessVeryLow,
            visibilityThreshold = PRESS_REST_THRESHOLD,
        ),
        label = "pill-press-depth",
    )

    val motion = LocalPageMotion.current
    val cover = LocalCoverColors.current
    val dynamicTint = androidx.compose.ui.graphics.Color(androidx.core.graphics.ColorUtils.blendARGB(PillGlassTint.toArgb(), cover.accent.toArgb(), 0.18f))
    LiquidGlassPanel(
        shape = PillShape,
        tint = dynamicTint,
        tintAlpha = PILL_GLASS_ALPHA,
        blurRadiusDp = PILL_BLUR_DP,
        dropShadow = false,
        modifier = modifier
            .fillMaxWidth()
            .height(PILL_HEIGHT)
            .graphicsLayer {
                val w = motion?.takeIf { !it.reduceMotion }
                    ?.let { min(abs(it.velocity) / PageMotionSpec.PLAYER_STRETCH_FULL_AT_PAGES_S, 1f) } ?: 0f
                scaleX = scale * (1f + PageMotionSpec.PLAYER_STRETCH_X * w)
                scaleY = scale * (1f - PageMotionSpec.PLAYER_STRETCH_Y * w)
            }
            .clip(PillShape)
            // Clears what the page drew here before the glass draws. The page has no background of its
            // own (that is a surface behind the window), so the glass's blurred copy of it, being partly
            // transparent, was laid over the page's own crisp text and the two read as double: a row
            // of type passing under the pill showed sharp through it. Cleared first, what shows is the
            // glass alone — the blurred page, over the background.
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
                val nextZoneStart = size.width - NEXT_ZONE.toPx()
                val playZoneStart = size.width - PLAY_ZONE.toPx()
                detectTapGestures(
                    onPress = { offset ->
                        pressed = true
                        haloCenter = offset
                        val spread = scope.launch {
                            halo.snapTo(0f)
                            halo.animateTo(1f, tween(HALO_MS, easing = LinearOutSlowInEasing))
                        }
                        // onPress fires on every touch down, including the start of a swipe, so
                        // without this every drag also painted a full-width gradient over the pill
                        // for the better part of a second — GPU work for a tap that never came.
                        // tryAwaitRelease returns false when the gesture was cancelled, which is
                        // exactly the case where the drag detector took it over.
                        val released = tryAwaitRelease()
                        pressed = false
                        if (!released) {
                            spread.cancel()
                            halo.snapTo(1f)
                        }
                    },
                    onTap = { offset ->
                        when {
                            offset.x >= nextZoneStart -> onNext()
                            offset.x >= playZoneStart -> onTogglePlay()
                            else -> onOpen()
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // One drag detector for both axes rather than two competing ones: the dominant
                // axis at release decides, so a slightly diagonal swipe still does what was meant.
                val threshold = 40.dp.toPx()
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        when {
                            abs(dx) > abs(dy) && dx <= -threshold -> onNext()
                            abs(dx) > abs(dy) && dx >= threshold -> onPrevious()
                            dy <= -threshold -> onOpen()
                        }
                    },
                ) { change, drag ->
                    dx += drag.x
                    dy += drag.y
                    change.consume()
                }
            }
            ,
    ) {
    Row(
        modifier = Modifier.fillMaxSize().padding(start = START_INSET, end = END_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            uri = track.artworkUri,
            contentDescription = track.album,
            shape = ART_SHAPE,
            modifier = Modifier.size(ART_SIZE),
            targetSizeDp = ART_SIZE,
            cacheKey = track.albumId.toString(),
        )

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
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
                Text(
                    text = shown.artist,
                    style = LibraryTypography.PlayerArtist,
                    color = cover.tint.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
            }
        }

        // Not IconButtons: their own clickable would compete with the pill-wide pointer handler
        // for gestures that start here and turn into a swipe. Their tap zones are handled above;
        // these only draw the glyphs.
        Crossfade(
            targetState = isPlaying,
            animationSpec = tween(GLYPH_SWAP_MS),
            modifier = Modifier.size(GLYPH_SIZE),
            label = "pill-play-pause",
        ) { playing ->
            Icon(
                imageVector = if (playing) CupertinoIcons.Pause else CupertinoIcons.Play,
                contentDescription = if (playing) "Pause" else "Lecture",
                tint = Color.White,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(GLYPH_GAP))
        Icon(
            imageVector = CupertinoIcons.SkipNext,
            contentDescription = "Titre suivant",
            tint = Color.White,
            modifier = Modifier.size(GLYPH_SIZE),
        )
    }
    }
}
