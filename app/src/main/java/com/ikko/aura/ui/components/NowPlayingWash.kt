package com.ikko.aura.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.animation.Animatable
import androidx.compose.ui.graphics.ShaderBrush
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
 * The colours of the record that is playing, laid over the top of a page, as on YouTube Music — measured on its screenshots:
 * dark (about a fifth of full lightness), two different colours, one on the left and one on the right, blended on a slant
 * rather than a tint laid evenly across; and falling away slowly down the page, easing out to black over the upper
 * 60 % of it. The same on Home and Bibliothèque. The colours are read in draw, so a change of record fades (350 ms,
 * see [washColors]) without recomposing the page.
 */
@Composable
fun Modifier.nowPlayingWash(): Modifier {
    val wash = washColors()
    return drawBehind {
        val w = size.width
        val h = size.height * 0.62f
        val c = wash.map { it.value }
        // On a layer of its own so the fall-off can be laid over the colours as a whole.
        drawIntoCanvas { canvas ->
            // A little taller than the fall-off: the layer's own edge must lie where nothing is left to cut.
            val layerH = h * 1.12f
            canvas.saveLayer(Rect(0f, 0f, w, layerH), Paint())
            // Left and right: two colours on a slant, so neither side is the other's mirror.
            drawRect(
                Brush.linearGradient(
                    0f to c[0], 0.55f to c[0].mix(c[1], 0.5f), 1f to c[1],
                    start = Offset(0f, h * 0.05f), end = Offset(w, h * 0.30f),
                ),
                size = Size(w, layerH),
            )
            // A softer, lighter patch off to one side and a darker one lower down: the uneven light of a real surface.
            drawRect(
                Brush.radialGradient(
                    0f to c[2].copy(alpha = 0.55f), 1f to Color.Transparent,
                    center = Offset(w * 0.22f, h * 0.30f), radius = w * 0.55f,
                ),
                size = Size(w, layerH),
            )
            drawRect(
                Brush.radialGradient(
                    0f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent,
                    center = Offset(w * 0.80f, h * 0.55f), radius = w * 0.5f,
                ),
                size = Size(w, layerH),
            )
            // Dither: a fine noise laid in before the fall-off, so that a panel that shows fewer shades breaks the dark
            // gradient into grain instead of into bands with visible edges.
            drawRect(
                brush = ShaderBrush(BitmapShader(ditherBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)),
                size = Size(w, layerH),
                alpha = 0.022f,
            )
            // The fall-off: slow, easing out, nothing left by the bottom of the wash.
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black, 0.12f to Color.Black.copy(alpha = 0.88f), 0.30f to Color.Black.copy(alpha = 0.62f),
                    0.50f to Color.Black.copy(alpha = 0.34f), 0.70f to Color.Black.copy(alpha = 0.13f),
                    0.88f to Color.Black.copy(alpha = 0.012f), 1f to Color.Transparent,
                    startY = 0f, endY = h,
                ),
                size = Size(w, layerH),
                blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
    }
}

private val ditherBitmap: Bitmap by lazy {
    val rnd = kotlin.random.Random(11)
    val px = IntArray(64 * 64) { val v = rnd.nextInt(256); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
    Bitmap.createBitmap(px, 64, 64, Bitmap.Config.ARGB_8888)
}

private fun Color.mix(other: Color, t: Float) = androidx.compose.ui.graphics.lerp(this, other, t)

/**
 * The four colours of the wash: the swatches of the record that is playing, darkened so that white type holds on them,
 * moved to the next record's within 350 ms — a change of track is felt at once, and is a fade.
 */
@Composable
private fun washColors(): List<androidx.compose.animation.core.Animatable<Color, AnimationVector4D>> {
    val mesh = LocalNowPlayingMesh.current
    val targets = remember(mesh) {
        fun hslOf(color: Color) = FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
        fun hueGap(a: Float, b: Float) = kotlin.math.abs(a - b).let { if (it > 180f) 360f - it else it }
        // Left is the first swatch, right the one whose hue is furthest from it: two colours that really differ.
        val first = mesh[0]
        val rest = mesh.drop(1)
        val right = rest.maxByOrNull { hueGap(hslOf(first)[0], hslOf(it)[0]) } ?: first
        val third = (rest - right).firstOrNull() ?: right
        listOf(first, right, third, right).map { color ->
            val hsl = hslOf(color)
            Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceIn(0.35f, 0.80f), hsl[2].coerceIn(0.16f, 0.22f))))
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
