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
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Once a page is scrolled, its content goes soft where it meets the top of the screen — sharp below the band, blurrier
 * and blurrier towards the edge — as iOS does under its bars: a progressive blur, not a blurred copy laid over the
 * sharp page (which left every title with a glow around it, the sharp letters still there under it).
 *
 * Cheap on purpose; the first version of this cost the GPU 6 ms a frame, a list at 60 fps on a 96 Hz screen. It put
 * the whole page in an offscreen layer and blurred the band with a two-pass shader. This one:
 *
 *  - draws the page below the band as it is, untouched: the page itself costs nothing it did not cost before;
 *  - draws the band's content once at a quarter of its size and blurs that with the platform's own blur at
 *    [LEVELS] radii, each in a layer of its own — a few thousand pixels each, in the hardware path;
 *  - lays the band out as a ramp of windows: the sharp content fading out going up, each blur level fading in and then
 *    out for the next, the strongest holding at the edge. The windows add up to one everywhere, so every row is
 *    drawn once, at a radius that grows steadily towards the top — the sharp letters are gone where the soft ones are.
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
    if (TOP_BLUR_ENABLED) this.then(TopBlurElement(topInset, strength)) else this

/**
 * Switch for the whole effect: false draws pages plain under the status bar. Off in the QA build only (the user's
 * request, for Aura QA); the other builds keep the blur.
 */
private val TOP_BLUR_ENABLED: Boolean get() = !com.ikko.aura.BuildConfig.IS_QA

/**
 * [topBlur] driven by how far the page has been scrolled, [scrolledPx]: none at the top, growing with the scroll to
 * all of it at [PageMotionSpec.FADE_TOP_RAMP_DP], and going back the same way. Read while drawing, so scrolling
 * recomposes nothing.
 */
fun Modifier.scrollTopBlur(topInset: Dp = 0.dp, scrolledPx: () -> Float): Modifier =
    topBlur(topInset) { (scrolledPx() / PageMotionSpec.FADE_TOP_RAMP_DP.dp.toPx()).coerceIn(0f, 1f) }

private const val STRENGTH_EPSILON = 0.001f

/** The scale the blurred levels are worked at. */
private const val DOWNSCALE = 0.5f

/** The radius of each blur level at full strength, in pixels of the downscaled band: the last is the top edge's. */
private val LEVEL_RADII = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f)
private val LEVELS = LEVEL_RADII.size

/** How many stops sample the eased ramp. */
private const val RAMP_STOPS = 16

/** How far below the status bar the blur reaches, in dp: just the status bar and a margin. */
private const val BAND_DP = 12f

/** A little dark at the very top, so the clock and the battery stay readable over soft content. */
private const val SCRIM_ALPHA = 0.10f

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
    private var group: GraphicsLayer? = null
    private var rawBand: GraphicsLayer? = null
    private var sharp: GraphicsLayer? = null
    private var base: GraphicsLayer? = null
    private var levels: Array<GraphicsLayer>? = null
    // The blur is made again only when its radius changes by a visible step, not on every frame of a scroll.
    private var radiusStep = Int.MIN_VALUE

    override fun onDetach() {
        val context = requireGraphicsContext()
        group?.let(context::releaseGraphicsLayer)
        rawBand?.let(context::releaseGraphicsLayer)
        sharp?.let(context::releaseGraphicsLayer)
        base?.let(context::releaseGraphicsLayer)
        levels?.forEach(context::releaseGraphicsLayer)
        group = null; rawBand = null; sharp = null; base = null; levels = null
        radiusStep = Int.MIN_VALUE
    }

    /**
     * The alpha of window [k] (0 = the sharp content, 1..LEVELS = the blur levels) at a row of depth [depth]: 0 at the
     * band's bottom, up to the strength's reach at its top. Window k weighs 1 - |depth - k|, floored at 0, so the weights
     * of neighbouring windows add up to one.
     */
    private fun weight(k: Int, depth: Float) = (1f - abs(depth - k)).coerceIn(0f, 1f)

    // The brushes are made again only when the strength or the band changes by a visible step: during most of a scroll
    // neither does, and nothing is allocated.
    private var rampKey = Long.MIN_VALUE
    private val ramps = arrayOfNulls<Brush>(2 * (LEVELS + 1))

    /** Window [k]'s weight over a band of [height] pixels. The depth follows a smoothstep of the row, so the blur starts
     *  and ends gently instead of at a constant slope. */
    private fun ramp(k: Int, small: Boolean, reach: Float, height: Float): Brush {
        val slot = k + if (small) LEVELS + 1 else 0
        ramps[slot]?.let { return it }
        val stops = Array(RAMP_STOPS + 1) { i ->
            val u = i.toFloat() / RAMP_STOPS
            val eased = u * u * (3f - 2f * u)
            u to Color.Black.copy(alpha = weight(k, reach * (1f - eased)))
        }
        return Brush.verticalGradient(colorStops = stops, startY = 0f, endY = height).also { ramps[slot] = it }
    }

    override fun ContentDrawScope.draw() {
        val s = strength()
        if (s <= STRENGTH_EPSILON) {
            drawContent()
            return
        }
        val bandHeight = ceil((topInset.toPx() + BAND_DP.dp.toPx()).coerceAtMost(size.height))
        val context = requireGraphicsContext()
        val width = ceil(size.width).toInt()
        val height = bandHeight.toInt()

        // How deep into the levels the strength has got at the top edge: none at the start of the scroll, all of them at
        // full strength. A fraction in between puts the top rows between two levels, so nothing steps.
        val reach = (round(s * LEVELS * 32f) / 32f).coerceIn(0.05f, LEVELS.toFloat())
        val key = (reach * 32f).toLong() * 100000L + height
        if (key != rampKey) { rampKey = key; ramps.fill(null) }

        // The page below the band, as it always was.
        clipRect(top = bandHeight) { this@draw.drawContent() }

        val rawLayer = rawBand ?: context.createGraphicsLayer().also { rawBand = it }
        rawLayer.record(IntSize(width, height)) {
            this@draw.drawContent()
        }

        val sharpLayer = sharp ?: context.createGraphicsLayer().also {
            // Offscreen: the ramp is a mask on this layer's own pixels, which it can only be if they are rendered apart.
            it.compositingStrategy = CompositingStrategy.Offscreen
            it.blendMode = BlendMode.Plus
            sharp = it
        }
        sharpLayer.record(IntSize(width, height)) {
            drawLayer(rawLayer)
            drawRect(ramp(0, false, reach, bandHeight), size = Size(width.toFloat(), bandHeight), blendMode = BlendMode.DstIn)
        }

        // The band's content once, at a quarter of its size in each direction: sixteen times fewer pixels, and a blur is
        // the one picture that loses nothing by being small.
        val smallW = ceil(width * DOWNSCALE).toInt().coerceAtLeast(1)
        val smallH = ceil(height * DOWNSCALE).toInt().coerceAtLeast(1)
        val baseLayer = base ?: context.createGraphicsLayer().also { base = it }
        baseLayer.record(IntSize(smallW, smallH)) {
            withTransform({ scale(DOWNSCALE, DOWNSCALE, pivot = Offset.Zero) }) { drawLayer(rawLayer) }
        }
        val blurred = levels ?: Array(LEVELS) {
            context.createGraphicsLayer().also { l ->
                l.compositingStrategy = CompositingStrategy.Offscreen
                l.blendMode = BlendMode.Plus
            }
        }.also { levels = it }
        // In steps of half a pixel of the strongest level: a radius that changed every frame would rebuild the effects
        // every frame.
        val step = (LEVEL_RADII.last() * s * 2f).roundToInt().coerceAtLeast(1)
        val remake = step != radiusStep
        radiusStep = step
        // The windows are ADDED, in a layer of their own that starts empty, and that layer is then laid over the page: their
        // weights add up to one, so the sum is exactly the picture (and its opacity) at a blend of radii. Laid one over
        // another as translucent layers they did not — two halves composite to three quarters, and the quarter that was
        // missing let the black behind show through as dark bars across the blur.
        val groupLayer = group ?: context.createGraphicsLayer().also {
            it.compositingStrategy = CompositingStrategy.Offscreen
            group = it
        }
        groupLayer.record(IntSize(width, height)) {
            drawLayer(sharpLayer)
            withTransform({ scale(1f / DOWNSCALE, 1f / DOWNSCALE, pivot = Offset.Zero) }) {
                for (i in 0 until LEVELS) {
                    val layer = blurred[i]
                    layer.record(IntSize(smallW, smallH)) {
                        drawLayer(baseLayer)
                        drawRect(ramp(i + 1, true, reach, smallH.toFloat()), size = Size(smallW.toFloat(), smallH.toFloat()), blendMode = BlendMode.DstIn)
                    }
                    if (remake) {
                        val radius = (LEVEL_RADII[i] * (step / (LEVEL_RADII.last() * 2f))).coerceAtLeast(0.5f)
                        layer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    }
                    drawLayer(layer)
                }
            }
        }
        drawLayer(groupLayer)

        drawRect(
            Brush.verticalGradient(0f to Color.Black.copy(alpha = SCRIM_ALPHA * s), 0.6f to Color.Transparent, startY = 0f, endY = bandHeight),
            size = Size(size.width, bandHeight),
        )
    }
}

/**
 * How far a list has been scrolled, for [scrollTopBlur]: the first item's offset, and "a lot" once it is out of view.
 * Read while drawing.
 */
fun androidx.compose.foundation.lazy.LazyListState.topScrollPx(): Float =
    if (firstVisibleItemIndex > 0) 1_000_000f else firstVisibleItemScrollOffset.toFloat()

fun androidx.compose.foundation.lazy.grid.LazyGridState.topScrollPx(): Float =
    if (firstVisibleItemIndex > 0) 1_000_000f else firstVisibleItemScrollOffset.toFloat()

/** Soft top edge for a list that starts below a header: blurred at the edge it is cut at, once it has scrolled. */
fun Modifier.listTopBlur(state: androidx.compose.foundation.lazy.LazyListState): Modifier =
    scrollTopBlur { state.topScrollPx() }

fun Modifier.listTopBlur(state: androidx.compose.foundation.lazy.grid.LazyGridState): Modifier =
    scrollTopBlur { state.topScrollPx() }
