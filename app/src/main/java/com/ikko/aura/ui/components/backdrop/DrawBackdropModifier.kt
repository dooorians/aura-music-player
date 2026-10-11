/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.ikko.aura.ui.components.backdrop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ikko.aura.ui.components.backdrop.backdrops.LayerBackdrop
import com.ikko.aura.ui.components.backdrop.highlight.Highlight
import com.ikko.aura.ui.components.backdrop.highlight.HighlightElement
import com.ikko.aura.ui.components.backdrop.internal.ShapeProvider
import com.ikko.aura.ui.components.backdrop.internal.recordLayer
import com.ikko.aura.ui.components.backdrop.shadow.InnerShadow
import com.ikko.aura.ui.components.backdrop.shadow.InnerShadowElement
import com.ikko.aura.ui.components.backdrop.shadow.Shadow
import com.ikko.aura.ui.components.backdrop.shadow.ShadowElement

/**
 * A screen that is travelling as a whole — the player's card rising or sinking — and where its glass will
 * come to rest.
 *
 * What a glass surface draws depends on where it sits over its backdrop, so a screen in motion has every
 * panel on it working out a fresh sample, and running the colour matrix, the blur and the lens over it,
 * on every frame: on the player, six of those chains a frame, which is what made the card travel in steps.
 *
 * While [frozen], a surface keeps the picture it has and simply moves with its screen. So that the picture
 * is the right one to keep, [offset] is how far the screen is from where it will rest at the moment it is
 * frozen: the surface samples as if it were already there, which is what it will show at the end. Nothing
 * changes when it is let go again, because what it had was the resting picture all along.
 */
class GlassSettle {
    /** Read while drawing, so setting it redraws the glass once — which is how the resting picture is taken. */
    var offset: Offset by androidx.compose.runtime.mutableStateOf(Offset.Zero)
    var frozen: Boolean by androidx.compose.runtime.mutableStateOf(false)
    var isClosing: Boolean = false

    /**
     * The travelling screen's own content — inside its movement — and where that content's origin sits in
     * the window when the screen is at rest (outside its movement). Set by the screen from its layout.
     *
     * With these, a surface works out exactly where it will rest, whatever the screen's movement is made of.
     * [offset] alone only undoes a vertical shift, and the player's card is also narrowed while it travels
     * (to 90% at the mini player): the glass sampled its backdrop up to a hundred pixels from where it would
     * at rest, and jumped to the right picture when it was let go — the flash at the end of the opening.
     */
    var anchor: androidx.compose.ui.layout.LayoutCoordinates? = null
    var restOrigin: Offset = Offset.Zero

    /** How far [coordinates] are from where they will rest, in the window; null when that cannot be told. */
    fun restShift(coordinates: androidx.compose.ui.layout.LayoutCoordinates?): Offset? {
        val anchor = anchor ?: return null
        val coordinates = coordinates ?: return null
        if (!anchor.isAttached || !coordinates.isAttached) return null
        return runCatching {
            coordinates.positionInWindow() - (restOrigin + anchor.localPositionOf(coordinates, Offset.Zero))
        }.getOrNull()
    }
}

val LocalGlassSettle = androidx.compose.runtime.staticCompositionLocalOf<GlassSettle?> { null }

private val DefaultHighlight = { Highlight.Default }
private val DefaultShadow = { Shadow.Default }
private val DefaultOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { it() }

fun Modifier.drawPlainBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
                backdropScale = 1f
            )
        )
}

fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    highlight: (() -> Highlight?)? = DefaultHighlight,
    shadow: (() -> Shadow?)? = DefaultShadow,
    innerShadow: (() -> InnerShadow?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null,
    // Vendored addition: records the backdrop into a layer at this fraction of the
    // surface resolution and draws it scaled back up, so the RenderEffect chain
    // processes far fewer pixels. Pixel-sized effect parameters (blur radius, lens
    // refraction) must be pre-multiplied by the same factor by the caller; the blur
    // hides the upscaling. 1f keeps the original full resolution behavior.
    backdropScale: Float = 1f
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .then(
            if (innerShadow != null) {
                InnerShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = innerShadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (shadow != null) {
                ShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = shadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (highlight != null) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = highlight
                )
            } else {
                Modifier
            }
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
                backdropScale = backdropScale.coerceIn(0.05f, 1f)
            )
        )
}

private class DrawBackdropElement(
    val backdrop: Backdrop,
    val shapeProvider: ShapeProvider,
    val effects: BackdropEffectScope.() -> Unit,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val exportedBackdrop: LayerBackdrop?,
    val onDrawBehind: (DrawScope.() -> Unit)?,
    val onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    val onDrawSurface: (DrawScope.() -> Unit)?,
    val onDrawFront: (DrawScope.() -> Unit)?,
    val backdropScale: Float
) : ModifierNodeElement<DrawBackdropNode>() {

    override fun create(): DrawBackdropNode {
        return DrawBackdropNode(
            backdrop = backdrop,
            shapeProvider = shapeProvider,
            effects = effects,
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            onDrawBehind = onDrawBehind,
            onDrawBackdrop = onDrawBackdrop,
            onDrawSurface = onDrawSurface,
            onDrawFront = onDrawFront,
            backdropScale = backdropScale
        )
    }

    override fun update(node: DrawBackdropNode) {
        node.backdrop = backdrop
        node.shapeProvider = shapeProvider
        node.effects = effects
        node.layerBlock = layerBlock
        if (node.exportedBackdrop != exportedBackdrop) {
            node.exportedBackdrop?.layerCoordinates = null
            node.exportedBackdrop = exportedBackdrop
        }
        node.onDrawBehind = onDrawBehind
        node.onDrawBackdrop = onDrawBackdrop
        node.onDrawSurface = onDrawSurface
        node.onDrawFront = onDrawFront
        node.backdropScale = backdropScale
        node.invalidateDrawCache()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawBackdrop"
        properties["backdrop"] = backdrop
        properties["shapeProvider"] = shapeProvider
        properties["effects"] = effects
        properties["layerBlock"] = layerBlock
        properties["exportedBackdrop"] = exportedBackdrop
        properties["onDrawBehind"] = onDrawBehind
        properties["onDrawBackdrop"] = onDrawBackdrop
        properties["onDrawSurface"] = onDrawSurface
        properties["onDrawFront"] = onDrawFront
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DrawBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (shapeProvider != other.shapeProvider) return false
        if (effects != other.effects) return false
        if (layerBlock != other.layerBlock) return false
        if (exportedBackdrop != other.exportedBackdrop) return false
        if (onDrawBehind != other.onDrawBehind) return false
        if (onDrawBackdrop != other.onDrawBackdrop) return false
        if (onDrawSurface != other.onDrawSurface) return false
        if (onDrawFront != other.onDrawFront) return false
        if (backdropScale != other.backdropScale) return false

        return true
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + shapeProvider.hashCode()
        result = 31 * result + effects.hashCode()
        result = 31 * result + (layerBlock?.hashCode() ?: 0)
        result = 31 * result + (exportedBackdrop?.hashCode() ?: 0)
        result = 31 * result + (onDrawBehind?.hashCode() ?: 0)
        result = 31 * result + onDrawBackdrop.hashCode()
        result = 31 * result + (onDrawSurface?.hashCode() ?: 0)
        result = 31 * result + (onDrawFront?.hashCode() ?: 0)
        result = 31 * result + backdropScale.hashCode()
        return result
    }
}

private class DrawBackdropNode(
    var backdrop: Backdrop,
    var shapeProvider: ShapeProvider,
    var effects: BackdropEffectScope.() -> Unit,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var exportedBackdrop: LayerBackdrop?,
    var onDrawBehind: (DrawScope.() -> Unit)?,
    var onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    var onDrawSurface: (DrawScope.() -> Unit)?,
    var onDrawFront: (DrawScope.() -> Unit)?,
    var backdropScale: Float
) : LayoutModifierNode, DrawModifierNode, GlobalPositionAwareModifierNode, ObserverModifierNode, CompositionLocalConsumerModifierNode, Modifier.Node() {

    private val effectScope =
        object : BackdropEffectScopeImpl() {

            override val shape: Shape get() = shapeProvider.innerShape
        }

    private var graphicsLayer: GraphicsLayer? = null
    private var hasRecordedLayer = false

    private val layoutLayerBlock: GraphicsLayerScope.() -> Unit = {
        clip = true
        shape = shapeProvider.shape
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
    }

    private var layoutCoordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())

    // Where the coordinates last put this surface, so a callback that moves nothing writes nothing (see below).
    private var lastPositionInWindow = Offset.Unspecified
    private var lastSize = IntSize.Zero

    private var padding by mutableFloatStateOf(0f)

    private val recordBackdropBlock: (DrawScope.() -> Unit) = {
        val canvas = drawContext.canvas
        val padding = padding
        val scale = backdropScale

        if (padding != 0f) {
            canvas.translate(padding, padding)
        }
        // Vendored addition: draw the backdrop content downscaled so the recorded
        // layer (and therefore the RenderEffect chain) covers fewer pixels.
        if (scale != 1f) {
            canvas.scale(scale, scale)
        }
        onDrawBackdrop {
            // Sampled as if the screen were already at rest, while it is travelling — see [GlassSettle].
            val glassSettle = currentValueOf(LocalGlassSettle)
            val settle = if (glassSettle != null && (glassSettle.frozen || glassSettle.offset != Offset.Zero)) {
                glassSettle.restShift(layoutCoordinates) ?: glassSettle.offset
            } else {
                Offset.Zero
            }
            translate(settle.x, settle.y) {
                with(backdrop) {
                    drawBackdrop(
                        density = effectScope,
                        coordinates = layoutCoordinates,
                        layerBlock = layerBlock
                    )
                }
            }
        }
        if (scale != 1f) {
            canvas.scale(1f / scale, 1f / scale)
        }
        if (padding != 0f) {
            canvas.translate(-padding, -padding)
        }
    }

    private val drawBackdropLayer: DrawScope.() -> Unit = {
        val layer = graphicsLayer
        if (layer != null) {
            val padding = padding
            val scale = backdropScale
            val settle = currentValueOf(LocalGlassSettle)
            val frozen = settle?.frozen == true && hasRecordedLayer

            if (!frozen) {
                recordLayer(
                    this@DrawBackdropNode,
                    layer,
                    size = IntSize(
                        ((size.width * scale).toInt() + padding.toInt() * 2).coerceAtLeast(1),
                        ((size.height * scale).toInt() + padding.toInt() * 2).coerceAtLeast(1)
                    ),
                    block = recordBackdropBlock
                )
                hasRecordedLayer = true
            }

            layer.topLeft =
                if (padding != 0f) IntOffset(-padding.toInt(), -padding.toInt())
                else IntOffset.Zero
            if (scale != 1f) {
                // Vendored addition: stretch the low resolution layer back over the
                // full surface; the blur in the effect chain masks the upscaling.
                scale(1f / scale, pivot = Offset.Zero) {
                    drawLayer(layer)
                }
            } else {
                drawLayer(layer)
            }
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(IntOffset.Zero, layerBlock = layoutLayerBlock)
        }
    }

    override fun ContentDrawScope.draw() {
        if (effectScope.update(this, backdropScale)) {
            updateEffects()
        }

        onDrawBehind?.invoke(this)
        drawBackdropLayer()
        onDrawSurface?.invoke(this)
        drawContent()
        onDrawFront?.invoke(this)

        exportedBackdrop?.graphicsLayer?.let { layer ->
            recordLayer(this@DrawBackdropNode, layer) {
                onDrawBehind?.invoke(this)
                drawBackdropLayer()
                onDrawSurface?.invoke(this)
                onDrawFront?.invoke(this)
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            if (backdrop.isCoordinatesDependent) {
                // Held still while the whole screen travels: the picture it has is the one it will rest with,
                // so working it out again, every frame, would cost a great deal to change nothing.
                val settle = currentValueOf(LocalGlassSettle)
                val frozen = settle?.frozen == true
                if (!(frozen && layoutCoordinates != null)) {
                    val disableCache = settle != null && settle.isClosing
                    if (disableCache) {
                        layoutCoordinates = coordinates
                    } else {
                        // Only when the surface has really moved or changed size. The state never compares equal
                        // (the coordinates are the same object from call to call), so every write redraws this
                        // surface and sends its backdrop through the whole effect chain again — and Compose calls
                        // this after every layout pass that places the surface, moved or not.
                        val position = coordinates.positionInWindow()
                        if (layoutCoordinates == null || position != lastPositionInWindow || coordinates.size != lastSize) {
                            lastPositionInWindow = position
                            lastSize = coordinates.size
                            layoutCoordinates = coordinates
                        }
                    }
                }
            } else {
                if (layoutCoordinates != null) {
                    layoutCoordinates = null
                }
            }
            exportedBackdrop?.layerCoordinates = coordinates
        }
    }

    override fun onObservedReadsChanged() {
        invalidateDrawCache()
    }

    fun invalidateDrawCache() {
        observeEffects()
    }

    private fun observeEffects() {
        observeReads { updateEffects() }
    }

    private fun updateEffects() {
        if (!isRenderEffectSupported()) return

        effectScope.apply(effects)
        graphicsLayer?.renderEffect = effectScope.renderEffect
        padding = effectScope.padding
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer = graphicsContext.createGraphicsLayer()

        observeEffects()
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            graphicsLayer = null
        }

        hasRecordedLayer = false
        effectScope.reset()
        layoutCoordinates = null
        lastPositionInWindow = Offset.Unspecified
        lastSize = IntSize.Zero
        exportedBackdrop?.layerCoordinates = null
    }
}
