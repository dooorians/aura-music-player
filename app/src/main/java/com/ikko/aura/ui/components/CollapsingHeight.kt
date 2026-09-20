package com.ikko.aura.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/**
 * Collapses a header's height toward zero as [offsetPx] goes negative, reading that offset in the
 * **layout** phase rather than during composition.
 *
 * The obvious spelling — `.height(with(density) { (full + offset).toDp() })` — subscribes the
 * enclosing composable to a value that changes on every single scroll frame. In `MainLayout` that
 * composable wraps the whole `NavHost`, and `LibraryScreen` nested the same pattern inside it, so
 * one drag recomposed both the entire navigation tree and the grid, ~90 times a second. Measured
 * with `dumpsys gfxinfo` during a library scroll, the UI thread spent ~10ms per frame in
 * recomposition while measure/layout/draw together needed under 3ms, and 96% of frames were
 * janky.
 *
 * Taking the value inside `Modifier.layout` keeps it in the phase that actually needs it, so a
 * scroll re-lays-out the header and nothing recomposes. The child is also measured against the
 * full height instead of the shrinking one, so its own measurement stops being invalidated on
 * every frame too — it just gets clipped by the parent (pair this with `clipToBounds`).
 */
fun Modifier.collapsingHeight(fullHeightPx: Float, offsetPx: () -> Float): Modifier =
    layout { measurable, constraints ->
        val full = fullHeightPx.roundToInt().coerceAtLeast(0)
        val visible = (fullHeightPx + offsetPx()).coerceIn(0f, fullHeightPx).roundToInt()
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = full))
        layout(placeable.width, visible) { placeable.place(0, 0) }
    }

/**
 * The collapse offset to feed [collapsingHeight], held so that it survives its screen leaving
 * composition.
 *
 * A plain `remember` here desynced the header from its own list: `rememberLazyGridState` is
 * internally saveable, so navigating to the player and back restored the list exactly where it had
 * been left, while the offset reset to zero — a fully expanded header sitting above a list that was
 * still scrolled halfway down. Saved as a bare `Float` rather than through `autoSaver` so the hot
 * path stays a `MutableFloatState` and scroll writes don't box.
 */
@Composable
fun rememberCollapseOffset(): MutableFloatState = rememberSaveable(
    saver = Saver(save = { it.floatValue }, restore = { mutableFloatStateOf(it) }),
) { mutableFloatStateOf(0f) }
