package com.ikko.aura.ui.theme

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The three colours of the library that come from the record that is playing: the accent, the tinted
 * white the page's type is set in, and the type that sits on the accent.
 *
 * They are read from a state that moves, so a change of record fades in rather than jumping: whichever
 * leaf reads [accent] (or the others) is redrawn for the length of the fade and nothing above it is.
 */
@Stable
class CoverColors internal constructor(initial: Targets) {
    private val accentAnim = Animatable(initial.accent)
    private val tintAnim = Animatable(initial.tint)
    private val onAccentAnim = Animatable(initial.onAccent)

    /** The full-strength accent: pale, saturated. */
    val accent: Color get() = accentAnim.value

    /** White with a trace of the record's hue: page titles, and — at reduced strength — every other line of type. */
    val tint: Color get() = tintAnim.value

    /** For type set on the accent. */
    val onAccent: Color get() = onAccentAnim.value

    suspend fun animateTo(target: Targets) = coroutineScope {
        val spec = tween<Color>(FADE_MS)
        launch { accentAnim.animateTo(target.accent, spec) }
        launch { tintAnim.animateTo(target.tint, spec) }
        launch { onAccentAnim.animateTo(target.onAccent, spec) }
        Unit
    }

    class Targets(val accent: Color, val tint: Color, val onAccent: Color)

    companion object {
        private const val FADE_MS = 400

        private fun hsl(h: Float, s: Float, l: Float) = Color(ColorUtils.HSLToColor(floatArrayOf(h, s, l)))

        /** The colours for a record whose hue as a whole is [hue] degrees, or the brand's rose where it has none. */
        fun targetsFor(hue: Float?): Targets =
            if (hue == null) {
                Targets(accent = Color(0xFFDDAEB0), tint = hsl(358f, 0.30f, 0.94f), onAccent = hsl(358f, 0.60f, 0.10f))
            } else {
                Targets(accent = hsl(hue, 0.78f, 0.76f), tint = hsl(hue, 0.45f, 0.94f), onAccent = hsl(hue, 0.60f, 0.10f))
            }
    }
}

/** Held for the whole app: the colours of the record that is playing. */
val LocalCoverColors = compositionLocalOf<CoverColors> { CoverColors(CoverColors.targetsFor(null)) }

/**
 * The cover colours for a record of hue [hue], fading to the next one over 400 ms whenever it changes.
 */
@Composable
fun rememberCoverColors(hue: Float?): CoverColors {
    val colors = remember { CoverColors(CoverColors.targetsFor(hue)) }
    LaunchedEffect(hue) { colors.animateTo(CoverColors.targetsFor(hue)) }
    return colors
}
