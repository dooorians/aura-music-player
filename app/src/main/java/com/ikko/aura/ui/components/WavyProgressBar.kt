package com.ikko.aura.ui.components

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.ikko.aura.ui.theme.AuraColors
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

private val TRACK_HEIGHT = 26.dp
private val WAVE_STROKE = 2.5.dp
private val LINE_STROKE = 2.dp
private val WAVE_AMPLITUDE = 5.5.dp
private val WAVELENGTH = 20.dp
private val THUMB_WIDTH = 3.5.dp
private val THUMB_HEIGHT = 22.dp
private val END_DOT_RADIUS = 2.dp

private const val WAVE_CYCLE_MS = 1400
private const val AMPLITUDE_SETTLE_MS = 450

/**
 * The seek bar from the mockup: the elapsed side of the track is a travelling sine wave, the
 * remaining side a flat line ending in a dot, and a vertical bar marks the join.
 *
 * Material 3's own `LinearWavyProgressIndicator` is a progress *indicator* — it has no thumb and
 * cannot be dragged — and this project is pinned to material3 1.3.1, which predates it entirely.
 * Drawn here instead, which also buys the two behaviours the mockup implies: the wave only
 * travels while audio is actually playing, and it flattens out while you are scrubbing so the
 * thumb has a straight line to sit on.
 *
 * Everything animated is read inside the draw lambda, so a frame of wave motion invalidates the
 * draw pass alone — no recomposition, no relayout. The same rule the rest of the app's hot paths
 * follow.
 */
@Composable
fun WavyProgressBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * How far in from its own edges the bar is drawn. Given the whole screen's width with this as its margin (in
     * the player), it is drawn where it always was while its touch area runs to the screen's edges — where the
     * page's margin had left a strip that belonged to Android's back gesture: grabbing the bar from the side went
     * back instead of seeking. The whole width is kept out of the back gesture too.
     */
    edgeInset: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val playedFraction = dragFraction
        ?: (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val displayedMs = (playedFraction * safeDuration).toLong()

    val waving = isPlaying && dragFraction == null
    // Flattening rather than freezing: a wave that simply stops mid-crest reads as a glitch,
    // whereas easing the amplitude to zero reads as the track settling.
    val amplitudeScale = animateFloatAsState(
        targetValue = if (waving) 1f else 0f,
        animationSpec = tween(AMPLITUDE_SETTLE_MS),
        label = "wavy-progress-amplitude",
    )
    // The wave's travel — one wavelength every WAVE_CYCLE_MS, as before — advanced by a frame loop that
    // only runs while there is a wave to move: while playing, and on through the settle so it flattens
    // while still travelling. This was an infinite transition, which asks for every frame for as long as
    // the player is composed: a paused player drew nothing, but its main thread still woke ~90 times a
    // second (17% of a core, measured) to step a value nobody read.
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(waving) {
        if (!waving && amplitudeScale.value == 0f) return@LaunchedEffect
        val runForMs = if (waving) Long.MAX_VALUE else AMPLITUDE_SETTLE_MS.toLong()
        var last = -1L
        var elapsed = 0L
        while (elapsed < runForMs) {
            withFrameMillis { now ->
                if (last >= 0L) {
                    val dt = now - last
                    elapsed += dt
                    phase.floatValue = (phase.floatValue + dt.toFloat() / WAVE_CYCLE_MS) % 1f
                }
                last = now
            }
        }
    }

    // One Path for the wave, emptied and refilled each frame rather than allocated each frame: the wave is
    // redrawn ninety-odd times a second while a track plays, and a new native Path per frame was garbage for
    // the collector to chase for nothing.
    val wavePath = remember { Path() }

    Column(modifier = modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .systemGestureExclusion()
                .pointerInput(safeDuration, edgeInset) {
                    val over = edgeInset.toPx()
                    val track = (size.width - 2 * over).coerceAtLeast(1f)
                    fun fractionAt(x: Float) = ((x - over) / track).coerceIn(0f, 1f)
                    coroutineScope {
                        launch {
                            detectTapGestures { offset ->
                                onSeek((fractionAt(offset.x) * safeDuration).toLong())
                            }
                        }
                        launch {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    dragFraction = fractionAt(offset.x)
                                },
                                onDragEnd = {
                                    dragFraction?.let { onSeek((it * safeDuration).toLong()) }
                                    dragFraction = null
                                },
                                onDragCancel = { dragFraction = null },
                            ) { change, dragAmount ->
                                val current = dragFraction ?: fractionAt(change.position.x)
                                dragFraction = (current + dragAmount / track).coerceIn(0f, 1f)
                                change.consume()
                            }
                        }
                    }
                },
        ) {
        Canvas(
            modifier = Modifier
                .padding(horizontal = edgeInset)
                .fillMaxSize()
                // Its own layer, so a frame of wave motion re-records this canvas and nothing else.
                // Without one the redraw lands on the nearest enclosing layer — the whole player's
                // content — and the page, its glass and its text were re-recorded on every frame while
                // a track played (~2 ms of main thread per frame, traced on the device).
                .graphicsLayer(),
        ) {
            val centerY = size.height / 2f
            val strokePx = WAVE_STROKE.toPx()
            val linePx = LINE_STROKE.toPx()
            val thumbW = THUMB_WIDTH.toPx()
            val wavelength = WAVELENGTH.toPx()
            val amplitude = WAVE_AMPLITUDE.toPx() * amplitudeScale.value

            // Half the thumb is kept clear at either end so it never hangs off the edge, and the
            // wave and the line are laid out inside what is left.
            val start = thumbW / 2f
            val end = size.width - thumbW / 2f
            val usable = (end - start).coerceAtLeast(1f)
            val thumbX = start + usable * playedFraction

            if (thumbX > start + 0.5f) {
                val path = wavePath.apply { reset() }
                // Read only while there is a wave to move. Not reading it is what unsubscribes
                // this draw from the animation, so a paused track stops invalidating the canvas
                // ninety times a second to redraw the same straight line.
                val shift = if (amplitude > 0f) phase.floatValue * wavelength else 0f
                var x = start
                path.moveTo(x, centerY)
                while (x < thumbX) {
                    val next = (x + WAVE_STEP_PX).coerceAtMost(thumbX)
                    val y = centerY + amplitude * sin((next - start + shift) / wavelength * 2f * PI.toFloat())
                    path.lineTo(next, y)
                    x = next
                }
                drawPath(
                    path = path,
                    color = AuraColors.TextPrimary,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }

            if (thumbX < end - 0.5f) {
                drawLine(
                    color = RemainingTrackColor,
                    start = Offset(thumbX + thumbW, centerY),
                    end = Offset(end, centerY),
                    strokeWidth = linePx,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = AuraColors.TextPrimary,
                    radius = END_DOT_RADIUS.toPx(),
                    center = Offset(end, centerY),
                )
            }

            drawRoundRect(
                color = AuraColors.TextPrimary,
                topLeft = Offset(thumbX - thumbW / 2f, centerY - THUMB_HEIGHT.toPx() / 2f),
                size = androidx.compose.ui.geometry.Size(thumbW, THUMB_HEIGHT.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbW / 2f),
            )
        }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = edgeInset + 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(displayedMs),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = com.ikko.aura.ui.theme.TABULAR_NUMBERS),
                color = AuraColors.TextSecondary,
            )
            Text(
                text = "-" + formatDuration((safeDuration - displayedMs).coerceAtLeast(0L)),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = com.ikko.aura.ui.theme.TABULAR_NUMBERS),
                color = AuraColors.TextSecondary,
            )
        }
    }
}

/**
 * Sampling step for the wave path. At this density a 2px step is a little under the stroke width,
 * which is fine enough that the polyline is indistinguishable from a curve and coarse enough that
 * a full-width wave is a couple of hundred segments rather than a couple of thousand.
 */
private const val WAVE_STEP_PX = 2f

private val RemainingTrackColor = Color(0x59FFFFFF)

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
