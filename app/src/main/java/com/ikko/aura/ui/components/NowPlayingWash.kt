package com.ikko.aura.ui.components

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.ikko.aura.ui.theme.LocalNowPlayingMesh
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The colours of the record that is playing, laid over the top of a page and fading to black down it, as on YouTube
 * Music: not one tint but the cover's swatches, a dark base and three soft patches. The same on Home and Bibliothèque.
 * The colours are read in draw, so a change of record fades (350 ms, see [washColors]) without recomposing the page.
 */
@Composable
fun Modifier.nowPlayingWash(): Modifier {
    val wash = washColors()
    return drawBehind {
        val w = size.width
        val h = w * 0.70f
        val c = wash.map { it.value }
        // Drawn on a layer of its own and faded out downwards as a whole: the patches reach past the wash's height, and
        // cut there they left a hard edge across the page.
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, w, h), Paint())
            drawRect(
                Brush.verticalGradient(
                    0f to c[3].copy(alpha = 0.90f), 0.45f to c[3].copy(alpha = 0.40f), 1f to Color.Transparent,
                    startY = 0f, endY = h,
                ),
            )
            fun patch(color: Color, x: Float, y: Float, r: Float, a: Float) = drawRect(
                Brush.radialGradient(
                    0f to color.copy(alpha = a), 0.55f to color.copy(alpha = a * 0.45f), 1f to Color.Transparent,
                    center = Offset(x * w, y * h), radius = r * w,
                ),
                size = Size(w, h),
            )
            patch(c[0], 0.10f, 0.06f, 0.95f, 0.85f)
            patch(c[1], 0.95f, 0.16f, 0.80f, 0.75f)
            patch(c[2], 0.55f, 0.40f, 1.05f, 0.45f)
            drawRect(
                Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent, startY = 0f, endY = h),
                size = Size(w, h),
                blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
    }
}

/**
 * The four colours of the wash: the swatches of the record that is playing, darkened so that white type holds on them,
 * moved to the next record's within 350 ms — a change of track is felt at once, and is a fade.
 */
@Composable
private fun washColors(): List<androidx.compose.animation.core.Animatable<Color, AnimationVector4D>> {
    val mesh = LocalNowPlayingMesh.current
    val targets = remember(mesh) {
        List(4) { i ->
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(mesh[i % mesh.size].toArgb(), hsl)
            Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceAtMost(0.75f), hsl[2].coerceIn(0.20f, 0.40f))))
        }
    }
    val animated = remember { targets.map { Animatable(it) } }
    LaunchedEffect(targets) {
        coroutineScope {
            animated.forEachIndexed { i, a -> launch { a.animateTo(targets[i], tween(350)) } }
        }
    }
    return animated
}
