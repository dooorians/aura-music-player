package com.ikko.aura.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The plain ground under a picture, kept a hair inside the picture's own edge.
 *
 * A cover is drawn over a ground of its own colour, both clipped to the same rounded shape, and both
 * edges are anti-aliased: at every curved corner the ground showed through the picture's soft edge as a
 * thin pale line, most of all along the bottom of a cover, where the title's darkening makes the picture
 * darkest and the ground stands out from it. Pulled in by under a dp, the ground is still all that shows
 * while a cover loads, and the edge of a cover is the cover's alone.
 */
fun Modifier.groundFill(brush: Brush, shape: Shape, inset: Dp = GROUND_INSET): Modifier = drawWithCache {
    // The outline and its path are made once per size, not on every draw: a scrolling list redraws each cover's ground
    // on every frame it moves.
    val i = inset.toPx()
    val inner = Size((size.width - 2 * i).coerceAtLeast(0f), (size.height - 2 * i).coerceAtLeast(0f))
    val outline = shape.createOutline(inner, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    onDrawBehind { translate(i, i) { drawPath(path, brush) } }
}

private val GROUND_INSET = 0.75.dp

/**
 * Clips to [shape] once, over everything drawn inside, rather than once for each thing drawn.
 *
 * A plain clip cuts every drawing operation on its own with a soft edge. A cover with a dark ramp for its
 * title over it is two operations, so along a curved corner the picture is cut to half strength and then
 * the ramp to half strength on top of it — and the ramp, which should have covered the picture completely
 * there, covers only half of what half of it left: a thin pale line of cover along the corner, on
 * almost every cover, brightest at the bottom where the ramp is darkest. Drawn into a layer of its own
 * and cut afterwards, the whole picture has one edge.
 */
fun Modifier.clipOnce(shape: Shape): Modifier = graphicsLayer {
    this.shape = shape
    clip = true
    compositingStrategy = CompositingStrategy.Offscreen
}
