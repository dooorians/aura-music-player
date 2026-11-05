package com.ikko.aura.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

/**
 * The edge of the player's liquid glass, drawn rather than computed.
 *
 * The player's panels get their rim from a shader that lights each point of the edge by how squarely
 * its outward normal faces one direction (down and to the right): full strength at the top-left and
 * bottom-right corners, none at the other two, and 71% of it along every straight side. Measured on
 * the player's transport tiles, pixel by pixel, the result is a bright line about a millimetre wide —
 * strongest on the outermost pixel of the tile and fading over the next three — and the same on all
 * four sides. This draws that: three nested strokes, each a little wider and a little fainter than the
 * last, on the four sides, and the same three on each corner with a sweep that follows the shader's
 * angle so the two lit corners glow and the other two go dark.
 *
 * It is the same picture without a shader per button, so it is complete on the first frame, costs a
 * handful of cached fills, moves with its page for free, and cannot flicker while something loads.
 * It must be drawn inside a clip to the shape: each stroke is centred on the edge and only its inner
 * half is meant to show.
 */
fun Modifier.playerRim(shape: Shape, strength: Float = 1f, lightDegrees: Float = 45f): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val w = size.width
    val h = size.height
    val radius = ((outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f)
        .coerceAtMost(min(w, h) / 2f)
    val row = ROW_DP.dp.toPx()

    // One entry per layer: how wide its stroke is and how strong.
    val widths = FloatArray(LAYER_ALPHA.size) { 2f * row * (it + 1) }

    val sides = List(LAYER_ALPHA.size) { Color.White.copy(alpha = LAYER_ALPHA[it] * strength) }

    // The corner arcs' sweep brushes, per layer, per corner — built once per size.
    val centres = listOf(
        Offset(radius, radius),
        Offset(w - radius, radius),
        Offset(w - radius, h - radius),
        Offset(radius, h - radius),
    )
    val startAngles = floatArrayOf(180f, 270f, 0f, 90f)
    val corners = if (radius > 0f) {
        List(LAYER_ALPHA.size) { layer ->
            List(4) { corner ->
                Brush.sweepGradient(
                    colorStops = Array(STOPS + 1) { i ->
                        val degrees = 360f * i / STOPS
                        i.toFloat() / STOPS to Color.White.copy(
                            alpha = (LAYER_ALPHA[layer] * strength * factorAt(degrees, lightDegrees)).coerceIn(0f, 1f),
                        )
                    },
                    center = centres[corner],
                )
            }
        }
    } else {
        emptyList()
    }

    onDrawBehind {
        for (layer in LAYER_ALPHA.indices) {
            val width = widths[layer]
            val color = sides[layer]
            if (w - 2 * radius > 0f) {
                drawLine(color, Offset(radius, 0f), Offset(w - radius, 0f), width)
                drawLine(color, Offset(radius, h), Offset(w - radius, h), width)
            }
            if (h - 2 * radius > 0f) {
                drawLine(color, Offset(0f, radius), Offset(0f, h - radius), width)
                drawLine(color, Offset(w, radius), Offset(w, h - radius), width)
            }
            if (radius > 0f) {
                val stroke = Stroke(width = width)
                val topLefts = listOf(
                    Offset(0f, 0f),
                    Offset(w - 2 * radius, 0f),
                    Offset(w - 2 * radius, h - 2 * radius),
                    Offset(0f, h - 2 * radius),
                )
                for (corner in 0 until 4) {
                    drawArc(
                        brush = corners[layer][corner],
                        startAngle = startAngles[corner],
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = topLefts[corner],
                        size = Size(2 * radius, 2 * radius),
                        style = stroke,
                    )
                }
            }
        }
    }
}

/** Thickness of one pixel row of the rim, in dp: 1px at this device's 2.5 density. */
private const val ROW_DP = 0.4f

/**
 * How opaque each of the three nested layers is along a straight side. Read off the player's tiles as
 * the rim's brightness on the outermost three pixel rows (+88, +66 and +24 of 255 over a typical
 * background), converted to the alphas that stacking three strokes needs to give exactly those.
 */
private val LAYER_ALPHA = floatArrayOf(0.185f, 0.261f, 0.130f)

private const val STOPS = 72

/** The straight sides' share of the shader's peak — cos 45 degrees. */
private const val SIDE_SHARE = 0.70710677f

/**
 * How much stronger than a straight side the rim is where the edge's outward normal points at
 * [degrees] (clockwise from east, y down): up to 1.41 at the lit corners, 0 at the dark ones. The lit
 * corners sit at [lightDegrees] and its opposite — 45 for the player's own tiles, turned a little from
 * one round button to the next so no two catch the light the same way.
 */
private fun factorAt(degrees: Float, lightDegrees: Float): Float =
    abs(cos(Math.toRadians((degrees - lightDegrees).toDouble()))).toFloat() / SIDE_SHARE

/**
 * The hairline edge of the reference's buttons: one 1dp stroke, lit along the top and the left, fading to almost nothing
 * through the middle and picking up a faint glow again at the bottom-right. Draw it inside a clip to the shape.
 */
fun Modifier.glassEdge(shape: Shape, strength: Float = 1f, angleDegrees: Float? = null, tint: Color = Color.White): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val width = 1.dp.toPx()
    // Square things are lit from the top-left corner; the wider the shape, the more the light falls straight from above,
    // so a long edge is lit along its whole top instead of fading away from the corner.
    val tilt = (size.height / size.width).coerceIn(0f, 1f).let { it * it * it }
    // With an angle given, the light runs along that direction (0 = from the left, 90 = from the top), so neighbouring
    // cards can each catch it from a different side.
    val rad = Math.toRadians((angleDegrees ?: 0f).toDouble())
    val dx = cos(rad).toFloat()
    val dy = kotlin.math.sin(rad).toFloat()
    val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
    val centre = Offset(size.width / 2f, size.height / 2f)
    val start = if (angleDegrees == null) Offset.Zero else Offset(centre.x - dx * half, centre.y - dy * half)
    val end = if (angleDegrees == null) Offset(size.width * tilt, size.height) else Offset(centre.x + dx * half, centre.y + dy * half)
    val brush = Brush.linearGradient(
        0f to tint.copy(alpha = 0.42f * strength),
        0.45f to tint.copy(alpha = 0.07f * strength),
        1f to tint.copy(alpha = 0.16f * strength),
        start = start,
        end = end,
    )
    onDrawBehind { drawOutline(outline, brush, style = Stroke(width = 2 * width)) }
}

/**
 * The pills' edge, after Apple Music's: a hairline lit along the top and thinning to nothing round the corners and down the
 * sides, a fainter one along the bottom, and a soft sheen just under the top line. Draw it inside a clip to the shape.
 */
fun Modifier.pillEdge(shape: Shape): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val line = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.40f),
        0.22f to Color.White.copy(alpha = 0.05f),
        0.5f to Color.White.copy(alpha = 0.015f),
        0.8f to Color.White.copy(alpha = 0.05f),
        1f to Color.White.copy(alpha = 0.20f),
        endY = size.height,
    )
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.07f),
        1f to Color.Transparent,
        endY = 14.dp.toPx(),
    )
    val stroke = Stroke(width = 1.5.dp.toPx())
    onDrawBehind {
        drawOutline(outline, sheen)
        drawOutline(outline, line, style = stroke)
    }
}
