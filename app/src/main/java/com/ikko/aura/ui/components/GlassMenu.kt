package com.ikko.aura.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LibraryTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * A menu that grows out of the glass button that opened it, as iOS 26's do — read off a screen recording of the
 * Control Center, frame by frame:
 *
 *  - the button swells under the finger (see GlassPress.kt), and as the finger lifts it empties: the button
 *    fades out and a bead of glass is left where it was;
 *  - the bead stretches into the menu in about a third of a second, one spring with a little overshoot. It is
 *    the same piece of glass the whole way, never a button replaced by a panel: its corners go from a circle to
 *    the menu's, and while it travels the lens bends what is behind it hard — a bulging droplet — and lets go as
 *    it settles into flat glass;
 *  - the menu's rows are not scaled with it: they are already at their final size, and the moving edge of the
 *    glass reveals them. They come out of a blur (soft, then sharp) and fade in as they are uncovered;
 *  - closing is the same thing backwards: the rows blur out, the glass pulls back into a bead, and the button
 *    comes back out of it.
 */

private const val MAX_PROGRESS = 1.25f
private val OpenSpring = spring<Float>(dampingRatio = 0.68f, stiffness = 300f)
private val CloseSpring = spring<Float>(dampingRatio = 0.92f, stiffness = 450f)

/** How hard the lens bends the backdrop at the very start of the morph, as a multiple of the resting glass. */
private const val EXTRA_REFRACTION = 1.8f

/** Where the rows start to appear and how much of the travel it takes for them to be fully there. */
private const val REVEAL_START = 0.2f
private const val REVEAL_SPAN = 0.55f

/** The rows' blur at the instant they start to appear. */
private val REVEAL_BLUR = 14.dp

/**
 * One menu's state: how far the glass has travelled from the button to the menu ([progress], 0 to 1 and a little
 * past it while the spring overshoots), where the button is, and whether the menu is open.
 */
@Stable
class GlassMenuState internal constructor(private val scope: CoroutineScope) {
    val progress = Animatable(0f).apply { updateBounds(0f, MAX_PROGRESS) }

    /** The button's rectangle in the window, kept up to date by [glassMenuAnchor]. */
    var anchor: Rect by mutableStateOf(Rect.Zero)
        internal set

    /** True from the moment the menu opens until the glass has gone back into its button. */
    var visible: Boolean by mutableStateOf(false)
        private set

    /** The menu's target: open, or on its way to being closed. */
    var isOpen: Boolean by mutableStateOf(false)
        private set

    /** The button empties as the glass leaves it and fills again as the glass comes back. */
    val buttonAlpha: Float
        get() {
            val t = (progress.value / 0.3f).coerceIn(0f, 1f)
            return 1f - t * t * (3f - 2f * t)
        }

    fun open() {
        if (isOpen || anchor == Rect.Zero) return
        isOpen = true
        visible = true
        scope.launch { progress.animateTo(1f, OpenSpring) }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        scope.launch {
            progress.animateTo(0f, CloseSpring)
            // Not if it was opened again in the meantime: that animation is the one that counts.
            if (!isOpen) visible = false
        }
    }
}

@Composable
fun rememberGlassMenuState(): GlassMenuState {
    val scope = rememberCoroutineScope()
    return remember(scope) { GlassMenuState(scope) }
}

/**
 * Marks the button a [GlassMenu] grows out of: it tells the menu where the button is, and it lets the button
 * empty while the menu is out, since the glass is then what was the button.
 */
fun Modifier.glassMenuAnchor(state: GlassMenuState): Modifier = this
    .onGloballyPositioned {
        val bounds = it.boundsInWindow()
        if (bounds != state.anchor) state.anchor = bounds
    }

/**
 * What the rows are cut to while the glass travels: the glass's own outline, expressed in the rows' box. That box is
 * as wide as the menu and as tall as its rows, and the glass grows out of its right edge and its top, from the
 * button's size to the whole box — so at any [progress] it is a rounded rectangle of that size, pinned to the
 * top right. A data class, so that the layer sees a new shape (and recuts its clip) whenever the progress moves.
 */
private data class MorphClip(
    val buttonWidth: Float,
    val buttonHeight: Float,
    val progress: Float,
    val corner: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = lerp(buttonWidth, size.width, progress).coerceAtLeast(1f)
        val h = lerp(buttonHeight, size.height, progress).coerceAtLeast(1f)
        val r = lerp(buttonHeight / 2f, corner, progress).coerceIn(0f, minOf(w, h) / 2f)
        return Outline.Rounded(
            RoundRect(left = size.width - w, top = 0f, right = size.width, bottom = h, cornerRadius = CornerRadius(r)),
        )
    }
}

/**
 * The menu itself. Lay it over the whole screen, above the content: it draws nothing while it is closed. A touch
 * anywhere outside the menu closes it, and so does Back. [content] holds the rows — see [GlassMenuItem] — and the
 * menu is [width] wide and as tall as they are, its right edge on the button's.
 */
@Composable
fun GlassMenu(
    state: GlassMenuState,
    modifier: Modifier = Modifier,
    width: Dp = 240.dp,
    cornerRadius: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!state.visible) return
    BackHandler(enabled = state.isOpen) { state.close() }

    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }
    val cornerPx = with(density) { cornerRadius.toPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }
    val blurPx = with(density) { REVEAL_BLUR.toPx() }
    var origin by remember { mutableStateOf(Offset.Zero) }

    // The glass's corners follow the travel: a circle at the button, the menu's own radius when it arrives.
    val glassShape = remember(state, cornerPx) {
        {
            val buttonRadius = state.anchor.height / 2f
            RoundedCornerShape(lerp(buttonRadius, cornerPx, state.progress.value).coerceAtLeast(0f))
        }
    }
    val refraction = remember(state) {
        { 1f + EXTRA_REFRACTION * (1f - state.progress.value.coerceIn(0f, 1f)) }
    }

    Layout(
        content = {
            // Its own node, with no content: the glass redraws when the surface moves, never because of a row.
            Spacer(Modifier.graphicsLayer { }.liquidGlassMorphing(shape = glassShape, refraction = refraction, tintAlpha = 0.72f))
            // The rows, at their final size. A touch on the panel that no row takes is not a touch outside it.
            Column(Modifier.padding(vertical = 6.dp).pointerInput(Unit) { detectTapGestures { } }) { content() }
        },
        modifier = modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.22f * state.progress.value.coerceIn(0f, 1f))) }
            .onGloballyPositioned {
                val position = it.positionInWindow()
                if (position != origin) origin = position
            }
            .pointerInput(state) { detectTapGestures { state.close() } },
    ) { measurables, constraints ->
        val glass = measurables[0]
        val body = measurables[1]
        val p = state.progress.value

        val button = state.anchor.translate(-origin.x, -origin.y)
        val bodyPlaceable = body.measure(Constraints.fixedWidth(widthPx))
        val menuHeight = bodyPlaceable.height

        // Where the menu ends up: its right edge on the button's, its top on the button's, kept clear of the screen's edges.
        val finalLeft = (button.right.roundToInt() - widthPx).coerceAtLeast(marginPx)
        val finalTop = button.top.roundToInt()
            .coerceAtMost(constraints.maxHeight - menuHeight - marginPx)
            .coerceAtLeast(marginPx)

        val left = lerp(button.left, finalLeft.toFloat(), p)
        val top = lerp(button.top, finalTop.toFloat(), p)
        val right = lerp(button.right, (finalLeft + widthPx).toFloat(), p)
        val bottom = lerp(button.bottom, (finalTop + menuHeight).toFloat(), p)
        val w = (right - left).roundToInt().coerceAtLeast(1)
        val h = (bottom - top).roundToInt().coerceAtLeast(1)

        val glassPlaceable = glass.measure(Constraints.fixed(w, h))
        layout(constraints.maxWidth, constraints.maxHeight) {
            glassPlaceable.place(left.roundToInt(), top.roundToInt())
            // Pinned to the glass's right edge, whatever the glass's width: the glass uncovers the rows, it does
            // not squeeze them. They come out of a blur and fade in as they are uncovered.
            bodyPlaceable.placeWithLayer(right.roundToInt() - widthPx, top.roundToInt()) {
                val q = state.progress.value
                clip = true
                this.shape = MorphClip(button.width, button.height, q, cornerPx)
                val reveal = ((q - REVEAL_START) / REVEAL_SPAN).coerceIn(0f, 1f)
                val eased = reveal * reveal * (3f - 2f * reveal)
                alpha = eased
                val scale = 0.94f + 0.06f * eased
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(1f, 0f)
                val radius = (1f - eased) * blurPx
                renderEffect = if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
            }
        }
    }
}

/**
 * A row of a [GlassMenu]. The one marked [selected] carries a check; a finger on a row lights a pill of glass under
 * it, and lifting it runs [onClick].
 */
@Composable
fun GlassMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val accent = com.ikko.aura.ui.components.rememberPlayerAccent()
    // As drawn: the chosen row is on a faint lit bed, in the accent and heavier, with its check at the end; the others
    // are plain, and a finger lights the row.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp)
            .clip(GlassMenuItemShape)
            .background(
                when {
                    pressed -> Color.White.copy(alpha = 0.16f)
                    selected -> Color.White.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .height(42.dp)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.ExtraBold else androidx.compose.ui.text.font.FontWeight.Bold,
            color = if (selected) accent else Color.White,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (selected) {
            Icon(
                CupertinoIcons.Check,
                contentDescription = "Tri actuel",
                modifier = Modifier.size(18.dp),
                tint = accent,
            )
        }
    }
}

private val GlassMenuItemShape = RoundedCornerShape(18.dp)
