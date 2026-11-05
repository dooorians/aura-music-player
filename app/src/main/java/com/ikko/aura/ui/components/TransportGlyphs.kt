package com.ikko.aura.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform

/** The press halo of a transport glyph: how strong it is at once, and how long it takes to fade after the finger. */
internal const val HALO_ALPHA = 0.16f
internal const val HALO_FADE_MS = 450
internal const val CHASE_MS = 460

/**
 * The skip glyph: two rounded triangles that, as [progress] runs 0→1, move one slot along the direction of travel — a
 * third comes in behind them from the edge and the leading one leaves — shrinking and fading with their distance from
 * the middle, so the pair breathes. At 0 and at 1 the picture is the same: the resting pair.
 */
@Composable
internal fun SkipGlyph(forward: Boolean, color: Color, progress: () -> Float, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val t = progress()
        val h = size.height * 0.52f
        val w = h * 0.92f
        val slot = w * 0.94f
        val corner = h * 0.10f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val dir = if (forward) 1f else -1f
        // Slots -1, 0 and 1 (the pair rests on 0 and 1, centred on 0.5), carried by t.
        for (i in -1..1) {
            val x = i + t
            val d = kotlin.math.abs(x - 0.5f)
            val scale = if (d <= 0.5f) 1f else (1f - 0.65f * (d - 0.5f)).coerceIn(0.2f, 1f)
            var alpha = if (d <= 0.5f) 1f else (1f - (d - 0.5f)).coerceIn(0f, 1f)
            if (i == -1) alpha *= (0.55f + 0.45f * t)   // the one coming in lights up as it settles
            if (alpha <= 0.01f) continue
            val centreX = cx + dir * (x - 0.5f) * slot
            withTransform({
                translate(centreX, cy)
                scale(scale * dir, scale, androidx.compose.ui.geometry.Offset.Zero)
            }) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(-w / 2f, -h / 2f); lineTo(w / 2f, 0f); lineTo(-w / 2f, h / 2f); close()
                }
                drawIntoCanvas { canvas ->
                    val layer = androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha }
                    canvas.saveLayer(androidx.compose.ui.geometry.Rect(-w, -h, w, h), layer)
                    drawPath(path, color)
                    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = corner * 2f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    canvas.restore()
                }
            }
        }
    }
}
