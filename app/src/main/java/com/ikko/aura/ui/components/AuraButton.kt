package com.ikko.aura.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** [Primary] is the pale tile (the player's play button); [Neutral] is the dark glass one. */
enum class ButtonTone { Primary, Neutral }

/**
 * The player's own tile shape: its corners are 20dp on a 58dp-tall tile, a third of the height, and
 * a third of the shorter side is what this keeps at every size — a chip and a square icon button are
 * the same rounded rectangle as a transport tile, not a capsule.
 */
val TileShape = RoundedCornerShape(percent = 34)

private const val PRESSED_SCALE = 0.96f

/** Round icon buttons carry a softer edge than the transport tiles: a hint of relief, not a frame. */
private const val ICON_RIM_STRENGTH = 0.45f

/** The range the round buttons' light direction is spread over, in degrees: the player's own 45 sits in the middle. */
private const val LIGHT_MIN = 5L
private const val LIGHT_SPAN = 80L

/**
 * Dark glass: what the player's rewind and forward tiles do to the background behind them. Measured on
 * the device, a tile is 14% darker than the backdrop around it.
 */
private val NeutralFill = Color.Black.copy(alpha = 0.14f)

/** The player's play tile, without its colour: pale glass with a dark glyph. */
private val PrimaryFill = Color(0xFFEDEDF2).copy(alpha = 0.90f)
private val PrimaryContent = Color(0xFF23232B)

/**
 * A button in the player's own style: the transport tiles' rounded shape, their darkening and their
 * rim (see [playerRim]), and the same soft press — it sinks under the finger and springs back with a
 * little overshoot. No colour of its own: the buttons take nothing from the page, so they cannot
 * clash with it.
 */
@Composable
fun AuraButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Neutral,
    shape: Shape = TileShape,
    minHeight: Dp = 48.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
    content: @Composable RowScope.() -> Unit,
) {
    TileSurface(onClick, modifier, tone, shape) { contentColor ->
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                modifier = Modifier.defaultMinSize(minHeight = minHeight).padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                content = content,
            )
        }
    }
}

/** A square tile holding a single glyph. */
@Composable
fun AuraIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Neutral,
    size: Dp = 46.dp,
    light: Float? = null,
    content: @Composable () -> Unit,
) {
    // Where the edge catches the light: given, or drawn from where this button sits in the code, so it is
    // different from its neighbours and the same every time it is shown — never chosen at random.
    val hash = currentCompositeKeyHash
    val lightDegrees = light ?: (LIGHT_MIN + (((hash.toLong() * 2654435761L) ushr 13) and 0xFFFFL) % LIGHT_SPAN).toFloat()
    TileSurface(onClick, modifier, tone, CircleShape, rimStrength = ICON_RIM_STRENGTH, lightDegrees = lightDegrees) { contentColor ->
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/** A selectable tile: the pale one when chosen, the dark one otherwise. */
@Composable
fun AuraChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    AuraButton(
        onClick = onClick,
        modifier = modifier,
        tone = if (selected) ButtonTone.Primary else ButtonTone.Neutral,
        minHeight = 44.dp,
        contentPadding = PaddingValues(horizontal = if (compact) 8.dp else 20.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), maxLines = 1)
    }
}

@Composable
private fun TileSurface(
    onClick: () -> Unit,
    modifier: Modifier,
    tone: ButtonTone,
    shape: Shape,
    rimStrength: Float = 1f,
    lightDegrees: Float = 45f,
    content: @Composable (contentColor: Color) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // The transport tiles' own press, spring and all — deliberately soft: at a stiffer spring the
    // sink-and-return is over before the finger registers it, which reads as a flicker.
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessVeryLow, visibilityThreshold = 0.0004f),
        label = "tile-press-depth",
    )
    val fill = if (tone == ButtonTone.Primary) PrimaryFill else NeutralFill
    val contentColor = if (tone == ButtonTone.Primary) PrimaryContent else Color.White

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .drawBehind { drawRect(fill) }
            .playerRim(shape, rimStrength, lightDegrees)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(contentColor)
    }
}
