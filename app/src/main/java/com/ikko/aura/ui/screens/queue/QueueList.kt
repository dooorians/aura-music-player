package com.ikko.aura.ui.screens.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ikko.aura.data.model.Track
import com.ikko.aura.ui.components.TrackListItem
import com.ikko.aura.ui.components.heroRise
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.PlayerUiState
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.keyAt
import kotlin.math.roundToInt

/**
 * The whole queue as one list: tap to play that entry, drag the handle to move it, the cross to drop it.
 * Shared by the queue page and the player's "À suivre" panel — the viewmodel's queue is the only copy.
 */
@Composable
fun QueueList(
    uiState: PlayerUiState,
    playerViewModel: PlayerViewModel,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onRowLongClick: (Track) -> Unit,
    modifier: Modifier = Modifier,
    /** Greys what is behind the playing entry: heard, until it is played again (then it is the playing one). */
    dimPlayed: Boolean = false,
    animateEntry: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val firstVisibleIndex = remember { listState.firstVisibleItemIndex }

    // The row under the finger, and how far it has been dragged from where it sits. The
    // index moves with the row rather than staying where the drag began: each swap re-homes
    // it, which is what lets one long drag cross several rows instead of only ever one.
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var edgeScroll by remember { mutableFloatStateOf(0f) }

    // A queue longer than the screen cannot be reordered without this: the destination is
    // off-view, and a drag that stops at the edge can only ever move a track as far as the
    // viewport already showed.
    LaunchedEffect(edgeScroll) {
        if (edgeScroll == 0f) return@LaunchedEffect
        while (true) {
            listState.scrollBy(edgeScroll)
            withFrameNanos { }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
    ) {
        itemsIndexed(uiState.queue, key = { index, _ -> uiState.queueKeys.keyAt(index) }) { index, track ->
            // The row's position as it is now: the drag below outlives the moves it makes, and its gesture block is
            // keyed on the entry, not on where the entry was when the block was made.
            val rowIndex by rememberUpdatedState(index)
            val isCurrent = index == uiState.queueIndex
            val isDragged = draggedIndex == index
            val isPlayed = dimPlayed && index < uiState.queueIndex

            TrackListItem(
                track = track,
                onClick = { playerViewModel.jumpToQueueIndex(index) },
                onLongClick = { onRowLongClick(track) },
                artSize = 52.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (animateEntry) {
                            Modifier.heroRise((index - firstVisibleIndex).coerceAtLeast(0), rows = true)
                        } else {
                            Modifier
                        },
                    )
                    // Lifted above its neighbours while it moves, so it slides over them
                    // rather than being clipped between two of them.
                    .zIndex(if (isDragged) 1f else 0f)
                    .alpha(if (isPlayed) PLAYED_ALPHA else 1f)
                    .graphicsLayer {
                        if (isDragged) {
                            translationY = dragOffset
                            scaleX = DRAG_SCALE
                            scaleY = DRAG_SCALE
                        }
                    }
                    // The rows being displaced animate into their new places; the one being
                    // dragged must not, or it would fight the finger for its position.
                    .then(if (isDragged) Modifier else Modifier.animateItem())
                    .clip(AuraShapes.SquircleSmall)
                    .background(
                        when {
                            isDragged -> AuraColors.GlassBorder
                            isCurrent -> AuraColors.GlassFill
                            else -> AuraColors.Black.copy(alpha = 0f)
                        },
                    )
                    // The playing entry is told apart by more than its fill: a bar on its edge, and a state for readers.
                    .drawBehind {
                        if (isCurrent) {
                            drawRect(AuraColors.AccentPink, Offset.Zero, Size(3.dp.toPx(), size.height))
                        }
                    }
                    .semantics {
                        if (isCurrent) stateDescription = "En cours de lecture" else if (isPlayed) stateDescription = "Déjà écouté"
                    }
                    .padding(horizontal = 8.dp),
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = CupertinoIcons.DragHandle,
                            contentDescription = "Déplacer dans la file",
                            tint = AuraColors.TextTertiary,
                            modifier = Modifier
                                .size(48.dp)
                                .padding(12.dp)
                                // A handle rather than a long press on the row: the row's own
                                // tap already means "play this", and a queue is a thing you
                                // scroll, so a drag starting anywhere on it would fight the
                                // list for every gesture.
                                .pointerInput(uiState.queueKeys.keyAt(index)) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggedIndex = rowIndex
                                            dragOffset = 0f
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragEnd = {
                                            draggedIndex = null
                                            dragOffset = 0f
                                            edgeScroll = 0f
                                        },
                                        onDragCancel = {
                                            draggedIndex = null
                                            dragOffset = 0f
                                            edgeScroll = 0f
                                        },
                                    ) { change, drag ->
                                        change.consume()
                                        dragOffset += drag.y
                                        val from = draggedIndex ?: return@detectDragGestures
                                        val row = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.index == from }
                                        val rowHeight = row?.size?.takeIf { it > 0 }
                                            ?: return@detectDragGestures

                                        // Past half a row in either direction is a swap. Done
                                        // in whole rows rather than by hit-testing centres so
                                        // a fast drag that outruns a frame still lands right.
                                        val steps = (dragOffset / rowHeight).roundToInt()
                                        if (steps != 0) {
                                            val to = (from + steps)
                                                .coerceIn(0, uiState.queue.lastIndex)
                                            if (to != from) {
                                                playerViewModel.moveInQueue(from, to)
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                dragOffset -= (to - from) * rowHeight
                                                draggedIndex = to
                                            }
                                        }

                                        val viewport = listState.layoutInfo.viewportEndOffset
                                        val centre = (row.offset + dragOffset + rowHeight / 2f)
                                        edgeScroll = when {
                                            centre < EDGE_ZONE_PX -> -EDGE_SPEED_PX
                                            centre > viewport - EDGE_ZONE_PX -> EDGE_SPEED_PX
                                            else -> 0f
                                        }
                                    }
                                },
                        )
                        IconButton(onClick = { playerViewModel.removeFromQueue(index) }) {
                            Icon(CupertinoIcons.Close, contentDescription = "Retirer", tint = AuraColors.TextTertiary)
                        }
                    }
                },
            )
        }
    }
}

/** How faint a row already played is drawn. */
private const val PLAYED_ALPHA = 0.4f

/** How much the dragged row lifts off the list. */
private const val DRAG_SCALE = 1.03f

/** How close to an edge starts scrolling, and how fast, in pixels. */
private const val EDGE_ZONE_PX = 120f
private const val EDGE_SPEED_PX = 14f
