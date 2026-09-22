package com.ikko.aura.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Every constant of the navigation motion in one place, so it can be tuned without hunting.
 * Lengths are in dp, times in seconds unless the name says milliseconds.
 */
object PageMotionSpec {
    // --- Pages ---
    /** The settle after a release. Low bounce: one overshoot of about six dp, no more. */
    const val PAGE_DAMPING = 0.75f
    const val PAGE_STIFFNESS = 140f

    /** The target page is the nearest to where the page would be this long after release. */
    const val FLING_LOOKAHEAD_S = 0.15f

    /** How close to its page, in pages, the settle runs before it is finished: well under a pixel. */
    const val PAGE_REST_THRESHOLD = 0.0002f

    /** How far past either end the pages may be pulled, in pages, and how much of the finger counts there. */
    const val EDGE_OVERSHOOT_PAGES = 0.06f
    const val EDGE_RESISTANCE = 0.35f

    // --- Followers: how far behind the page an element trails ---
    const val TRAIL_TITLE_S = 0.015f
    const val TRAIL_COLUMN_BASE_S = 0.020f
    const val TRAIL_COLUMN_STEP_S = 0.018f

    // --- Tab capsule ---
    const val CAPSULE_HEIGHT_DP = 45f

    /** The touch zone is taller than the drawing, so a thumb does not have to be exact. */
    const val CAPSULE_TOUCH_HEIGHT_DP = 48f
    const val CAPSULE_GAP_TO_PLAYER_DP = 7f
    const val CAPSULE_SHOW_DAMPING = 0.5f
    const val CAPSULE_SHOW_STIFFNESS = 200f
    const val CAPSULE_HIDE_MS = 450
    const val CAPSULE_LEAVE_MS = 90
    val CapsuleHideEasing = CubicBezierEasing(0.36f, 0f, 0.66f, -0.56f)
    const val CAPSULE_RISE_DP = 42f
    const val CAPSULE_SCALE_FROM = 0.88f
    const val CAPSULE_STRETCH_X = 0.03f
    const val CAPSULE_STRETCH_Y = 0.05f
    const val CAPSULE_STRETCH_FULL_AT = 4f
    const val CAPSULE_INTERACTIVE_ABOVE = 0.05f
    const val CAPSULE_APPEARS_AT_PAGE = 0.9f
    const val SCROLL_DOWN_HIDES_DP = 20f
    const val SCROLL_UP_SHOWS_DP = 10f
    const val SHEEN_MS = 900

    // --- Mini player ---
    const val PLAYER_STRETCH_X = 0.012f
    const val PLAYER_STRETCH_Y = 0.010f
    const val PLAYER_STRETCH_FULL_AT_PAGES_S = 3f

    // --- List fade (top only) ---
    const val FADE_TOP_LENGTH_DP = 77f
    const val FADE_TOP_AFTER_SCROLL_DP = 20f
}

/** The page position, shared with whatever should react to it. */
val LocalPageMotion = staticCompositionLocalOf<PageMotionState?> { null }

/**
 * The position of the top-level pages as a single number: 0 is the first, 1 the second, 2 the third,
 * fractions between while a finger drags or a spring settles, and a little past either end while the
 * pages overshoot.
 *
 * It is a plain float state read only inside graphics-layer and draw lambdas, so a moving page costs
 * no recomposition. [velocity] is in pages per second and falls to zero at rest.
 */
@Stable
class PageMotionState(val pageCount: Int, val reduceMotion: Boolean) {
    private val position: MutableFloatState = mutableFloatStateOf(0f)
    val p: Float get() = position.floatValue

    private val speed: MutableFloatState = mutableFloatStateOf(0f)
    val velocity: Float get() = speed.floatValue

    var widthPx: Float = 1f
    var dragging by mutableStateOf(false)
        internal set

    val inTransit: Boolean get() = dragging || abs(p - p.roundToInt()) > 0.001f

    private var settleJob: Job? = null

    fun stop() {
        settleJob?.cancel()
        settleJob = null
    }

    fun dragBy(pages: Float) {
        stop()
        dragging = true
        val last = (pageCount - 1).toFloat()
        var next = p + pages
        val beyondStart = min(next, 0f)
        val beyondEnd = max(next - last, 0f)
        if (beyondStart < 0f || beyondEnd > 0f) {
            // Resistance past the ends: the pages give a little, then hold.
            val over = if (beyondStart < 0f) beyondStart else beyondEnd
            val resisted = (over * PageMotionSpec.EDGE_RESISTANCE)
                .coerceIn(-PageMotionSpec.EDGE_OVERSHOOT_PAGES, PageMotionSpec.EDGE_OVERSHOOT_PAGES)
            next = if (beyondStart < 0f) resisted else last + resisted
        }
        position.floatValue = next
    }

    /** Lets go: settles on the nearest page, decided by where the pages are headed. [pagesPerSecond] is the drag's velocity in p. */
    fun release(scope: kotlinx.coroutines.CoroutineScope, pagesPerSecond: Float) {
        dragging = false
        val target = (p + pagesPerSecond * PageMotionSpec.FLING_LOOKAHEAD_S)
            .roundToInt().coerceIn(0, pageCount - 1)
        goTo(scope, target, pagesPerSecond)
    }

    fun goTo(scope: kotlinx.coroutines.CoroutineScope, page: Int, initialVelocity: Float = 0f) {
        stop()
        settleJob = scope.launch {
            if (reduceMotion) {
                position.floatValue = page.toFloat()
                speed.floatValue = 0f
            } else {
                animate(
                    initialValue = p,
                    targetValue = page.toFloat(),
                    initialVelocity = initialVelocity,
                    animationSpec = spring(
                        PageMotionSpec.PAGE_DAMPING,
                        PageMotionSpec.PAGE_STIFFNESS,
                        // The default threshold is 1% of a page, about 4 dp: the spring was declared done
                        // that far from its target and the page snapped the rest — the abrupt stop at the
                        // end of every bounce. This lets it run down to a fraction of a pixel.
                        PageMotionSpec.PAGE_REST_THRESHOLD,
                    ),
                ) { value, _ -> position.floatValue = value }
            }
        }
    }

    internal fun setSpeed(v: Float) {
        speed.floatValue = v
    }
}

@Composable
fun rememberPageMotion(pageCount: Int): PageMotionState {
    val context = LocalContext.current
    val reduce = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val state = remember { PageMotionState(pageCount, reduce) }
    // A frame loop, alive only while the pages are moving, that turns position into velocity.
    LaunchedEffect(state) {
        var last = state.p
        var lastNanos = 0L
        snapshotFlow { state.p }.collectLatest {
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.1f)
                lastNanos = now
                val instant = if (dt > 0f) (state.p - last) / dt else 0f
                last = state.p
                val smoothed = state.velocity * 0.6f + instant * 0.4f
                state.setSpeed(if (abs(smoothed) < 0.02f) 0f else smoothed)
                if (state.velocity == 0f && instant == 0f) break
            }
            lastNanos = 0L
        }
    }
    return state
}

/**
 * The pages, side by side, sliding with the finger. No card, no scale, no dimming: each page is a
 * full-screen layer translated by its distance from [state]'s position, so the pages stay exactly
 * under the finger and can be pulled a little past either end and spring back.
 *
 * Horizontal drags reach it two ways: directly, where nothing scrolls, and as the scroll a row of
 * covers could not use once it reached its own edge — the hand-over a pager does, done by hand.
 */
@Composable
fun PagedContainer(
    state: PageMotionState,
    modifier: Modifier = Modifier,
    pages: List<@Composable () -> Unit>,
) {
    val scope = rememberCoroutineScope()
    val connection = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.x != 0f && state.inTransit) {
                    state.dragBy(-available.x / state.widthPx)
                    return Offset(available.x, 0f)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.x != 0f) {
                    state.dragBy(-available.x / state.widthPx)
                    return Offset(available.x, 0f)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (state.inTransit) {
                    state.release(scope, -available.x / state.widthPx)
                    return Velocity(available.x, 0f)
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (state.dragging) {
                    state.release(scope, -available.x / state.widthPx)
                    return Velocity(available.x, 0f)
                }
                return Velocity.Zero
            }
        }
    }
    val draggable = rememberDraggableState { delta -> state.dragBy(-delta / state.widthPx) }

    androidx.compose.runtime.CompositionLocalProvider(LocalPageMotion provides state) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { state.widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .nestedScroll(connection)
                .draggable(
                    state = draggable,
                    orientation = Orientation.Horizontal,
                    onDragStarted = { state.stop(); state.dragging = true },
                    onDragStopped = { velocity -> state.release(scope, -velocity / state.widthPx) },
                ),
        ) {
            pages.forEachIndexed { index, content ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = (index - state.p) * state.widthPx },
                ) { content() }
            }
        }
    }
}

/**
 * How far behind the page an element trails, as a position: a critically damped follower of the page
 * position with a time constant of [delaySec]. The offset to apply is `(page position - this) * width`
 * — zero at rest, largest at the page's peak speed, which is what makes the content feel liquid.
 */
@Composable
fun rememberTrail(motion: PageMotionState?, delaySec: Float): State<Float> {
    val x = remember { mutableFloatStateOf(motion?.p ?: 0f) }
    LaunchedEffect(motion, delaySec) {
        if (motion == null) return@LaunchedEffect
        var v = 0f
        var lastNanos = 0L
        val w = 2f / delaySec
        snapshotFlow { motion.p }.collectLatest { target ->
            while (abs(target - x.floatValue) > 1e-4f || abs(v) > 1e-3f) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                val dt = if (lastNanos == 0L) 0.011f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.05f)
                lastNanos = now
                // Exact step of a critically damped spring toward a fixed target: stable at any dt.
                val y = x.floatValue - target
                val decay = exp(-w * dt)
                val c = v + w * y
                x.floatValue = target + (y + c * dt) * decay
                v = (v - c * w * dt) * decay
            }
            lastNanos = 0L
        }
    }
    return x
}

/** The soft diagonal light that crosses a glass surface while things move: 105 degrees, white at 20% in the middle. */
fun Modifier.sheen(position: () -> Float, strength: () -> Float): Modifier = drawWithContent {
    drawContent()
    val s = strength()
    if (s <= 0.001f) return@drawWithContent
    val centre = Offset(size.width * position(), size.height / 2f)
    val angle = Math.toRadians(105.0)
    val dir = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
    val half = size.width * 0.24f
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.5f to Color.White.copy(alpha = 0.20f * s),
            1f to Color.Transparent,
            start = centre - dir * half,
            end = centre + dir * half,
        ),
    )
}

/** Runs an [Animatable] to [target] the capsule's way in (a bouncy spring) or out (a wind-up, then away). */
suspend fun Animatable<Float, androidx.compose.animation.core.AnimationVector1D>.capsuleTo(show: Boolean, windUp: Boolean = true) {
    if (show) {
        animateTo(1f, spring(PageMotionSpec.CAPSULE_SHOW_DAMPING, PageMotionSpec.CAPSULE_SHOW_STIFFNESS, PageMotionSpec.PAGE_REST_THRESHOLD))
    } else if (!windUp) {
        // Leaving the library: a plain, quick sink — no wind-up, so nothing rises while the page is leaving.
        animateTo(0f, tween(PageMotionSpec.CAPSULE_LEAVE_MS, easing = androidx.compose.animation.core.FastOutLinearInEasing))
    } else {
        animateTo(0f, tween(PageMotionSpec.CAPSULE_HIDE_MS, easing = PageMotionSpec.CapsuleHideEasing))
    }
}
