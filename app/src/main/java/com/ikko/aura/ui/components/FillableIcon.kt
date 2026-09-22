package com.ikko.aura.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector

private const val FILL_MS = 160
private const val POP_FROM = 0.72f

/**
 * An icon that has an outline and a filled form and moves between them, with a small springy pop
 * on every change — the way iOS answers a tap on a toggle. Where a glyph has no filled form,
 * pass the same vector for both and only the pop and the colour change.
 *
 * The size comes from [modifier]. The very first composition does not pop: a toggle that is
 * already on when the screen opens should simply be on.
 */
@Composable
fun FillableIcon(
    outline: ImageVector,
    filled: ImageVector,
    active: Boolean,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val fill by animateFloatAsState(if (active) 1f else 0f, tween(FILL_MS), label = "icon-fill")
    val color by animateColorAsState(tint, tween(FILL_MS), label = "icon-tint")
    val pop = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(active, outline, filled) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        pop.snapTo(POP_FROM)
        pop.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium, visibilityThreshold = 0.0004f),
        )
    }

    Box(modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
        if (outline === filled) {
            Icon(outline, contentDescription, Modifier.matchParentSize(), tint = color)
        } else {
            Icon(
                outline,
                contentDescription,
                Modifier.matchParentSize().graphicsLayer { alpha = 1f - fill },
                tint = color,
            )
            Icon(
                filled,
                null,
                Modifier.matchParentSize().graphicsLayer { alpha = fill },
                tint = color,
            )
        }
    }
}
