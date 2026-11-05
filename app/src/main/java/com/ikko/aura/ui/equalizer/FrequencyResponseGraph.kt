package com.ikko.aura.ui.equalizer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikko.aura.core.audio.dsp.EqResponse
import com.ikko.aura.core.audio.dsp.FilterSpec
import com.ikko.aura.core.audio.dsp.IsoBands
import com.ikko.aura.ui.theme.AuraFontFamily

/** What the graph's height spans, either side of 0 dB. */
private const val RANGE_DB = 15f

private val GRID_HZ = doubleArrayOf(50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0)
private val LABELLED_HZ = doubleArrayOf(100.0, 1_000.0, 10_000.0)
private val LABELS = arrayOf("100", "1k", "10k")
private val GRID_DB = floatArrayOf(-12f, -6f, 6f, 12f)

/**
 * The curve the listener is hearing, 20 Hz to 20 kHz on a logarithmic axis, in dB.
 *
 * Everything the draw pass touches is made once and kept: the [EqResponse] with its sine tables, the arrays it
 * fills, the [Path] (rewound, never rebuilt), the stroke and the brush. The gains are read inside the draw lambda,
 * so a slider moving invalidates this layer's drawing only — no recomposition, no layout, and nothing allocated per
 * frame, which is what lets the curve follow the finger at the display's full rate (96 or 120 Hz).
 *
 * The curve is a Catmull-Rom spline through the response's points, drawn as cubic Béziers: smooth between the grid
 * points without the overshoot a looser fit would add to a sharp AutoEq notch.
 */
@Composable
fun FrequencyResponseGraph(
    bands: EqBandsState,
    parametric: List<FilterSpec>?,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val response = remember { EqResponse(points = 96) }
    val gains = remember { FloatArray(IsoBands.COUNT) }
    val curve = remember { FloatArray(response.points) }
    val xs = remember { FloatArray(response.points) }
    val ys = remember { FloatArray(response.points) }
    val path = remember { Path() }
    val fillPath = remember { Path() }
    val bandPositions = remember { FloatArray(IsoBands.COUNT) { response.position(IsoBands.FREQUENCIES_HZ[it]) } }
    val gridPositions = remember { FloatArray(GRID_HZ.size) { response.position(GRID_HZ[it]) } }
    val stroke = remember(density) {
        with(density) { Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round) }
    }
    val hairline = remember(density) { with(density) { 1.dp.toPx() } }
    val dash = remember(density) { with(density) { PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())) } }
    val dotRadius = remember(density) { with(density) { 3.5.dp.toPx() } }
    val lineColor = if (active) accent else Color.White.copy(alpha = 0.45f)
    val fill = remember(lineColor) { Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f))) }
    val currentParametric by rememberUpdatedState(parametric)

    BoxWithConstraints(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val w = size.width
                    val h = size.height
                    val mid = h / 2f
                    val pxPerDb = mid / RANGE_DB

                    // The grid: decades and their halves, ±6 and ±12 dB, a firmer line at 0 dB.
                    for (i in gridPositions.indices) {
                        val x = gridPositions[i] * w
                        drawLine(Color.White.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, h), hairline)
                    }
                    for (db in GRID_DB) {
                        val y = mid - db * pxPerDb
                        drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(w, y), hairline, pathEffect = dash)
                    }
                    drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, mid), Offset(w, mid), hairline)

                    // The response, from the gains as they are this frame.
                    val p = currentParametric
                    if (p != null) {
                        response.compute(p, curve)
                    } else {
                        bands.copyInto(gains)
                        response.computeGraphic(gains, curve)
                    }
                    val n = response.points
                    for (i in 0 until n) {
                        xs[i] = w * i / (n - 1f)
                        ys[i] = mid - curve[i].coerceIn(-RANGE_DB, RANGE_DB) * pxPerDb
                    }
                    path.rewind()
                    path.moveTo(xs[0], ys[0])
                    for (i in 0 until n - 1) {
                        val i0 = if (i == 0) 0 else i - 1
                        val i3 = if (i + 2 >= n) n - 1 else i + 2
                        path.cubicTo(
                            xs[i] + (xs[i + 1] - xs[i0]) / 6f, ys[i] + (ys[i + 1] - ys[i0]) / 6f,
                            xs[i + 1] - (xs[i3] - xs[i]) / 6f, ys[i + 1] - (ys[i3] - ys[i]) / 6f,
                            xs[i + 1], ys[i + 1],
                        )
                    }
                    fillPath.rewind()
                    fillPath.addPath(path)
                    fillPath.lineTo(w, h)
                    fillPath.lineTo(0f, h)
                    fillPath.close()
                    drawPath(fillPath, fill)
                    drawPath(path, lineColor, style = stroke)

                    // Where each slider stands, on the graphic curve.
                    if (p == null) {
                        for (i in 0 until IsoBands.COUNT) {
                            val y = mid - gains[i].coerceIn(-RANGE_DB, RANGE_DB) * pxPerDb
                            drawCircle(Color.White.copy(alpha = if (active) 0.9f else 0.5f), dotRadius, Offset(bandPositions[i] * w, y))
                        }
                    }
                },
        )
        // Labels are text, laid out once; the drawing above never measures any.
        val labelStyle = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
        for (i in LABELLED_HZ.indices) {
            val x = maxWidth * response.position(LABELLED_HZ[i])
            Text(LABELS[i], style = labelStyle, modifier = Modifier.align(Alignment.BottomStart).offset(x = x + 3.dp).padding(bottom = 2.dp))
        }
        Text("+12", style = labelStyle, modifier = Modifier.align(Alignment.TopStart).offset(y = maxHeight * (0.5f - 12f / RANGE_DB / 2f) - 13.dp).padding(start = 4.dp))
        Text("−12", style = labelStyle, modifier = Modifier.align(Alignment.TopStart).offset(y = maxHeight * (0.5f + 12f / RANGE_DB / 2f) + 1.dp).padding(start = 4.dp))
    }
}
