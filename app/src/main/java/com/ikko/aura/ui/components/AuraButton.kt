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

private const val PRESSED_SCALE = 1.05f

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
 * rim (see [playerRim]), and an inverted press — it pops forward towards the user from the exact
 * touch contact point and springs back with a lively bounce.
 */
@Composable
fun AuraButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Neutral,
    shape: Shape = TileShape,
    minHeight: Dp = 44.dp,
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

class AuraGroupPressState {
    val scale = Animatable(1f)
    val tiltY = Animatable(0f)
    val tiltX = Animatable(0f)
    val translationX = Animatable(0f)
    val translationY = Animatable(0f)
    var touchPivot by mutableStateOf(TransformOrigin.Center)
}

val LocalAuraGroupPressState = compositionLocalOf<AuraGroupPressState?> { null }

/**
 * Several icon buttons joined into one capsule of the round buttons' own glass — one fill, one rim, one light —
 * the way a toolbar groups related actions. The capsule dynamically expands and tilts towards the user on
 * the pressed side, with bounce physics and directional 3D relief.
 */
@Composable
fun AuraIconGroup(
    modifier: Modifier = Modifier,
    light: Float = 45f,
    content: @Composable RowScope.() -> Unit,
) {
    val groupState = remember { AuraGroupPressState() }

    CompositionLocalProvider(LocalAuraGroupPressState provides groupState) {
        Row(
            modifier = modifier
                .graphicsLayer {
                    scaleX = groupState.scale.value
                    scaleY = groupState.scale.value
                    rotationX = groupState.tiltX.value
                    rotationY = groupState.tiltY.value
                    translationX = groupState.translationX.value
                    translationY = groupState.translationY.value
                    cameraDistance = 12f
                    transformOrigin = groupState.touchPivot
                }
                .clip(CircleShape)
                .drawBehind { drawRect(NeutralFill) }
                .playerRim(CircleShape, ICON_RIM_STRENGTH, light),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** One button of an [AuraIconGroup]: expanding towards the user from the touch point with asymmetric emphasis. */
@Composable
fun AuraGroupIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    side: Float = 0f, // -1f = left, 0f = center, +1f = right
    content: @Composable () -> Unit,
) {
    val groupState = LocalAuraGroupPressState.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    var touchPivot by remember { mutableStateOf(TransformOrigin.Center) }
    var pressJob by remember { mutableStateOf<Job?>(null) }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier = modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    transformOrigin = touchPivot
                }
                .semantics { role = Role.Button }
                .pointerInput(onClick, side) {
                    detectTapGestures(
                        onPress = { offset ->
                            val pressStart = System.currentTimeMillis()
                            val w = this@pointerInput.size.width.toFloat()
                            val h = this@pointerInput.size.height.toFloat()
                            val normX = if (w > 0f) (offset.x / w).coerceIn(0f, 1f) else 0.5f
                            val normY = if (h > 0f) (offset.y / h).coerceIn(0f, 1f) else 0.5f
                            touchPivot = TransformOrigin(normX, normY)

                            pressJob?.cancel()
                            pressJob = scope.launch {
                                launch {
                                    scale.animateTo(
                                        targetValue = GROUP_PRESSED_SCALE,
                                        animationSpec = spring(
                                            dampingRatio = 0.82f,
                                            stiffness = 160f,
                                        ),
                                    )
                                }
                                groupState?.let { group ->
                                    val isRight = side > 0f
                                    val isLeft = side < 0f

                                    // When pressing right: anchor at left so expansion blooms towards the right.
                                    // When pressing left: anchor at right so expansion blooms towards the left.
                                    val targetPivot = if (isRight) TransformOrigin(0.20f, normY)
                                        else if (isLeft) TransformOrigin(0.80f, normY)
                                        else TransformOrigin.Center

                                    // 3D perspective tilt:
                                    // Right side rotates towards the viewer (negative rotationY)
                                    // Left side rotates towards the viewer (positive rotationY)
                                    val targetTiltY = if (isRight) -5.0f else if (isLeft) 5.0f else 0f
                                    val targetTiltX = -(normY - 0.5f) * 3.5f

                                    // Physical translation: the glass visibly springs outwards in the direction pressed
                                    val shiftXDist = 3.5f * density.density
                                    val targetShiftX = if (isRight) shiftXDist else if (isLeft) -shiftXDist else 0f
                                    val targetShiftY = (normY - 0.5f) * 1.5f * density.density

                                    group.touchPivot = targetPivot
                                    launch {
                                        group.scale.animateTo(
                                            targetValue = 1.05f,
                                            animationSpec = spring(
                                                dampingRatio = 0.82f,
                                                stiffness = 160f,
                                            ),
                                        )
                                    }
                                    launch {
                                        group.tiltY.animateTo(
                                            targetValue = targetTiltY,
                                            animationSpec = spring(
                                                dampingRatio = 0.82f,
                                                stiffness = 160f,
                                            ),
                                        )
                                    }
                                    launch {
                                        group.tiltX.animateTo(
                                            targetValue = targetTiltX,
                                            animationSpec = spring(
                                                dampingRatio = 0.82f,
                                                stiffness = 160f,
                                            ),
                                        )
                                    }
                                    launch {
                                        group.translationX.animateTo(
                                            targetValue = targetShiftX,
                                            animationSpec = spring(
                                                dampingRatio = 0.82f,
                                                stiffness = 160f,
                                            ),
                                        )
                                    }
                                    launch {
                                        group.translationY.animateTo(
                                            targetValue = targetShiftY,
                                            animationSpec = spring(
                                                dampingRatio = 0.82f,
                                                stiffness = 160f,
                                            ),
                                        )
                                    }
                                }
                            }
                            val released = tryAwaitRelease()
                            if (released) {
                                val elapsed = System.currentTimeMillis() - pressStart
                                pressJob?.cancel()
                                pressJob = scope.launch {
                                    val minHoldMs = 100L
                                    if (elapsed < minHoldMs) delay(minHoldMs - elapsed)
                                    launch {
                                        scale.animateTo(
                                            targetValue = 1f,
                                            animationSpec = spring(
                                                dampingRatio = 0.58f, // Apple .bouncy
                                                stiffness = 135f,
                                            ),
                                        )
                                    }
                                    groupState?.let { group ->
                                        launch {
                                            group.scale.animateTo(
                                                targetValue = 1f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.58f, // Apple .bouncy
                                                    stiffness = 135f,
                                                ),
                                            )
                                        }
                                        launch {
                                            group.tiltY.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.58f, // Apple .bouncy
                                                    stiffness = 135f,
                                                ),
                                            )
                                        }
                                        launch {
                                            group.tiltX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.58f, // Apple .bouncy
                                                    stiffness = 135f,
                                                ),
                                            )
                                        }
                                        launch {
                                            group.translationX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.58f, // Apple .bouncy
                                                    stiffness = 135f,
                                                ),
                                            )
                                        }
                                        launch {
                                            group.translationY.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.58f, // Apple .bouncy
                                                    stiffness = 135f,
                                                ),
                                            )
                                        }
                                    }
                                }
                            } else {
                                pressJob?.cancel()
                                pressJob = scope.launch {
                                    launch {
                                        scale.animateTo(
                                            targetValue = 1f,
                                            animationSpec = spring(
                                                dampingRatio = 0.82f,
                                                stiffness = 160f,
                                            ),
                                        )
                                    }
                                    groupState?.let { group ->
                                        launch {
                                            group.scale.animateTo(
                                                targetValue = 1f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.82f,
                                                    stiffness = 160f,
                                                ),
                                            )
                                        }
                                        launch {
                                            group.tiltY.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.82f,
                                                    stiffness = 160f,
                                                ),
                                            )
                                        }
                                        launch {
                                            group.tiltX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.82f,
                                                    stiffness = 160f,
                                                ),
                                            )
                                        }
                                        launch {
                                            group.translationX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.82f,
                                                    stiffness = 160f,
                                                ),
                                            )
                                        }
                                        launch {
                                            group.translationY.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.82f,
                                                    stiffness = 160f,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        onTap = { onClick() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** A glyph inside a group pops towards the user when pressed. */
private const val GROUP_PRESSED_SCALE = 1.055f

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
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    var touchPivot by remember { mutableStateOf(TransformOrigin.Center) }
    var pressJob by remember { mutableStateOf<Job?>(null) }

    val fill = if (tone == ButtonTone.Primary) PrimaryFill else NeutralFill
    val contentColor = if (tone == ButtonTone.Primary) PrimaryContent else Color.White

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                transformOrigin = touchPivot
            }
            .clip(shape)
            .drawBehind { drawRect(fill) }
            .playerRim(shape, rimStrength, lightDegrees)
            .semantics { role = Role.Button }
            .pointerInput(onClick) {
                detectTapGestures(
                    onPress = { offset ->
                        val pressStart = System.currentTimeMillis()
                        val w = this@pointerInput.size.width.toFloat()
                        val h = this@pointerInput.size.height.toFloat()
                        if (w > 0f && h > 0f) {
                            touchPivot = TransformOrigin(
                                (offset.x / w).coerceIn(0f, 1f),
                                (offset.y / h).coerceIn(0f, 1f),
                            )
                        }
                        pressJob?.cancel()
                        pressJob = scope.launch {
                            scale.animateTo(
                                targetValue = PRESSED_SCALE,
                                animationSpec = spring(
                                    dampingRatio = 0.82f, // Apple interactive soft cushion
                                    stiffness = 160f,
                                ),
                            )
                        }
                        val released = tryAwaitRelease()
                        if (released) {
                            val elapsed = System.currentTimeMillis() - pressStart
                            pressJob?.cancel()
                            pressJob = scope.launch {
                                val minHoldMs = 100L
                                if (elapsed < minHoldMs) delay(minHoldMs - elapsed)
                                scale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = 0.58f, // Apple .bouncy: soft organic overshoot
                                        stiffness = 135f,
                                    ),
                                )
                            }
                        } else {
                            pressJob?.cancel()
                            pressJob = scope.launch {
                                scale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = 0.82f,
                                        stiffness = 160f,
                                    ),
                                )
                            }
                        }
                    },
                    onTap = { onClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        content(contentColor)
    }
}
