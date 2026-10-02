package com.ikko.aura.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/*
 * The press of an interactive glass button, as iOS 26 does it — measured on a 60 fps screen recording of the
 * Control Center's tiles, by the width of the white tile in every frame:
 *
 *  - the touch grows the tile from its centre to +17..19% (225 px to 263..267 px) in five frames, 85 ms: a firm,
 *    quick spring with next to no overshoot. A tap is held about 100 ms at the top before it is let go;
 *  - the release is a bouncy spring: from +19% it crosses its rest size, goes 5% under it after 0.18 s (225 px to
 *    213 px), comes back 1% over and is still by 0.37 s. That is a damping ratio of about 0.4 and a natural
 *    frequency of about 19 rad/s (stiffness 350);
 *  - while it is held the glass lights up, and there is no tilt: nothing rotates, the button only swells and settles.
 *
 * Bigger buttons do not swell by a percentage — a wide chip that grew 18% would be 25 dp wider — so the growth is
 * capped at 14 dp, and 18% is the most a small round one grows.
 */

/** How much a pressed button grows, in dp, whatever its size. */
private const val GROW_DP = 14f
private const val MIN_PRESSED_SCALE = 1.035f
private const val MAX_PRESSED_SCALE = 1.18f

/** The glass drifts a little towards the finger: this share of the touch's distance from the centre. */
private const val PULL = 0.10f
private const val MAX_PULL_DP = 3f

/** A tap shorter than this still shows the whole swell: it is held for this long before it is let go. */
private const val MIN_HOLD_MS = 100L

/** How bright the glass gets at full press, as the alpha of a white wash over it. */
const val GLASS_PRESS_GLOW = 0.16f

private val PressSpring = spring<Float>(dampingRatio = 0.90f, stiffness = 3000f)
private val ReleaseSpring = spring<Float>(dampingRatio = 0.40f, stiffness = 350f)
private val CancelSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 800f)

/**
 * Where a glass button is in its press: [scale] and the drift towards the finger move the button, [glow] is how
 * lit the glass is. [fixedScale] pins the pressed size, for the buttons whose growth is not a function of their
 * own size (the capsule of a group, the glyph inside it).
 */
@Stable
class GlassPressState(
    private val scope: CoroutineScope,
    private val density: Density,
    private val fixedScale: Float? = null,
) {
    val scale = Animatable(1f)
    val glow = Animatable(0f)
    val shiftX = Animatable(0f)
    val shiftY = Animatable(0f)
    private var job: Job? = null

    fun press(touch: Offset, size: IntSize) {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val target = fixedScale
            ?: (1f + with(density) { GROW_DP.dp.toPx() } / max(w, h)).coerceIn(MIN_PRESSED_SCALE, MAX_PRESSED_SCALE)
        val maxPull = with(density) { MAX_PULL_DP.dp.toPx() }
        val dx = ((touch.x - w / 2f) * PULL).coerceIn(-maxPull, maxPull)
        val dy = ((touch.y - h / 2f) * PULL).coerceIn(-maxPull, maxPull)
        job?.cancel()
        job = scope.launch {
            launch { scale.animateTo(target, PressSpring) }
            launch { glow.animateTo(1f, tween(120, easing = FastOutSlowInEasing)) }
            launch { shiftX.animateTo(dx, PressSpring) }
            launch { shiftY.animateTo(dy, PressSpring) }
        }
    }

    /** [tapped]: the finger lifted on the button (the bouncy return); otherwise the touch was cancelled (a calm one). */
    fun release(heldMs: Long, tapped: Boolean) {
        val pressing = job
        job = scope.launch {
            // The swell keeps running while a very short tap waits out its minimum, so it is seen to the end.
            if (tapped && heldMs < MIN_HOLD_MS) delay(MIN_HOLD_MS - heldMs)
            pressing?.cancel()
            val spring = if (tapped) ReleaseSpring else CancelSpring
            launch { scale.animateTo(1f, spring) }
            launch { glow.animateTo(0f, tween(260, easing = FastOutSlowInEasing)) }
            launch { shiftX.animateTo(0f, spring) }
            launch { shiftY.animateTo(0f, spring) }
        }
    }
}

@Composable
fun rememberGlassPress(fixedScale: Float? = null): GlassPressState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(scope, density.density, fixedScale) { GlassPressState(scope, density, fixedScale) }
}

/** Swells the button: the scale and the drift, from its centre. Place it before the clip, so the clip swells with it. */
fun Modifier.glassPressLayer(state: GlassPressState): Modifier = graphicsLayer {
    scaleX = state.scale.value
    scaleY = state.scale.value
    translationX = state.shiftX.value
    translationY = state.shiftY.value
}

/** The glass lighting up under the finger: a white wash that follows [GlassPressState.glow]. Place it after the fill. */
fun Modifier.glassPressGlow(state: GlassPressState, strength: Float = GLASS_PRESS_GLOW): Modifier = drawBehind {
    val glow = state.glow.value
    if (glow > 0.001f) drawRect(Color.White.copy(alpha = strength * glow))
}

/**
 * The touch itself: down starts the swell, up lets it go and calls [onClick], a cancelled touch lets it go quietly.
 * [alsoSwell] is a parent that swells with its child — the capsule of a group — and follows the same touch.
 */
fun Modifier.glassPressInput(
    state: GlassPressState,
    onClick: () -> Unit,
    alsoSwell: GlassPressState? = null,
): Modifier = pointerInput(state, onClick, alsoSwell) {
    detectTapGestures(
        onPress = { offset ->
            val start = System.currentTimeMillis()
            state.press(offset, this@pointerInput.size)
            // The parent's size is its own; its swell is fixed and takes no drift, so the child's centre stands in.
            alsoSwell?.press(Offset(this@pointerInput.size.width / 2f, this@pointerInput.size.height / 2f), this@pointerInput.size)
            val released = tryAwaitRelease()
            val held = System.currentTimeMillis() - start
            state.release(held, tapped = released)
            alsoSwell?.release(held, tapped = released)
        },
        onTap = { onClick() },
    )
}
