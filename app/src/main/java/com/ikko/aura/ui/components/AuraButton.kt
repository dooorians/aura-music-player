package com.ikko.aura.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** [Primary] is the pale tile (the player's play button); [Neutral] is the dark glass one. */
enum class ButtonTone { Primary, Neutral }

/**
 * The player's own tile shape: its corners are 20dp on a 58dp-tall tile, a third of the height, and
 * a third of the shorter side is what this keeps at every size — a chip and a square icon button are
 * the same rounded rectangle as a transport tile, not a capsule.
 */
val TileShape = RoundedCornerShape(percent = 34)

/** Round icon buttons carry a softer edge than the transport tiles: a hint of relief, not a frame. */
private const val ICON_RIM_STRENGTH = 0.45f

/** The range the round buttons' light direction is spread over, in degrees: the player's own 45 sits in the middle. */
private const val LIGHT_MIN = 5L
private const val LIGHT_SPAN = 80L

/**
 * Dark glass: what the player's rewind and forward tiles do to the background behind them. Measured on
 * the device, a tile is 14% darker than the backdrop around it.
 */
private val NeutralFill = Color(0xFF080C1C).copy(alpha = 0.38f)

/** The player's play tile, without its colour: pale glass with a dark glyph. */
private val PrimaryFill = Color(0xFFEDEDF2).copy(alpha = 0.90f)
private val PrimaryContent = Color(0xFF23232B)

/**
 * A button in the player's own style: the transport tiles' rounded shape, their darkening and their
 * rim (see [playerRim]), and the press of iOS's interactive glass (see [GlassPressState]): it swells from its centre,
 * the glass lights up under the finger, and it springs back with one soft overshoot.
 */
@Composable
fun AuraButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Neutral,
    shape: Shape = TileShape,
    minHeight: Dp = 44.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
    tint: (() -> Color)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    TileSurface(onClick, modifier, tone, shape, tint = tint) { contentColor ->
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
    size: Dp = 44.dp,
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

/**
 * The capsule's own swell, shared with the icons inside it: pressing any one of them makes the whole capsule breathe
 * a little, as the iOS toolbars' groups do.
 */
val LocalAuraGroupPress = compositionLocalOf<GlassPressState?> { null }

/**
 * Several icon buttons joined into one capsule of the round buttons' own glass — one fill, one rim, one light —
 * the way a toolbar groups related actions. Pressing one of them swells the capsule slightly and springs it back.
 */
@Composable
fun AuraIconGroup(
    modifier: Modifier = Modifier,
    light: Float = 45f,
    content: @Composable RowScope.() -> Unit,
) {
    val press = rememberGlassPress(fixedScale = CAPSULE_PRESSED_SCALE)

    CompositionLocalProvider(LocalAuraGroupPress provides press) {
        Row(
            modifier = modifier
                .glassPressLayer(press)
                .clip(CircleShape)
                .drawBehind { drawRect(NeutralFill) }
                .glassEdge(CircleShape)
                .glassPressGlow(press, strength = GLASS_PRESS_GLOW * 0.4f),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * One button of an [AuraIconGroup]. Its glyph swells from its centre and a bubble of lit glass appears under the
 * finger, while the capsule around it breathes; on release both spring back with one soft overshoot.
 * [side] is kept for the callers that still pass it: nothing tilts towards a side any more.
 */
@Composable
fun AuraGroupIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    @Suppress("UNUSED_PARAMETER") side: Float = 0f,
    content: @Composable () -> Unit,
) {
    val group = LocalAuraGroupPress.current
    val press = rememberGlassPress(fixedScale = GLYPH_PRESSED_SCALE)

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier = modifier
                .size(size)
                .semantics { role = Role.Button }
                .glassPressInput(press, onClick, alsoSwell = group),
            contentAlignment = Alignment.Center,
        ) {
            // The lit glass under the finger, kept inside the capsule so its clip never cuts it.
            Box(
                Modifier.matchParentSize().drawBehind {
                    val glow = press.glow.value
                    if (glow > 0.001f) {
                        drawCircle(
                            color = Color.White.copy(alpha = GLASS_PRESS_GLOW * 1.3f * glow),
                            radius = this.size.minDimension / 2f * (0.86f + 0.14f * glow),
                        )
                    }
                },
            )
            Box(Modifier.glassPressLayer(press), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/** How much of the album's colour a tinted button takes on, over its dark glass. */
private const val TINT_ALPHA = 0.11f

/** A glyph inside a group swells by this much when pressed. */
private const val GLYPH_PRESSED_SCALE = 1.12f

/** The whole capsule of a group swells by this much while one of its icons is pressed. */
private const val CAPSULE_PRESSED_SCALE = 1.035f

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
    tint: (() -> Color)? = null,
    content: @Composable (contentColor: Color) -> Unit,
) {
    val press = rememberGlassPress()

    val fill = if (tone == ButtonTone.Primary) PrimaryFill else NeutralFill
    // The neutral buttons take the cover's accent, like the player's own controls; the primary tone keeps its dark glyph.
    val accent = rememberPlayerAccent()
    val contentColor = if (tone == ButtonTone.Primary) PrimaryContent else accent

    Box(
        modifier = modifier
            // The swell is outside the clip, so the clip, the fill and the rim all grow together.
            .glassPressLayer(press)
            .clip(shape)
            .drawBehind {
                drawRect(fill)
                if (tint != null) drawRect(tint().copy(alpha = TINT_ALPHA))
            }
            .glassEdge(shape)
            .glassPressGlow(press)
            .semantics { role = Role.Button }
            .glassPressInput(press, onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(contentColor)
    }
}
