package com.ikko.aura.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val playedFraction = dragFraction
        ?: (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val displayedMs = (playedFraction * safeDuration).toLong()

    val transition = rememberInfiniteTransition(label = "wavy-progress")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(WAVE_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavy-progress-phase",
    )
    // Flattening rather than freezing: a wave that simply stops mid-crest reads as a glitch,
    // whereas easing the amplitude to zero reads as the track settling.
    val amplitudeScale = animateFloatAsState(
        targetValue = if (isPlaying && dragFraction == null) 1f else 0f,
        animationSpec = tween(AMPLITUDE_SETTLE_MS),
        label = "wavy-progress-amplitude",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .pointerInput(safeDuration) {
                    coroutineScope {
                        launch {
                            detectTapGestures { offset ->
                                onSeek(((offset.x / size.width).coerceIn(0f, 1f) * safeDuration).toLong())
                            }
                        }
                        launch {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    dragFraction?.let { onSeek((it * safeDuration).toLong()) }
                                    dragFraction = null
                                },
                                onDragCancel = { dragFraction = null },
                            ) { change, dragAmount ->
                                val current = dragFraction ?: (change.position.x / size.width)
                                dragFraction = (current + dragAmount / size.width).coerceIn(0f, 1f)
                                change.consume()
                            }
                        }
                    }
                },
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
                val path = Path()
                // Read only while there is a wave to move. Not reading it is what unsubscribes
                // this draw from the animation, so a paused track stops invalidating the canvas
                // ninety times a second to redraw the same straight line.
                val shift = if (amplitude > 0f) phase.value * wavelength else 0f
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
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
