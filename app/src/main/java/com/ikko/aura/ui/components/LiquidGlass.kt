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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ikko.aura.ui.components.backdrop.Backdrop
import com.ikko.aura.ui.components.backdrop.backdrops.emptyBackdrop
import com.ikko.aura.ui.components.backdrop.drawBackdrop
import com.ikko.aura.ui.components.backdrop.effects.blur
import com.ikko.aura.ui.components.backdrop.effects.colorControls
import com.ikko.aura.ui.components.backdrop.effects.lens
import com.ikko.aura.ui.components.backdrop.effects.effect
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
    blurRadiusDp: Float = BLUR_RADIUS_DP,
    dropShadow: Boolean = true,
): Modifier {
    if (LocalAuraBatterySaver.current) {
        return background(tint.copy(alpha = 0.55f), shape)
            .border(GLASS_EDGE_WIDTH, AuraColors.GlassBorder, shape)
    }
    val backdrop = LocalAuraBackdrop.current
    val density = LocalDensity.current
    // Pre-multiplied by the resolution scale: the effects run on the downscaled recording, so a
    // radius in screen pixels would come out three times too wide once it is upscaled back.
    val blurPx = with(density) { blurRadiusDp.dp.toPx() } * GLASS_RESOLUTION_SCALE
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
                depthEffect = false,
                chromaticAberration = false,
            )
        },
        highlight = { Highlight.Default },
        shadow = if (dropShadow) ({ Shadow.Default }) else null,
        onDrawSurface = { drawRect(color = tint.copy(alpha = tintAlpha), size = size) },
        backdropScale = GLASS_RESOLUTION_SCALE,
    )
}

/**
 * A liquid-glass panel with its content sitting *on* it rather than *inside* it. Always live glass:
 * there is no flat stand-in phase, because the player no longer slides — see AuraMotion.sheetEnter.
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
    blurRadiusDp: Float = BLUR_RADIUS_DP,
    dropShadow: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier, contentAlignment = contentAlignment) {
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
                .liquidGlass(shape, tint, tintAlpha, blurRadiusDp, dropShadow),
        )
        content()
    }
}

/**
 * A shape that asks for its corners each time it is drawn, so a surface whose corners are animated can still be
 * given a flat fill and a border under the battery saver.
 */
private class DynamicShape(private val provider: () -> CornerBasedShape) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ) = provider().createOutline(size, layoutDirection, density)
}

/**
 * [liquidGlass] for a surface that changes shape while it is on screen: a menu growing out of the button that
 * opened it. [shape] is asked for at every draw, so its corners can follow the animation, and [refraction] is how
 * hard the lens bends what is behind the glass — 1 is the resting glass, more is the bulging, liquid look of a
 * surface in motion, which is what iOS shows while its menus are travelling and then lets settle.
 *
 * Both are read while drawing, so animating them redraws the glass and nothing is recomposed.
 */
@Composable
fun Modifier.liquidGlassMorphing(
    shape: () -> CornerBasedShape,
    refraction: () -> Float = { 1f },
    tint: Color = GlassSurfaceTint,
    tintAlpha: Float = SURFACE_OPACITY,
    blurRadiusDp: Float = BLUR_RADIUS_DP,
    dropShadow: Boolean = true,
): Modifier {
    if (LocalAuraBatterySaver.current) {
        val movingShape = remember(shape) { DynamicShape(shape) }
        return background(tint.copy(alpha = 0.55f), movingShape)
            .border(GLASS_EDGE_WIDTH, AuraColors.GlassBorder, movingShape)
    }
    val backdrop = LocalAuraBackdrop.current
    val density = LocalDensity.current
    val blurPx = with(density) { blurRadiusDp.dp.toPx() } * GLASS_RESOLUTION_SCALE
    val lensHeightPx = with(density) { (LENS_HEIGHT * LENS_MAX_DP).dp.toPx() } * GLASS_RESOLUTION_SCALE
    val lensAmountPx = with(density) { (LENS_AMOUNT * LENS_MAX_DP).dp.toPx() } * GLASS_RESOLUTION_SCALE

    return drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = {
            colorControls(saturation = 1f + 0.5f * VIBRANCY)
            blur(blurPx)
            lens(
                refractionHeight = lensHeightPx,
                refractionAmount = lensAmountPx * refraction().coerceIn(0f, 4f),
                depthEffect = false,
                chromaticAberration = false,
            )
        },
        highlight = { Highlight.Default },
        shadow = if (dropShadow) ({ Shadow.Default }) else null,
        onDrawSurface = { drawRect(color = tint.copy(alpha = tintAlpha), size = size) },
        backdropScale = GLASS_RESOLUTION_SCALE,
    )
}
