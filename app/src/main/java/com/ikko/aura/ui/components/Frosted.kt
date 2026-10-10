package com.ikko.aura.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import com.ikko.aura.ui.components.backdrop.Backdrop
import com.ikko.aura.ui.components.backdrop.drawBackdrop
import com.ikko.aura.ui.components.backdrop.effects.blur
import com.ikko.aura.ui.components.backdrop.effects.colorControls
import com.ikko.aura.ui.theme.LocalAuraBatterySaver

/** What the page's frosted buttons blur: the F background alone (the buttons sit on it, never over the page's rows). */
val LocalImmersiveBackdrop = staticCompositionLocalOf<Backdrop?> { null }

private const val SCALE = 0.33f

/**
 * The design's frosted button: white from [top] to [bottom] over a 26-px blur of what is behind it, saturated by 1.6, a
 * 1.5-px rim at [rim], and a 2-px light line along the top edge at the same strength. [shape] a pill or a circle.
 */
@Composable
fun Modifier.frosted(
    shape: CornerBasedShape,
    top: Float = 0.26f,
    bottom: Float = 0.12f,
    rim: Float = 0.28f,
): Modifier {
    val backdrop = LocalImmersiveBackdrop.current
    val unit = designUnit()
    val density = LocalDensity.current
    val fill = Brush.verticalGradient(listOf(Color.White.copy(alpha = top), Color.White.copy(alpha = bottom)))
    val rimWidth = with(density) { (unit * 1.5f).toPx() }
    val lineWidth = with(density) { (unit * 2f).toPx() }
    if (backdrop == null || LocalAuraBatterySaver.current) {
        return this
            .background(fill, shape)
            .border(unit * 1.5f, Color.White.copy(alpha = rim), shape)
    }
    val blurPx = with(density) { (unit * 26f).toPx() } * SCALE
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1.6f)
            blur(blurPx)
        },
        highlight = null,
        shadow = null,
        onDrawSurface = {
            drawRect(fill)
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, Color.White.copy(alpha = rim), style = Stroke(width = rimWidth * 2f))
            clipRect(bottom = lineWidth * 2f) {
                drawOutline(outline, Color.White.copy(alpha = rim), style = Stroke(width = lineWidth * 2f))
            }
        },
        backdropScale = SCALE,
    )
}

/** A frosted round button of [size], its content centred. */
@Composable
fun FrostedCircleButton(
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    /** A dark veil over the glass, for a button that must stand out of a bright, same-hued background. */
    scrim: Float = 0f,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .frosted(CircleShape, rim = if (scrim > 0f) 0.5f else 0.28f)
            .then(if (scrim > 0f) Modifier.background(Color.Black.copy(alpha = scrim)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { content() }
}
