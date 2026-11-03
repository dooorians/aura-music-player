package com.ikko.aura.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidPath

/**
 * The round and pill buttons (search, back, profile ring, shuffle, sort): the same glass as the library's menus, the dark
 * glass of the design (black at 26 %, a hairline of white at 7 % inside its edge), not the white veil they had. [pressed]
 * darkens the fill a little.
 */
@androidx.compose.runtime.Composable
fun Modifier.glassSurface(shape: Shape, pressed: Boolean = false): Modifier {
    val u = designUnit()
    return this
        .glassShadow(shape)
        .background(Color.Black.copy(alpha = if (pressed) 0.38f else 0.26f), shape)
        .border(u * 1.5f, Color.White.copy(alpha = 0.07f), shape)
}

/**
 * The soft shadow of the validated design, drawn outside the shape only (see [glassSurface]): wide, pushed down, and
 * deeper than before. One shadow only: a second, close one left a thin dark rim at the shape's edge.
 */
/** Off for now: no shadow under the translucent surfaces (the code below is kept, to be switched back on). */
private const val GLASS_SHADOWS = false

fun Modifier.glassShadow(shape: Shape): Modifier =
    if (!GLASS_SHADOWS) this else this.drawWithCache {
        // The shadow is made once for this size, as a picture, and then only drawn: blurring it afresh on every frame
        // of a page opening made the cards late and jerky. Made at a quarter of the size and drawn back up: a blur this
        // soft has nothing in it that a quarter of the pixels cannot hold, and it costs a sixteenth to make.
        val q = 0.25f
        val blur = 34f * density
        val pad = (blur * 1.5f).toInt() + 1
        val w = ((size.width + 2 * pad) * q).toInt().coerceAtLeast(1)
        val h = ((size.height + 2 * pad) * q).toInt().coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val outline = shape.createOutline(size, layoutDirection, this)
        val shapePath = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }
        val path = shapePath.asAndroidPath()
        canvas.scale(q, q)
        canvas.translate(pad.toFloat(), pad.toFloat())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            setShadowLayer(blur, 0f, 12f * density, Color.Black.copy(alpha = 0.26f).toArgb())
        }
        canvas.drawPath(path, paint)
        val image = bitmap.asImageBitmap()
        onDrawBehind {
            // What is under the shape is cut away here, at full resolution, so no dark rim is left at its edge: only the
            // shadow outside it is drawn.
            clipPath(shapePath, androidx.compose.ui.graphics.ClipOp.Difference) {
            drawImage(
                image,
                srcSize = androidx.compose.ui.unit.IntSize(w, h),
                dstOffset = androidx.compose.ui.unit.IntOffset(-pad, -pad),
                dstSize = androidx.compose.ui.unit.IntSize((size.width + 2 * pad).toInt(), (size.height + 2 * pad).toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
            )
            }
        }
    }
