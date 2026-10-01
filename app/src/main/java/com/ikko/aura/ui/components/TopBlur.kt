package com.ikko.aura.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Once a page is scrolled, its content goes soft where it meets the top of the screen — sharpest below the band,
 * most blurred at the edge — as iOS does under its bars.
 *
 * Cheap on purpose; the first version of this cost the GPU 6 ms a frame, a list at 60 fps on a 96 Hz screen. It put
 * the whole page in an offscreen layer and blurred the band with a two-pass shader. This one:
 *
 *  - draws the page as it is, in a plain layer (a recorded display list, not a texture), so the page itself costs
 *    nothing it did not cost before;
 *  - draws the top band of that layer a second time into a small layer of its own — the band, not the page —
 *    fades it out downward with a gradient, and blurs *that* with the platform's own blur, which is done in the
 *    hardware path and at a fraction of the resolution;
 *  - lays that blurred, fading band over the sharp page. Where it is opaque the page is soft, where it is
 *    transparent the page is sharp, and between the two it cross-fades: a progressive blur, built from one blur.
 *
 * At the top of a page, not scrolled, none of this runs and the page is drawn directly.
 *
 * Put it on the scrolling viewport — before the scroll modifier, or on the lazy list itself — so the band stays where
 * it is while the content moves under it.
 *
 * [topInset]: how much of the viewport's top is under the status bar. The pages run under it, so the band covers the
 * bar and then its usual length below it.
 */
fun Modifier.topBlur(topInset: Dp = 0.dp, strength: Density.() -> Float): Modifier =
    this.then(TopBlurElement(topInset, strength))

/**
 * [topBlur] driven by how far the page has been scrolled, [scrolledPx]: none at the top, growing with the scroll to
 * all of it at [PageMotionSpec.FADE_TOP_RAMP_DP], and going back the same way. Read while drawing, so scrolling
 * recomposes nothing.
 */
fun Modifier.scrollTopBlur(topInset: Dp = 0.dp, scrolledPx: () -> Float): Modifier =
    topBlur(topInset) { (scrolledPx() / PageMotionSpec.FADE_TOP_RAMP_DP.dp.toPx()).coerceIn(0f, 1f) }

private const val STRENGTH_EPSILON = 0.001f

/** The scale the band is worked at. */
private const val DOWNSCALE = 0.25f

/** The blur at the very top edge at full strength, in dp: enough to soften text, not enough to cost. */
private const val TOP_BLUR_RADIUS_DP = 9f

private data class TopBlurElement(val topInset: Dp, val strength: Density.() -> Float) : ModifierNodeElement<TopBlurNode>() {
    override fun create() = TopBlurNode(topInset, strength)
    override fun update(node: TopBlurNode) {
        node.topInset = topInset
        node.strength = strength
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "topBlur"
    }
}

private class TopBlurNode(var topInset: Dp, var strength: Density.() -> Float) : Modifier.Node(), DrawModifierNode {
    private var band: GraphicsLayer? = null
    // The blur is made again only when its radius changes by a visible step, not on every frame of a scroll.
    private var radiusStep = Int.MIN_VALUE

    override fun onDetach() {
        band?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        band = null
        radiusStep = Int.MIN_VALUE
    }

    override fun ContentDrawScope.draw() {
        val s = strength()
        if (s <= STRENGTH_EPSILON) {
            drawContent()
            return
        }
        val bandHeight = (topInset.toPx() + PageMotionSpec.FADE_TOP_LENGTH_DP.dp.toPx()).coerceAtMost(size.height)
        val context = requireGraphicsContext()

        // The page is drawn straight, as it always was: recording all of it into a layer first, only to draw the layer,
        // cost the UI thread a full re-record every frame of a scroll.
        drawContent()

        val b = band ?: context.createGraphicsLayer().also {
            // Offscreen: the gradient below is a mask on this layer's own pixels, which it can only be if they are
            // rendered apart from what is around them.
            it.compositingStrategy = CompositingStrategy.Offscreen
            band = it
        }
        // The band is worked at a quarter of its size in each direction: sixteen times fewer pixels to draw, mask and
        // blur, and blown back up it is already soft — a blur is the one picture that loses nothing by being small.
        val smallW = ceil(size.width * DOWNSCALE).toInt().coerceAtLeast(1)
        val smallH = ceil(bandHeight * DOWNSCALE).toInt().coerceAtLeast(1)
        b.record(IntSize(smallW, smallH)) {
            withTransform({ scale(DOWNSCALE, DOWNSCALE, pivot = Offset.Zero) }) { this@draw.drawContent() }
            // Eased (smoothstep), so neither end of the fade shows as a line.
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black,
                    0.25f to Color.Black.copy(alpha = 0.844f),
                    0.5f to Color.Black.copy(alpha = 0.5f),
                    0.75f to Color.Black.copy(alpha = 0.156f),
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = smallH.toFloat(),
                ),
                size = Size(smallW.toFloat(), smallH.toFloat()),
                blendMode = BlendMode.DstIn,
            )
            // A little dark over what is left, strongest at the very top: the clock and the battery stay readable over
            // soft content. SrcAtop, so it only tints the band's own pixels and never paints where there are none.
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.34f),
                    0.6f to Color.Transparent,
                    startY = 0f,
                    endY = smallH.toFloat(),
                ),
                size = Size(smallW.toFloat(), smallH.toFloat()),
                blendMode = BlendMode.SrcAtop,
            )
        }
        // In steps of half a pixel: a radius that changed every frame would rebuild the effect every frame.
        val step = (TOP_BLUR_RADIUS_DP.dp.toPx() * DOWNSCALE * s * 2f).roundToInt().coerceAtLeast(1)
        if (step != radiusStep) {
            radiusStep = step
            val radius = step / 2f
            b.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
        }
        withTransform({ scale(1f / DOWNSCALE, 1f / DOWNSCALE, pivot = Offset.Zero) }) { drawLayer(b) }
    }
}
