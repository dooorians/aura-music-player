/*
 * Integration glue for the vendored Kyant0/backdrop renderer in
 * [com.ikko.aura.ui.components.backdrop], adapted from BitChord's own
 * `LiquidGlass.kt` (which in turn adapted Echo-Music's GlassEffectConfig).
 * The effect chain and its constants are BitChord's, so the player's panels
 * read as the same material.
 */
package com.ikko.aura.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ikko.aura.ui.components.backdrop.Backdrop
import com.ikko.aura.ui.components.backdrop.backdrops.emptyBackdrop
import com.ikko.aura.ui.components.backdrop.drawBackdrop
import com.ikko.aura.ui.components.backdrop.effects.blur
import com.ikko.aura.ui.components.backdrop.effects.colorControls
import com.ikko.aura.ui.components.backdrop.effects.lens
import com.ikko.aura.ui.components.backdrop.highlight.Highlight
import com.ikko.aura.ui.components.backdrop.shadow.Shadow
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalAuraBatterySaver

/**
 * What a liquid-glass surface samples. In Aura this is the blurred album-art backdrop only, not
 * the whole page: the player's panels sit directly on it and nothing else, so recording just the
 * backdrop keeps a glass surface from sampling a layer it is itself inside of — the feedback that
 * shows up as smearing when a glass panel overlaps another one.
 */
val LocalAuraBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

/**
 * False while the screen is moving — a page sliding in or out — and true once it has come to rest.
 *
 * The glass samples the backdrop at its own position on screen, so a panel that is sliding is
 * re-recording the backdrop and re-running its colour matrix, blur and lens on every frame of the
 * slide. Measured over five opens and closes of the player: 7.5% janky frames at p90 32ms with the
 * glass live throughout, 1.4% at p90 16ms with it out of the way. So it stays out of the way until
 * the movement is over, and fades in behind the panel that is already there.
 */
val LocalGlassSettled = staticCompositionLocalOf { mutableStateOf(true) }

/** Apple-matched defaults, carried over from BitChord unchanged. */
private const val VIBRANCY = 1f
private const val BLUR_RADIUS_DP = 8f
private const val LENS_HEIGHT = 0.5f
private const val LENS_AMOUNT = 0.5f
private const val LENS_MAX_DP = 48f
internal const val SURFACE_OPACITY = 0.4f

/**
 * Fraction of full resolution the backdrop is recorded and processed at — nine times fewer pixels
 * through the colour matrix, the blur and the lens shader. The blur is what hides the upscale.
 */
private const val GLASS_RESOLUTION_SCALE = 0.33f

private val GLASS_EDGE_WIDTH = 0.5.dp

/** How long the stand-in fill takes to give way to the real glass. */
internal const val GLASS_SETTLE_FADE_MS = 140

/** The dark tint the glass is filled with, matching the near-black the rest of the app sits on. */
internal val GlassSurfaceTint = Color(0xFF121212)

/**
 * Draws this composable as a liquid-glass surface over [LocalAuraBackdrop]: saturation lift, blur,
 * then a lens refraction with depth and chromatic aberration at the rim, finished with a tint and
 * the specular [Highlight] that gives the edge its lit look.
 *
 * [tint] paints the surface itself. Left at its default the panel is neutral dark glass; giving it
 * a colour is how the play button gets its wash without losing the refraction underneath.
 *
 * Under the battery saver the surface is filled flat instead — the same promise that setting makes
 * everywhere else in the app, and the only version that costs nothing per frame.
 *
 * [shape] must be a [CornerBasedShape]: the lens shader needs corner radii and throws without them.
 */
@Composable
fun Modifier.liquidGlass(
    shape: CornerBasedShape,
    tint: Color = GlassSurfaceTint,
    tintAlpha: Float = SURFACE_OPACITY,
): Modifier {
    if (LocalAuraBatterySaver.current) {
        return background(tint.copy(alpha = 0.55f), shape)
            .border(GLASS_EDGE_WIDTH, AuraColors.GlassBorder, shape)
    }
    val backdrop = LocalAuraBackdrop.current
    val density = LocalDensity.current
    // Pre-multiplied by the resolution scale: the effects run on the downscaled recording, so a
    // radius in screen pixels would come out three times too wide once it is upscaled back.
    val blurPx = with(density) { BLUR_RADIUS_DP.dp.toPx() } * GLASS_RESOLUTION_SCALE
    val lensHeightPx = with(density) { (LENS_HEIGHT * LENS_MAX_DP).dp.toPx() } * GLASS_RESOLUTION_SCALE
    val lensAmountPx = with(density) { (LENS_AMOUNT * LENS_MAX_DP).dp.toPx() } * GLASS_RESOLUTION_SCALE

    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1f + 0.5f * VIBRANCY)
            blur(blurPx)
            lens(
                refractionHeight = lensHeightPx,
                refractionAmount = lensAmountPx,
                depthEffect = true,
                chromaticAberration = true,
            )
        },
        highlight = { Highlight.Default },
        shadow = { Shadow.Default },
        onDrawSurface = { drawRect(color = tint.copy(alpha = tintAlpha), size = size) },
        backdropScale = GLASS_RESOLUTION_SCALE,
    )
}

/**
 * A liquid-glass panel with its content sitting *on* it rather than *inside* it.
 *
 * [liquidGlass] is a draw modifier, so it shares a node with whatever it wraps — and a node
 * redraws as a whole. Anything animating inside the panel therefore re-ran the whole effect
 * chain (colour matrix, blur, lens shader) on every frame, even though the backdrop behind it had
 * not moved. Measured on the device over six track changes: 9.9% janky frames at p90 42ms with the
 * glass live, 3.9% at p90 17ms with it neutralised — because the artwork, the title, the lyric line
 * and the up-next row all animate at once, and each panel they sit in was recomputing its glass
 * ninety times a second for the duration.
 *
 * Splitting them means the glass is its own node with no content of its own. It redraws when the
 * backdrop changes or when the panel is resized — and not because a word inside it moved. The
 * result is pixel-identical; only what invalidates changed.
 *
 * The glass sizes itself to the panel, which the content decides, so callers put their padding on
 * [content] and their layout size on [modifier] exactly as before.
 */
@Composable
fun LiquidGlassPanel(
    shape: CornerBasedShape,
    modifier: Modifier = Modifier,
    tint: Color = GlassSurfaceTint,
    tintAlpha: Float = SURFACE_OPACITY,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val settled by LocalGlassSettled.current
    // Held back until the panel has stopped moving, then faded over the flat fill standing in for
    // it. A fill and a glass surface differ by the refraction at the rim and a little colour, so
    // over a tenth of a second on a dark backdrop the swap is not something that can be caught.
    val fillAlpha by animateFloatAsState(
        targetValue = if (settled) 0f else 1f,
        animationSpec = tween(if (settled) GLASS_SETTLE_FADE_MS else 0),
        label = "glass-settle",
    )

    Box(modifier = modifier, contentAlignment = contentAlignment) {
        if (settled) {
            Spacer(
                Modifier
                    .matchParentSize()
                // Its own layer, and the reason is not cosmetic. Compose invalidates draw up to
                // the nearest enclosing layer, so without one here every glass surface redrew
                // whenever *anything* on the screen did — and on the player something always
                // does: the seek bar's wave runs at 90Hz. Each of those redraws re-records the
                // whole backdrop at [GLASS_RESOLUTION_SCALE] and pushes it through the colour
                // matrix, the blur and the lens shader, six times over. Given a layer, the
                // rendered glass is kept and reused until the backdrop behind it actually
                // changes.
                    .graphicsLayer { }
                    .liquidGlass(shape, tint, tintAlpha),
            )
        }
        if (fillAlpha > 0f) {
            Spacer(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = fillAlpha }
                    // Matched to the panel's own opacity rather than a constant. At a flat 0.55
                    // the play button's pink stand-in was visibly paler than the glass that
                    // replaced it, so the swap showed on the one panel that is not neutral.
                    .background(tint.copy(alpha = tintAlpha.coerceAtLeast(0.55f)), shape)
                    .border(GLASS_EDGE_WIDTH, AuraColors.GlassBorder, shape),
            )
        }
        content()
    }
}
