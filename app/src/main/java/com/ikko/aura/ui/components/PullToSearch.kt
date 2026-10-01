package com.ikko.aura.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch

@Composable
fun Modifier.pullToSearch(
    searchPull: Animatable<Float, *>,
    searchOpen: Boolean,
    openSearch: () -> Unit,
    closeSearch: () -> Unit,
    pullThreshold: Float = 320f,
    commitAt: Float = 0.55f,
): Modifier {
    val scope = rememberCoroutineScope()
    val connection = remember(searchPull, searchOpen, openSearch, closeSearch) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val p = searchPull.value
                // If we are currently in the middle of a pull down, consume upward scrolls
                // to undo the pull before scrolling the actual list.
                // Only as much as it takes to put the page back: the rest of the stroke scrolls the list.
                if (p > 0f && !searchOpen && source == NestedScrollSource.UserInput && available.y < 0f) {
                    val used = maxOf(available.y, -p * pullThreshold)
                    scope.launch { searchPull.snapTo((p + used / pullThreshold).coerceAtLeast(0f)) }
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // If list can't scroll down anymore (at the top) and user keeps dragging down
                if (available.y > 0f && !searchOpen && source == NestedScrollSource.UserInput) {
                    val delta = available.y / pullThreshold
                    val newP = (searchPull.value + delta).coerceIn(0f, 1f)
                    scope.launch { searchPull.snapTo(newP) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!searchOpen && searchPull.value > 0f) {
                    // Significant pull check
                    if (searchPull.value >= commitAt || available.y > 1000f) {
                        openSearch()
                    } else {
                        closeSearch()
                    }
                    return if (searchPull.value > 0f) Velocity(0f, available.y) else Velocity.Zero
                }
                return Velocity.Zero
            }
        }
    }
    return this.nestedScroll(connection)
}
