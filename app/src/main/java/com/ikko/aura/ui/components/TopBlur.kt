package com.ikko.aura.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import com.ikko.aura.ui.theme.LocalAuraBatterySaver
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * Once a page is scrolled, its content blurs progressively at the top — sharp where the band begins, most
 * blurred at the edge it is cut at — and dissolves there, as iOS does under its bars, rather than running hard
 * into the status bar. It replaces a plain fade to transparent. Nothing is done at the bottom: the content runs
 * on under the tab capsule and the player, which blur what passes behind them.
 *
 * Only the top band goes through the blur, not the page: the page is drawn as it is, the band is cut out of it
 * and drawn again through two shader passes (across, then down) whose radius falls to nothing at the band's
 * lower edge, so the join cannot be seen. The band is 77 dp by the screen's width; the page's own offscreen
 * layer is the one the fade already used. At the top of a page, not scrolled, nothing of this runs at all.
 *
 * Put it on the scrolling viewport — before the scroll modifier, or on the lazy list itself — so the band stays
 * where it is while the content moves under it. Android 13 and up; below that, the fade it replaces.
 *
 * [topInset]: how much of the viewport's top is under the status bar. The pages run under it now, so their
 * content melts into the bar rather than stopping at a line below it; the band and its fade cover the bar and
 * then their usual length below it.
 */
fun Modifier.topBlur(topInset: Dp = 0.dp, strength: Density.() -> Float): Modifier = composed {
    when {
        // Under the battery saver there is nothing at the top: once a page is scrolled, the effect puts the whole
        // viewport through an offscreen layer every frame, which on this device took the GPU from ~4 ms to ~10 ms
        // per frame — measured, a plain list scrolling at 60 fps instead of 96.
        // What scrolls up under the status bar is cut at its lower edge: a plain clip, no layer, so nothing of the header
        // shows through the bar.
        LocalAuraBatterySaver.current -> Modifier.drawWithContent {
            val edge = topInset.toPx()
            clipRect(top = edge) { this@drawWithContent.drawContent() }
            // A dark scrim that thins out below the bar stands in for the blur-and-fade: the page melts into the bar
            // instead of ending on a line. A gradient rectangle, a fraction of the cost of the layer it replaces.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.72f),
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = edge + SAVER_SCRIM_DP.dp.toPx(),
                ),
                topLeft = Offset.Zero,
                size = Size(size.width, edge + SAVER_SCRIM_DP.dp.toPx()),
            )
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            Modifier
                .graphicsLayer { compositingStrategy = if (strength() > STRENGTH_EPSILON) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
                .then(TopBlurElement(topInset, strength))
        else -> Modifier.topFadeFallback(topInset, strength)
    }
}

/**
 * [topBlur] driven by how far the page has been scrolled, [scrolledPx]: none at the top, growing with the scroll
 * to all of it at [PageMotionSpec.FADE_TOP_RAMP_DP], and going back the same way. Read while drawing, so
 * scrolling recomposes nothing.
 */
fun Modifier.scrollTopBlur(topInset: Dp = 0.dp, scrolledPx: () -> Float): Modifier =
    topBlur(topInset) { (scrolledPx() / PageMotionSpec.FADE_TOP_RAMP_DP.dp.toPx()).coerceIn(0f, 1f) }

private const val STRENGTH_EPSILON = 0.001f
private const val SAVER_SCRIM_DP = 56f

/** The blur at the very top edge at full strength. It falls to nothing across the band. */
private const val TOP_BLUR_RADIUS_DP = 7f

/**
 * The fade that dissolves the edge, over this much of the band's top. At 24 dp (with a 12 dp blur) the user found
 * it too strong and cut off abruptly; it now runs over most of the band, on an eased curve.
 */
private const val TOP_EDGE_FADE_DP = 56f

/**
 * One pass of the band's blur, along [dir]: a Gaussian whose radius is [maxRadius] at the top of the band and
 * falls away (squared, so the lower part stays close to sharp) to nothing at [band]. Above the page there is
 * nothing to sample, so the very top also dissolves into what is behind it.
 */
private const val TOP_BLUR_AGSL = """
uniform shader content;
uniform float2 dir;
uniform float maxRadius;
uniform float band;

half4 main(float2 p) {
    float t = clamp(p.y / band, 0.0, 1.0);
    float r = maxRadius * (1.0 - t) * (1.0 - t);
    if (r < 0.5) { return content.eval(p); }
    float sigma = r * 0.5;
    // As many taps each side as the radius needs, about one per 1.5 px, 8 at most: most of the band is only
    // lightly blurred, and there two or three do what eight did.
    float n = clamp(ceil(r / 1.5), 1.0, 8.0);
    half4 sum = half4(0.0);
    float weights = 0.0;
    for (int i = -8; i <= 8; i++) {
        if (abs(float(i)) > n) { continue; }
        float x = float(i) * r / n;
        float w = exp(-0.5 * x * x / (sigma * sigma));
        sum += content.eval(p + dir * x) * half(w);
        weights += w;
    }
    return sum / half(weights);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object TopBlurShaders {
    private val across by lazy { RuntimeShader(TOP_BLUR_AGSL) }
    private val down by lazy { RuntimeShader(TOP_BLUR_AGSL) }
    private var broken = false

    /** Null if the device will not compile it: the band is then drawn sharp, never a crash. */
    fun effect(maxRadius: Float, band: Float): RenderEffect? =
        if (broken) null else try {
            across.setFloatUniform("dir", 1f, 0f)
            across.setFloatUniform("maxRadius", maxRadius)
            across.setFloatUniform("band", band)
            val first = RenderEffect.createRuntimeShaderEffect(across, "content")
            down.setFloatUniform("dir", 0f, 1f)
            down.setFloatUniform("maxRadius", maxRadius)
            down.setFloatUniform("band", band)
            val second = RenderEffect.createRuntimeShaderEffect(down, "content")
            RenderEffect.createChainEffect(second, first)
        } catch (e: RuntimeException) {
            broken = true
            null
        }
}

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

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class TopBlurNode(var topInset: Dp, var strength: Density.() -> Float) : Modifier.Node(), DrawModifierNode {
    private var page: GraphicsLayer? = null
    private var band: GraphicsLayer? = null
    // The effect is made again only when what it depends on changes, not every frame of a scroll.
    private var effectFor = Float.NaN
    private var effectBand = Float.NaN
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null

    override fun onDetach() {
        page?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        band?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        page = null
        band = null
        effect = null
        effectFor = Float.NaN
        effectBand = Float.NaN
    }

    override fun ContentDrawScope.draw() {
        val s = strength()
        if (s <= STRENGTH_EPSILON) {
            drawContent()
            return
        }
        val bandHeight = (topInset.toPx() + PageMotionSpec.FADE_TOP_LENGTH_DP.dp.toPx()).coerceAtMost(size.height)
        val radius = TOP_BLUR_RADIUS_DP.dp.toPx() * s
        if (radius != effectFor || bandHeight != effectBand) {
            effect = TopBlurShaders.effect(radius, bandHeight)?.asComposeRenderEffect()
            effectFor = radius
            effectBand = bandHeight
        }
        val blur = effect
        if (blur == null) {
            drawContent()
            return
        }

        val context = requireGraphicsContext()
        val p = page ?: context.createGraphicsLayer().also { page = it }
        p.record { this@draw.drawContent() }
        drawLayer(p)

        // The band: cut out of the page (inside the page's own offscreen layer, so only the page is cut), and drawn
        // again blurred. Its layer is the band's size plus room below for the downward pass, so the blur works on
        // that strip alone rather than the whole page; what is drawn is only the band.
        val below = ceil(radius).toInt()
        val b = band ?: context.createGraphicsLayer().also { band = it }
        b.record(IntSize(size.width.toInt(), ceil(bandHeight).toInt() + below)) { drawLayer(p) }
        b.renderEffect = blur
        drawRect(Color.Transparent, size = Size(size.width, bandHeight), blendMode = BlendMode.Clear)
        clipRect(bottom = bandHeight) { drawLayer(b) }

        // The edge itself: blurred alone, the top row was still cut by a straight line where the page ends. A
        // short fade over the first few dp dissolves it, as iOS pairs its blur with one.
        val edge = (topInset.toPx() + TOP_EDGE_FADE_DP.dp.toPx()).coerceAtMost(bandHeight)
        val clear = 1f - s
        drawRect(
            // Eased (smoothstep) rather than a straight ramp, so neither end of it shows as a line.
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = clear),
                0.25f to Color.Black.copy(alpha = clear + s * 0.156f),
                0.5f to Color.Black.copy(alpha = clear + s * 0.5f),
                0.75f to Color.Black.copy(alpha = clear + s * 0.844f),
                1f to Color.Black,
                startY = 0f,
                endY = edge,
            ),
            size = Size(size.width, edge),
            blendMode = BlendMode.DstIn,
        )
    }
}

/** Below Android 13, where there is no RuntimeShader: the plain fade to transparent this replaced. */
private fun Modifier.topFadeFallback(topInset: Dp, strength: Density.() -> Float): Modifier =
    graphicsLayer { compositingStrategy = if (strength() > STRENGTH_EPSILON) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
        .drawWithContent {
            drawContent()
            val top = strength()
            if (top > STRENGTH_EPSILON) {
                val topLen = topInset.toPx() + PageMotionSpec.FADE_TOP_LENGTH_DP.dp.toPx()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 1f - top),
                        1f to Color.Black,
                        startY = 0f,
                        endY = topLen,
                    ),
                    size = Size(size.width, topLen),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
