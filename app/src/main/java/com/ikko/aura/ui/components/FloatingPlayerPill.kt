package com.ikko.aura.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ikko.aura.data.model.Track
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraMotion
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.PhosphorIcons
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.launch

private val PillShape = AuraShapes.PlayerPill
private val PILL_HEIGHT = 58.dp
private val ART_SIZE = 42.dp
private val SIDE_INSET = 10.4.dp
private val ART_SHAPE = RoundedCornerShape(7.6.dp)
private val ART_TEXT_GAP = 12.dp
private val BUTTON_SIZE = 44.dp
private val GLYPH_SIZE = 24.dp

// Skip-forward's ink stops 4.5dp short of its 24dp box; the end padding cancels that so the
// visible glyph sits SIDE_INSET from the pill's edge, mirroring the artwork on the left.
private val NEXT_GLYPH_END_PAD = SIDE_INSET - 4.5.dp

private const val HALO_MS = 720
private const val PRESSED_SCALE = 0.97f

/**
 * Real-time frosted glass via [Haze](https://github.com/chrisbanes/haze) — a real GPU
 * `RenderEffect` sampling the actual app content behind the pill, redrawn on the compositor's own
 * vsync. The fixed 0.33x input scale matches BitChord's own tuning note (`HazeInputScale.Auto`
 * doesn't step down until the blur radius is already large, leaving a small pill sampling at full
 * resolution every frame). The blur stays live while the list scrolls: `dumpsys gfxinfo` put the
 * GPU at 4-7ms a frame with the jank entirely on the UI thread, so there is nothing to trade away.
 *
 * Two buttons sit at the right — play/pause, then next — and **everywhere else opens the player**.
 * Swipe left/right moves through the queue, swipe up opens the player too.
 *
 * Zones are derived from the laid-out width inside the gesture handler rather than from clickable
 * children, so a single pointer handler owns taps and drags and the two can't fight over a gesture
 * that starts as a press and becomes a swipe.
 */
@OptIn(ExperimentalHazeMaterialsApi::class, ExperimentalHazeApi::class)
@Composable
fun FloatingPlayerPill(
    track: Track,
    queueIndex: Int,
    isPlaying: Boolean,
    hazeState: HazeState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaceTint = MaterialTheme.colorScheme.surface
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
        ),
        label = "pill-press-depth",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PILL_HEIGHT)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(PillShape)
            .hazeEffect(state = hazeState, style = HazeMaterials.thin(surfaceTint)) {
                inputScale = HazeInputScale.Fixed(0.33f)
            }
            .border(1.dp, AuraColors.GlassBorder, PillShape)
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
                val nextZoneStart = size.width - BUTTON_SIZE.toPx()
                val playZoneStart = size.width - (BUTTON_SIZE * 2).toPx()
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
            .padding(start = SIDE_INSET),
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
                AuraMotion.queueSlide(forward = targetState.first >= initialState.first)
            },
            modifier = Modifier.weight(1f).padding(start = ART_TEXT_GAP, end = 8.dp),
            label = "pill-track",
        ) { (_, shown) ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = shown.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = AuraColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
                Text(
                    text = shown.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
            }
        }

        // Not IconButtons: their own clickable would compete with the pill-wide pointer handler
        // for gestures that start here and turn into a swipe. Their tap zones are handled above;
        // these only draw the glyphs, at the size the targets would have had.
        Icon(
            imageVector = if (isPlaying) PhosphorIcons.Pause else PhosphorIcons.Play,
            contentDescription = if (isPlaying) "Pause" else "Lecture",
            tint = AuraColors.TextPrimary,
            modifier = Modifier.size(BUTTON_SIZE).padding((BUTTON_SIZE - GLYPH_SIZE) / 2),
        )
        Icon(
            imageVector = PhosphorIcons.SkipNext,
            contentDescription = "Titre suivant",
            tint = AuraColors.TextPrimary,
            modifier = Modifier
                .size(BUTTON_SIZE)
                .padding(start = BUTTON_SIZE - GLYPH_SIZE - NEXT_GLYPH_END_PAD, end = NEXT_GLYPH_END_PAD, top = (BUTTON_SIZE - GLYPH_SIZE) / 2, bottom = (BUTTON_SIZE - GLYPH_SIZE) / 2),
        )
    }
}
