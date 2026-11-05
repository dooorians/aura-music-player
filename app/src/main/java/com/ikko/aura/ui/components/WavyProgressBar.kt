package com.ikko.aura.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ikko.aura.ui.theme.AuraColors
import java.util.Locale

import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer

private val IDLE_HEIGHT = 7.dp
private val ACTIVE_HEIGHT = 12.dp

/**
 * BitChord-style capsule scrubber: a thin rounded track that thickens under the finger.
 * It has no separate thumb and does not run an animation loop while playback continues.
 */
@Composable
fun WavyProgressBar(
    positionMs: () -> Long,
    durationMs: () -> Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    edgeInset: Dp = 0.dp,
) {
    val accent = rememberPlayerAccent()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val safeDuration = durationMs().coerceAtLeast(1L)
    val displayedSec by remember(safeDuration) {
        derivedStateOf {
            val frac = dragFraction ?: (positionMs().toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
            ((frac * safeDuration).toLong() / 1000L)
        }
    }
    val totalSec = safeDuration / 1000L
    val height by animateDpAsState(
        targetValue = if (dragging) ACTIVE_HEIGHT else IDLE_HEIGHT,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "progress-height",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ACTIVE_HEIGHT + 22.dp)
                .systemGestureExclusion()
                .pointerInput(safeDuration, edgeInset) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        fun fractionAt(x: Float): Float {
                            val start = edgeInset.toPx()
                            val width = (size.width - 2f * start).coerceAtLeast(1f)
                            return ((x - start) / width).coerceIn(0f, 1f)
                        }
                        dragFraction = fractionAt(down.position.x)
                        onSeek((dragFraction!! * safeDuration).toLong())

                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!pointer.pressed) {
                                pointer.consume()
                                break
                            }
                            if (pointer.positionChanged()) {
                                dragFraction = fractionAt(pointer.position.x)
                                onSeek((dragFraction!! * safeDuration).toLong())
                                pointer.consume()
                            }
                        }
                        dragging = false
                        dragFraction = null
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = edgeInset)
                    .height(height),
            ) {
                val value = dragFraction ?: (positionMs().toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(
                    color = accent.copy(alpha = 0.26f),
                    size = size,
                    cornerRadius = radius,
                )
                val filled = size.width * value
                if (filled > 0f) {
                    drawRoundRect(
                        color = accent.copy(alpha = 0.92f),
                        size = Size(filled.coerceAtLeast(size.height).coerceAtMost(size.width), size.height),
                        cornerRadius = radius,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = edgeInset + 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatSeconds(displayedSec),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = com.ikko.aura.ui.theme.TABULAR_NUMBERS,
                ),
                color = accent.copy(alpha = 0.7f),
            )
            Text(
                text = "-${formatSeconds((totalSec - displayedSec).coerceAtLeast(0L))}",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = com.ikko.aura.ui.theme.TABULAR_NUMBERS,
                ),
                color = accent.copy(alpha = 0.7f),
            )
        }
    }
}

private fun formatSeconds(totalSeconds: Long): String {
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
