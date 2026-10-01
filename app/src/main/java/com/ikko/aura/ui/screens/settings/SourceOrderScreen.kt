package com.ikko.aura.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.SourceOrderViewModel
import kotlin.math.roundToInt

/**
 * The sources in priority order, dragged by their handle exactly as the queue's songs are. The first is the one the
 * search lists first.
 */
@Composable
fun SourceOrderScreen(viewModel: SourceOrderViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    GradientScreenScaffold {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = LocalPlayerPillHeight.current + 12.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)) {
                    Text(
                        text = "Priorité des sources",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = AuraColors.TextPrimary,
                    )
                    Text(
                        text = "Glisse une source par sa poignée : plus elle est haut, plus elle passe en premier dans la recherche.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AuraColors.TextSecondary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                val isDragged = draggedIndex == index
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            if (isDragged) {
                                translationY = dragOffset
                                scaleX = 1.03f
                                scaleY = 1.03f
                            }
                        }
                        .then(if (isDragged) Modifier else Modifier.animateItem())
                        .clip(AuraShapes.SquircleSmall)
                        .background(if (isDragged) AuraColors.GlassBorder else AuraColors.GlassFill.copy(alpha = 0.3f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = AuraColors.TextTertiary,
                        modifier = Modifier.width(26.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = if (entry.enabled) AuraColors.TextPrimary else AuraColors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (entry.enabled) entry.detail else "${entry.detail} · désactivée",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuraColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = CupertinoIcons.DragHandle,
                        contentDescription = "Déplacer",
                        tint = AuraColors.TextTertiary,
                        modifier = Modifier
                            .size(44.dp)
                            .padding(10.dp)
                            .pointerInput(entry.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggedIndex = index
                                        dragOffset = 0f
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragEnd = { draggedIndex = null; dragOffset = 0f },
                                    onDragCancel = { draggedIndex = null; dragOffset = 0f },
                                ) { change, drag ->
                                    change.consume()
                                    dragOffset += drag.y
                                    val from = draggedIndex ?: return@detectDragGestures
                                    // The header is item 0, so the entry at [from] is the list's item from + 1.
                                    val rowHeight = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == from + 1 }?.size?.toFloat()
                                        ?: return@detectDragGestures
                                    // Whole rows, as the queue does: a fast drag that outruns a frame still lands right.
                                    val steps = (dragOffset / rowHeight).roundToInt()
                                    if (steps != 0) {
                                        val to = (from + steps).coerceIn(0, entries.lastIndex)
                                        if (to != from) {
                                            viewModel.move(from, to)
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            dragOffset -= (to - from) * rowHeight
                                            draggedIndex = to
                                        }
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}
