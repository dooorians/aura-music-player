package com.ikko.aura.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * A row that scrolls sideways inside a panel, softened at its two ends: what passes the edge blurs a little and
 * fades, instead of being cut there by a straight line. The same technique as [topBlur], turned on its side: the
 * row is drawn as it is, and only a narrow band at each end is cut out and drawn again through a blur whose
 * radius falls to nothing towards the inside.
 *
 * [start] and [end] say how much of it each side wants, 0 to 1, read while drawing: the start only once the row
 * has been scrolled, the end while there is more to come. At 0 on both sides nothing of this runs.
 * Android 13 and up; below that, a plain fade.
 */
fun Modifier.horizontalEdgeBlur(start: () -> Float, end: () -> Float): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        graphicsLayer {
            compositingStrategy = if (start() > EPSILON || end() > EPSILON) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }.then(EdgeBlurElement(start, end))
    } else {
        graphicsLayer {
            compositingStrategy = if (start() > EPSILON || end() > EPSILON) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }.edgeFadeOnly(start, end)
    }

private const val EPSILON = 0.001f

/** How wide each softened end is, how strong its blur gets at the very edge, and how far it fades there. */
private const val EDGE_BAND_DP = 28f
private const val EDGE_BLUR_RADIUS_DP = 5f
private const val EDGE_FADE_TO = 0.35f

/**
 * One pass of the ends' blur, along [dir]. The radius is [maxRadius] at [edgeX] and falls away (squared) to
 * nothing [band] further in, towards [inward] (+1 when the inside is to the right of the edge, -1 to the left).
 */
private const val EDGE_BLUR_AGSL = """
uniform shader content;
uniform float2 dir;
uniform float maxRadius;
uniform float band;
uniform float edgeX;
uniform float inward;

half4 main(float2 p) {
    float t = clamp((p.x - edgeX) * inward / band, 0.0, 1.0);
    float r = maxRadius * (1.0 - t) * (1.0 - t);
    if (r < 0.5) { return content.eval(p); }
    float sigma = r * 0.5;
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
private object EdgeBlurShaders {
    private val across by lazy { RuntimeShader(EDGE_BLUR_AGSL) }
    private val down by lazy { RuntimeShader(EDGE_BLUR_AGSL) }
    private var broken = false

    /** Null if the device will not compile it: the ends are then drawn sharp, never a crash. */
    fun effect(maxRadius: Float, band: Float, edgeX: Float, inward: Float): RenderEffect? =
        if (broken) null else try {
            fun RuntimeShader.set(dx: Float, dy: Float) {
                setFloatUniform("dir", dx, dy)
                setFloatUniform("maxRadius", maxRadius)
                setFloatUniform("band", band)
                setFloatUniform("edgeX", edgeX)
                setFloatUniform("inward", inward)
            }
            across.set(1f, 0f)
            val first = RenderEffect.createRuntimeShaderEffect(across, "content")
            down.set(0f, 1f)
            val second = RenderEffect.createRuntimeShaderEffect(down, "content")
            RenderEffect.createChainEffect(second, first)
        } catch (e: RuntimeException) {
            broken = true
            null
        }
}

private data class EdgeBlurElement(val start: () -> Float, val end: () -> Float) : ModifierNodeElement<EdgeBlurNode>() {
    override fun create() = EdgeBlurNode(start, end)
    override fun update(node: EdgeBlurNode) {
        node.start = start
        node.end = end
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "horizontalEdgeBlur"
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class EdgeBlurNode(var start: () -> Float, var end: () -> Float) : Modifier.Node(), DrawModifierNode {
    private var row: GraphicsLayer? = null
    private var startBand: GraphicsLayer? = null
    private var endBand: GraphicsLayer? = null

    override fun onDetach() {
        val context = requireGraphicsContext()
        listOfNotNull(row, startBand, endBand).forEach(context::releaseGraphicsLayer)
        row = null
        startBand = null
        endBand = null
    }

    override fun ContentDrawScope.draw() {
        val s = start().coerceIn(0f, 1f)
        val e = end().coerceIn(0f, 1f)
        if (s <= EPSILON && e <= EPSILON) {
            drawContent()
            return
        }
        val context = requireGraphicsContext()
        val r = row ?: context.createGraphicsLayer().also { row = it }
        r.record { this@draw.drawContent() }
        drawLayer(r)

        val band = EDGE_BAND_DP.dp.toPx().coerceAtMost(size.width / 2f)
        val margin = ceil(EDGE_BLUR_RADIUS_DP.dp.toPx()).toInt()
        val height = size.height.toInt()
        val layerWidth = ceil(band).toInt() + margin

        if (s > EPSILON) {
            val radius = EDGE_BLUR_RADIUS_DP.dp.toPx() * s
            val effect = EdgeBlurShaders.effect(radius, band, edgeX = 0f, inward = 1f)
            if (effect != null) {
                val b = startBand ?: context.createGraphicsLayer().also { startBand = it }
                b.record(IntSize(layerWidth, height)) { drawLayer(r) }
                b.renderEffect = effect.asComposeRenderEffect()
                drawRect(Color.Transparent, size = Size(band, size.height), blendMode = BlendMode.Clear)
                clipRect(right = band) { drawLayer(b) }
            }
            fade(0f, band, 1f - (1f - EDGE_FADE_TO) * s, fromLeft = true)
        }
        if (e > EPSILON) {
            val radius = EDGE_BLUR_RADIUS_DP.dp.toPx() * e
            val left = size.width - layerWidth
            val effect = EdgeBlurShaders.effect(radius, band, edgeX = layerWidth.toFloat(), inward = -1f)
            if (effect != null) {
                val b = endBand ?: context.createGraphicsLayer().also { endBand = it }
                b.record(IntSize(layerWidth, height)) { translate(-left, 0f) { drawLayer(r) } }
                b.renderEffect = effect.asComposeRenderEffect()
                val bandLeft = size.width - band
                drawRect(Color.Transparent, topLeft = Offset(bandLeft, 0f), size = Size(band, size.height), blendMode = BlendMode.Clear)
                clipRect(left = bandLeft) { translate(left, 0f) { drawLayer(b) } }
            }
            fade(size.width - band, band, 1f - (1f - EDGE_FADE_TO) * e, fromLeft = false)
        }
    }
}

/** Fades the band from [edgeAlpha] at the outer edge to fully kept [band] further in. */
private fun ContentDrawScope.fade(x: Float, band: Float, edgeAlpha: Float, fromLeft: Boolean) {
    val outer = Color.Black.copy(alpha = edgeAlpha)
    val brush = if (fromLeft) {
        Brush.horizontalGradient(0f to outer, 1f to Color.Black, startX = x, endX = x + band)
    } else {
        Brush.horizontalGradient(0f to Color.Black, 1f to outer, startX = x, endX = x + band)
    }
    drawRect(brush, topLeft = Offset(x, 0f), size = Size(band, size.height), blendMode = BlendMode.DstIn)
}

/** Below Android 13: the fade alone. */
private fun Modifier.edgeFadeOnly(start: () -> Float, end: () -> Float): Modifier = drawWithContent {
    drawContent()
    val s = start().coerceIn(0f, 1f)
    val e = end().coerceIn(0f, 1f)
    val band = EDGE_BAND_DP.dp.toPx().coerceAtMost(size.width / 2f)
    if (s > EPSILON) fade(0f, band, 1f - (1f - EDGE_FADE_TO) * s, fromLeft = true)
    if (e > EPSILON) fade(size.width - band, band, 1f - (1f - EDGE_FADE_TO) * e, fromLeft = false)
}
