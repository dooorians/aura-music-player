package com.ikko.aura.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ikko.aura.data.home.WidgetSize
import com.ikko.aura.viewmodel.WidgetItem

/** Where one widget sits on the grid, in cells: [col] and [row] of its top-left cell, and how many it covers. */
data class PlacedWidget(val col: Int, val row: Int, val cols: Int, val rows: Int)

/**
 * Packs widgets onto a grid of [columns] columns, in order: each takes the first free place — scanning row by row, left to
 * right — where it fits whole. A tall tile beside two short ones, a bar across the page, a banner: whatever the sizes,
 * they interlock the way the reference does, and changing one size only moves what comes after it.
 *
 * A widget wider than the grid is narrowed to it rather than dropped.
 */
fun packWidgets(sizes: List<WidgetSize>, columns: Int = WidgetSize.GRID_COLUMNS): List<PlacedWidget> {
    val taken = ArrayList<BooleanArray>()
    fun free(col: Int, row: Int, cols: Int, rows: Int): Boolean {
        for (r in row until row + rows) {
            if (r >= taken.size) continue
            for (c in col until col + cols) if (taken[r][c]) return false
        }
        return true
    }
    fun findSlot(cols: Int, rows: Int): PlacedWidget {
        var row = 0
        while (true) {
            for (col in 0..columns - cols) {
                if (free(col, row, cols, rows)) return PlacedWidget(col, row, cols, rows)
            }
            row++
        }
    }
    return sizes.map { size ->
        val placed = findSlot(size.cols.coerceAtMost(columns), size.rows)
        while (taken.size < placed.row + placed.rows) taken.add(BooleanArray(columns))
        for (r in placed.row until placed.row + placed.rows) {
            for (c in placed.col until placed.col + placed.cols) taken[r][c] = true
        }
        placed
    }
}

/** How many rows the packed widgets cover in all. */
fun rowCount(placed: List<PlacedWidget>): Int = placed.maxOfOrNull { it.row + it.rows } ?: 0

/**
 * Renders a list of [WidgetItem]s packed onto a 2-column grid using [packWidgets].
 * Medium cards (1x2) are rendered as exact squares, and compact cards (1x1) stack flush beside them.
 */
@Composable
fun HomeWidgetGrid(
    items: List<WidgetItem>,
    onWidgetClick: (WidgetItem) -> Unit,
    onWidgetPlay: (WidgetItem) -> Unit,
    onWidgetShuffle: (WidgetItem) -> Unit,
    onWidgetMore: (WidgetItem) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
) {
    if (items.isEmpty()) return

    val sizes = remember(items) { items.map { it.widget.size } }
    val placed = remember(sizes) { packWidgets(sizes) }

    Layout(
        content = {
            items.forEach { item ->
                key(item.widget.id) {
                    HomeWidgetCard(
                        content = item.content,
                        size = item.widget.size,
                        onClick = { onWidgetClick(item) },
                        onPlay = { onWidgetPlay(item) },
                        onShuffle = { onWidgetShuffle(item) },
                        onMore = { onWidgetMore(item) },
                        showActions = item.widget.kind != com.ikko.aura.data.home.WidgetKind.ALBUM &&
                            item.widget.kind != com.ikko.aura.data.home.WidgetKind.GIF,
                        mesh = item.widget.kind != com.ikko.aura.data.home.WidgetKind.ALBUM &&
                            item.widget.kind != com.ikko.aura.data.home.WidgetKind.RESUME &&
                            item.widget.kind != com.ikko.aura.data.home.WidgetKind.GIF,
                        // Mix Favoris and Les plus écoutés wear colours of their own, not the covers': a deep burgundy
                        // running into indigo, and an emerald running into deep blue.
                        palette = when (item.widget.kind) {
                            com.ikko.aura.data.home.WidgetKind.FAVORITE_ALBUMS -> listOf(
                                androidx.compose.ui.graphics.Color(0xFF9B1236), androidx.compose.ui.graphics.Color(0xFF4A3FA8),
                            )
                            com.ikko.aura.data.home.WidgetKind.TOP_PLAYED -> listOf(
                                androidx.compose.ui.graphics.Color(0xFF117A5C), androidx.compose.ui.graphics.Color(0xFF14587F),
                            )
                            else -> null
                        },
                        showText = item.widget.kind != com.ikko.aura.data.home.WidgetKind.ALBUM,
                    )
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val spacingPx = spacing.roundToPx()
        val totalWidth = constraints.maxWidth
        val columns = WidgetSize.GRID_COLUMNS
        val cellWidth = maxOf(0, (totalWidth - (columns - 1) * spacingPx) / columns)
        // Sizing formula: medium (1x2) is square, so 2 * cellHeight + spacing = cellWidth
        val cellHeight = maxOf(0, (cellWidth - spacingPx) / 2)

        val placeables = measurables.mapIndexed { index, measurable ->
            val p = placed.getOrNull(index) ?: PlacedWidget(0, 0, 1, 1)
            val w = p.cols * cellWidth + (p.cols - 1) * spacingPx
            val h = p.rows * cellHeight + (p.rows - 1) * spacingPx
            measurable.measure(
                Constraints(
                    minWidth = w.coerceAtLeast(0),
                    maxWidth = w.coerceAtLeast(0),
                    minHeight = h.coerceAtLeast(0),
                    maxHeight = h.coerceAtLeast(0),
                ),
            )
        }

        val rows = rowCount(placed)
        val totalHeight = if (rows > 0) rows * cellHeight + (rows - 1) * spacingPx else 0

        layout(totalWidth, totalHeight) {
            placeables.forEachIndexed { index, placeable ->
                val p = placed.getOrNull(index) ?: PlacedWidget(0, 0, 1, 1)
                val x = p.col * (cellWidth + spacingPx)
                val y = p.row * (cellHeight + spacingPx)
                placeable.placeRelative(x, y)
            }
        }
    }
}
