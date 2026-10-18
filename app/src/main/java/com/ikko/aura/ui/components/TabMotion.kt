package com.ikko.aura.ui.components

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity

/**
 * The tab-to-tab motion of the "Transition de l'accueil" / "Transition des onglets" boards (their scripts, not their
 * prose): the header and the player stay put, only the panel changes. The old one leaves in 170 ms, accelerating,
 * 56 design px against the direction of the change, fading; the new one is already coming from 90 ms, sliding in from
 * 80 px over 360 ms, decelerating, without a fade of its own: its items appear one after another ([tabStagger]), so the
 * two overlap and the screen is never empty.
 */
object TabMotion {
    val Exit = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    val Enter = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    const val EXIT_MS = 170
    const val ENTER_MS = 360
    /** The new panel starts arriving this long after the old one has started to leave. */
    const val ENTER_DELAY_MS = 90
    const val COVER_MS = 440
    const val TEXT_MS = 380
    const val ROW_MS = 420
    const val COVER_STEP_MS = 60
    const val ROW_STEP_MS = 30
    /** A list's rows: past the tenth, they all come with the tenth. */
    const val ROW_STEP_CAP = 9
    const val TEXT_DELAY_MS = 120
    const val REDUCED_MS = 140
    /** How long a panel's arrival lasts at most (the twelfth card's text): after it, items are simply there. */
    const val ARRIVAL_MS = ENTER_DELAY_MS + 12 * COVER_STEP_MS + TEXT_DELAY_MS + TEXT_MS + 80
}

/** What the items of a panel that has just come in need to know to settle: whether it is a tab change, and which way. */
class TabEntry(entering: Boolean, val direction: Int) {
    /** True only while the panel is arriving; it goes false for good once the last item has settled. */
    var entering by androidx.compose.runtime.mutableStateOf(entering)
}

val LocalTabEntry = compositionLocalOf { TabEntry(entering = false, direction = 1) }

/**
 * The [AnimatedContent] transition between two panels. [direction] is +1 when the new panel is further along the
 * tabs than the old one, -1 when it is before. Reduced motion is a plain 140 ms cross-fade.
 */
@Composable
fun <S> tabPanelTransition(
    reduceMotion: Boolean,
    direction: (from: S, to: S) -> Int,
): AnimatedContentTransitionScope<S>.() -> ContentTransform {
    val unitPx = with(LocalDensity.current) { designUnit().toPx() }
    return {
        if (reduceMotion) {
            fadeIn(tween(TabMotion.REDUCED_MS)) togetherWith fadeOut(tween(TabMotion.REDUCED_MS))
        } else {
            val d = direction(initialState, targetState)
            val outPx = (56f * unitPx).toInt()
            val inPx = (80f * unitPx).toInt()
            slideInHorizontally(tween(TabMotion.ENTER_MS, TabMotion.ENTER_DELAY_MS, TabMotion.Enter)) { inPx * d } togetherWith (
                slideOutHorizontally(tween(TabMotion.EXIT_MS, easing = TabMotion.Exit)) { -outPx * d } +
                    fadeOut(tween(TabMotion.EXIT_MS, easing = TabMotion.Exit))
                )
        }
    }
}

/** Tells the items of a panel composed in the middle of a transition that they are entering, and which way. */
@Composable
fun ProvideTabEntry(entering: Boolean, direction: Int, content: @Composable () -> Unit) {
    val entry = remember { TabEntry(entering, direction) }
    // The motion belongs to the arrival only: once the last staggered item has settled, an item that comes into view
    // by scrolling is simply there, instead of replaying its entrance.
    LaunchedEffect(entry) {
        if (entry.entering) {
            kotlinx.coroutines.delay(TabMotion.ARRIVAL_MS.toLong())
            entry.entering = false
        }
    }
    CompositionLocalProvider(LocalTabEntry provides entry, content = content)
}

/**
 * An item that settles in: rises [risePx] design px (comes [slidePx] from the side the panel comes from) from 96 % of
 * its size and appears, [index] steps (at most [stepCap]) after the panel starts arriving. Does nothing outside a tab
 * change, so scrolling and first display are untouched.
 */
@Composable
fun Modifier.tabStagger(
    index: Int,
    stepMs: Int,
    durationMs: Int,
    risePx: Float = 22f,
    slidePx: Float = 0f,
    extraDelayMs: Int = 0,
    stepCap: Int = TabMotion.ROW_STEP_CAP,
): Modifier {
    val entry = LocalTabEntry.current
    // Decided once, when the item first composes: an item scrolled back into view later is a new composition, and by
    // then the arrival is over.
    val animate = remember { entry.entering }
    if (!animate) return this
    val unitPx = with(LocalDensity.current) { designUnit().toPx() }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // The arrival ends the moment the listener scrolls: whatever is still waiting for its turn is there at once.
        launch { snapshotFlow { entry.entering }.first { !it }; progress.snapTo(1f) }
        progress.animateTo(
            1f,
            tween(durationMs, TabMotion.ENTER_DELAY_MS + minOf(index, stepCap) * stepMs + extraDelayMs, TabMotion.Enter),
        )
    }
    return graphicsLayer {
        val p = progress.value
        val left = 1f - p
        alpha = p
        scaleX = 0.96f + 0.04f * p
        scaleY = scaleX
        translationY = risePx * unitPx * left
        translationX = slidePx * unitPx * left * entry.direction
    }
}

/** A text under a cover: rises [risePx] and appears [TabMotion.TEXT_DELAY_MS] after its cover. */
@Composable
fun Modifier.tabTextRise(index: Int, risePx: Float = 14f): Modifier {
    val entry = LocalTabEntry.current
    val animate = remember { entry.entering }
    if (!animate) return this
    val unitPx = with(LocalDensity.current) { designUnit().toPx() }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { snapshotFlow { entry.entering }.first { !it }; progress.snapTo(1f) }
        progress.animateTo(
            1f,
            tween(
                TabMotion.TEXT_MS,
                TabMotion.ENTER_DELAY_MS + index * TabMotion.COVER_STEP_MS + TabMotion.TEXT_DELAY_MS,
                TabMotion.Enter,
            ),
        )
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = risePx * unitPx * (1f - p)
    }
}

/** Ends the panel's arrival as soon as the list inside it is being scrolled: a flung list never waits for a turn. */
@Composable
fun EndTabEntryWhenScrolled(scrolling: Boolean) {
    val entry = LocalTabEntry.current
    LaunchedEffect(scrolling) { if (scrolling) entry.entering = false }
}

/** Something of the panel that is not an item (an action): it simply appears, [delayMs] in, over [durationMs]. */
@Composable
fun Modifier.tabFade(delayMs: Int, durationMs: Int): Modifier {
    val entry = LocalTabEntry.current
    val animate = remember { entry.entering }
    if (!animate) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { snapshotFlow { entry.entering }.first { !it }; progress.snapTo(1f) }
        progress.animateTo(1f, tween(durationMs, delayMs))
    }
    return graphicsLayer { alpha = progress.value }
}
