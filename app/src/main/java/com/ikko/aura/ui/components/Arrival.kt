package com.ikko.aura.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val ARRIVAL_EASING = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/**
 * What comes in after a page's placeholders (within 0.7 s of its loading): it fades in and rises a little, [index] steps of
 * 35 ms after the one before. A page that never had placeholders, and what is composed after those 0.7 s (a row scrolled into
 * view later), is simply there: nothing plays twice.
 *
 * [loadedAt] holds the instant the page loaded (set by the page, 0 until then); [beganLoading] is whether it opened as placeholders.
 */
@Composable
fun Modifier.arrivesAfterLoading(index: Int, loadedAt: LongArray, beganLoading: Boolean): Modifier {
    val animate = remember { beganLoading && loadedAt[0] != 0L && System.nanoTime() - loadedAt[0] < 700_000_000L }
    if (!animate) return this
    return this.softArrival(index)
}

/**
 * A part of a page that comes in on its own, after the page (what a source answers late): it fades in and rises a little when it is
 * first composed, [index] steps of 35 ms after the one before.
 */
@Composable
fun Modifier.softArrival(index: Int = 0): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(8) * 35L)
        p.animateTo(1f, tween(320, easing = ARRIVAL_EASING))
    }
    val rise = with(LocalDensity.current) { 10.dp.toPx() }
    return this.graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * rise
    }
}
