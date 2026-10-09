package com.ikko.aura.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.ikko.aura.ui.theme.LocalNowPlayingMesh
import kotlin.math.abs

/**
 * The colour of the player's text and controls: one of the cover's own colours, chosen to stand apart from the
 * backdrop that the cover also colours. The backdrop is the mean of the cover's swatches; the accent is the swatch
 * whose hue is furthest from it (weighted by how saturated it is), then moved in lightness until it contrasts with it:
 * light on a dark backdrop, dark on a light one. A cover with nothing but one hue gets a tint of it, pale or deep.
 */
@Composable
fun rememberPlayerAccent(): Color {
    val mesh = LocalNowPlayingMesh.current
    val target = remember(mesh) { playerAccentFor(mesh) }
    val animated by animateColorAsState(target, tween(200), label = "player-accent")
    return animated
}

internal fun playerAccentFor(mesh: List<Color>): Color {
    if (mesh.isEmpty()) return Color.White
    val hsl = mesh.map { c -> FloatArray(3).also { ColorUtils.colorToHSL(c.toArgb(), it) } }
    // The backdrop's hue: the circular mean of the swatches, weighted by saturation.
    var x = 0.0; var y = 0.0
    hsl.forEach { h ->
        val a = Math.toRadians(h[0].toDouble()); val w = h[1].toDouble() + 0.05
        x += Math.cos(a) * w; y += Math.sin(a) * w
    }
    val bgHue = ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
    val bgLum = (mesh.map { it.luminance() }.average()).toFloat()
    val bgIsLight = bgLum > 0.38f

    fun gap(h: Float): Float = abs(h - bgHue).let { if (it > 180f) 360f - it else it }
    val best = hsl.maxByOrNull { gap(it[0]) * (0.3f + it[1]) }
    val hue: Float
    val sat: Float
    if (best != null && gap(best[0]) > 40f && best[1] > 0.25f) {
        hue = best[0]; sat = best[1].coerceIn(0.55f, 0.95f)
    } else {
        // Nothing of another hue on this cover: its own hue, lightly coloured, as pale (or deep) as the contrast needs.
        hue = bgHue; sat = 0.35f
    }
    val light = if (bgIsLight) 0.20f else 0.82f
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, sat, light)))
}
