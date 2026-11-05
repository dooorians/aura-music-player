package com.ikko.aura.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ikko.aura.data.AlbumLayoutMode
import com.ikko.aura.data.TrackLayoutMode
import com.ikko.aura.ui.components.LocalEctoPalette
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.screens.home.darkGlass
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

// The settings' parts, in the measures of Home and Library (design px of 1080): glass groups of 1000 wide with 38 corners,
// rows with a 42 title and a 30 line under it, a large switch, and small drawings that show what a choice does.

private val DangerColor = Color(0xFFFF7A70)

/** The colour that something switched on or chosen takes: the cover's, in its middle tones (the pale accent is for text). */
@Composable
internal fun settingsAccent(): Color = LocalEctoPalette.current.cols[3]

/** A glass card of rows, with an optional title above it and a line under it. */
@Composable
internal fun SettingsGroup(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val u = designUnit()
    Column(Modifier.fillMaxWidth().padding(horizontal = u * 40f)) {
        if (title != null) {
            Text(
                title,
                color = Color.White.copy(alpha = 0.55f),
                style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(32f), lineHeight = designSp(40f)),
                modifier = Modifier.padding(start = u * 20f, top = u * 40f, bottom = u * 14f),
            )
        } else {
            Spacer(Modifier.height(u * 24f))
        }
        val shape = RoundedCornerShape(u * 38f)
        Column(Modifier.fillMaxWidth().darkGlass(shape, u * 1.5f).clip(shape), content = content)
        if (footer != null) {
            Text(
                footer,
                color = Color.White.copy(alpha = 0.5f),
                style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(28f), lineHeight = designSp(36f)),
                modifier = Modifier.padding(start = u * 20f, end = u * 20f, top = u * 14f),
            )
        }
    }
}

@Composable
internal fun SettingsDivider() {
    val u = designUnit()
    Box(Modifier.padding(start = u * 28f).fillMaxWidth().height(u * 1.5f).background(Color.White.copy(alpha = 0.1f)))
}

/**
 * One row: a title, a short line under it, and (if there is more to say) a "Détails" that opens the rest in place. A
 * [danger] row is in red. [icon] is a coloured tile in front of it.
 */
@Composable
internal fun SettingsRow(
    title: String,
    summary: String? = null,
    detail: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val u = designUnit()
    val accent = settingsAccent()
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = u * 28f, vertical = u * 26f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(u * 80f).clip(RoundedCornerShape(u * 24f)).background(iconTint ?: accent),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(u * 44f)) }
            Spacer(Modifier.width(u * 26f))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (danger) DangerColor else Color.White,
                style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(42f), lineHeight = designSp(50f)),
            )
            if (summary != null) {
                Text(
                    summary,
                    color = Color.White.copy(alpha = 0.6f),
                    style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(30f), lineHeight = designSp(38f)),
                )
            }
            if (detail != null) {
                Text(
                    if (open) "Masquer" else "Détails",
                    color = accent,
                    style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(30f), lineHeight = designSp(38f)),
                    modifier = Modifier
                        .padding(top = u * 8f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = !open },
                )
                AnimatedVisibility(open) {
                    Text(
                        detail,
                        color = Color.White.copy(alpha = 0.72f),
                        style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Medium, fontSize = designSp(30f), lineHeight = designSp(40f)),
                        modifier = Modifier.padding(top = u * 10f),
                    )
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(u * 20f))
            trailing()
        }
    }
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    summary: String? = null,
    detail: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) = SettingsRow(
    title = title, summary = summary, detail = detail,
    onClick = { onCheckedChange(!checked) },
    trailing = { IosSwitch(checked, onCheckedChange) },
)

@Composable
internal fun ChevronTrailing(value: String? = null) {
    val u = designUnit()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (value != null) {
            Text(
                value, color = Color.White.copy(alpha = 0.6f),
                style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(32f), lineHeight = designSp(40f)),
            )
            Spacer(Modifier.width(u * 10f))
        }
        Icon(CupertinoIcons.ChevronRight, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(u * 34f))
    }
}

/** The iOS switch, large (112 by 68): the cover's colour when on, a pale glass when off. */
@Composable
internal fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val u = designUnit()
    val unitPx = with(androidx.compose.ui.platform.LocalDensity.current) { u.toPx() }
    val accent = settingsAccent()
    val t by animateFloatAsState(if (checked) 1f else 0f, tween(220), label = "ios-switch")
    // Read while drawing, like the knob's offset: the 220 ms turn of the switch recomposes nothing.
    val track = animateColorAsState(if (checked) accent else Color.White.copy(alpha = 0.2f), tween(220), label = "ios-switch-track")
    Box(
        Modifier
            .size(width = u * 112f, height = u * 68f)
            .clip(CircleShape)
            .drawBehind { drawRect(track.value) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(((6f + 44f * t) * unitPx).roundToInt(), 0) }
                .size(u * 56f)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Choices drawn instead of named

/** One choice: a small drawing, its name under it; the chosen one has the cover's colour round it. */
@Composable
internal fun PreviewOption(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ratio: Float = SCREEN_RATIO,
    draw: DrawScope.() -> Unit,
) {
    val u = designUnit()
    val accent = settingsAccent()
    val ring by animateColorAsState(if (selected) accent else Color.White.copy(alpha = 0.08f), tween(180), label = "preview-ring")
    val shape = RoundedCornerShape(u * 28f)
    Column(
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.06f))
                .border(if (selected) u * 4f else u * 1.5f, ring, shape)
                .padding(u * 18f),
        ) { Canvas(Modifier.fillMaxSize()) { draw() } }
        Text(
            label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(32f), lineHeight = designSp(40f)),
            modifier = Modifier.padding(top = u * 14f),
        )
    }
}

@Composable
internal fun PreviewRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val u = designUnit()
    Row(
        modifier.fillMaxWidth().padding(horizontal = u * 28f, vertical = u * 28f),
        horizontalArrangement = Arrangement.spacedBy(u * 22f),
        content = content,
    )
}

/** The albums' layouts, as three small drawings to touch. */
@Composable
internal fun AlbumLayoutChooser(selected: AlbumLayoutMode, onSelect: (AlbumLayoutMode) -> Unit) {
    PreviewRow {
        PreviewOption(selected == AlbumLayoutMode.GRID_2, "2 colonnes", { onSelect(AlbumLayoutMode.GRID_2) }, Modifier.weight(1f)) { inScreen { drawCoverGrid(2) } }
        PreviewOption(selected == AlbumLayoutMode.GRID_3, "3 colonnes", { onSelect(AlbumLayoutMode.GRID_3) }, Modifier.weight(1f)) { inScreen { drawCoverGrid(3) } }
        PreviewOption(selected == AlbumLayoutMode.LIST, "Liste", { onSelect(AlbumLayoutMode.LIST) }, Modifier.weight(1f)) { inScreen { drawRows(columns = 1, rows = 4) } }
    }
}

/** The songs' layouts: a list, or two columns of rows. */
@Composable
internal fun TrackLayoutChooser(selected: TrackLayoutMode, onSelect: (TrackLayoutMode) -> Unit) {
    PreviewRow {
        PreviewOption(selected == TrackLayoutMode.LIST, "Liste", { onSelect(TrackLayoutMode.LIST) }, Modifier.weight(1f)) { inScreen { drawRows(columns = 1, rows = 4) } }
        PreviewOption(selected == TrackLayoutMode.GRID_2, "2 colonnes", { onSelect(TrackLayoutMode.GRID_2) }, Modifier.weight(1f)) { inScreen { drawRows(columns = 2, rows = 4) } }
        Spacer(Modifier.weight(1f))
    }
}

/** The mini player: the bar glued to the foot of the screen, or a pastille of glass floating above it. */
@Composable
internal fun MiniPlayerChooser(glass: Boolean, onSelect: (Boolean) -> Unit) {
    PreviewRow {
        PreviewOption(!glass, "Barre", { onSelect(false) }, Modifier.weight(1f), ratio = SCREEN_RATIO) { drawPhone(); drawDockedBar() }
        PreviewOption(glass, "Verre", { onSelect(true) }, Modifier.weight(1f), ratio = SCREEN_RATIO) { drawPhone(); drawGlassPill() }
    }
}

/** How to go from Home to the Library: a swipe, with the player the width of the screen, or a button beside the player. */
@Composable
internal fun PageSwitchChooser(button: Boolean, onSelect: (Boolean) -> Unit) {
    PreviewRow {
        PreviewOption(!button, "Balayage", { onSelect(false) }, Modifier.weight(1f), ratio = SCREEN_RATIO) { drawPhone(); drawSwipeHint(); drawDockedBar() }
        PreviewOption(button, "Bouton", { onSelect(true) }, Modifier.weight(1f), ratio = SCREEN_RATIO) { drawPhone(); drawDockedBar(width = 0.66f); drawSwitchButton() }
    }
}

// --- the drawings ---

private val Ink = Color.White

/** The iKKO MindOne Pro's screen: 1080 by 1240, nearly square, with small corners. Every drawing is in that shape. */
private const val SCREEN_RATIO = 1080f / 1240f

/** The screen with the header of a page (its title); [content] is drawn under it, inside the screen's margins. */
private fun DrawScope.inScreen(content: DrawScope.() -> Unit) {
    drawPhone(rows = false)
    val pad = size.width * 0.09f
    val top = size.height * 0.2f
    inset(pad, top, pad, pad * 0.8f) { content() }
}

private fun DrawScope.drawCoverGrid(columns: Int) {
    val gap = size.width * if (columns == 2) 0.08f else 0.07f
    val cell = (size.width - gap * (columns - 1)) / columns
    val bar = cell * 0.1f
    val rowH = cell + bar * 2.6f + gap
    var y = 0f
    var r = 0
    while (y < size.height && r < 4) {
        for (c in 0 until columns) {
            val x = c * (cell + gap)
            if (y + cell > size.height) break
            drawRoundRect(Ink.copy(alpha = 0.3f), Offset(x, y), Size(cell, cell), CornerRadius(cell * 0.16f))
            if (y + cell + bar * 2.6f <= size.height) {
                drawRoundRect(Ink.copy(alpha = 0.42f), Offset(x, y + cell + bar * 0.7f), Size(cell * 0.82f, bar), CornerRadius(bar / 2))
                drawRoundRect(Ink.copy(alpha = 0.22f), Offset(x, y + cell + bar * 2.0f), Size(cell * 0.54f, bar), CornerRadius(bar / 2))
            }
        }
        y += rowH
        r++
    }
}

/** Rows of a cover and two lines, in [columns] columns. */
private fun DrawScope.drawRows(columns: Int, rows: Int) {
    val gap = size.width * 0.06f
    val colW = (size.width - gap * (columns - 1)) / columns
    val rowH = size.height / rows
    val cover = minOf(rowH * 0.78f, colW * 0.4f)
    for (c in 0 until columns) {
        val x0 = c * (colW + gap)
        for (r in 0 until rows) {
            val y0 = r * rowH + (rowH - cover) / 2f
            drawRoundRect(Ink.copy(alpha = 0.3f), Offset(x0, y0), Size(cover, cover), CornerRadius(cover * 0.18f))
            val tx = x0 + cover + colW * 0.07f
            val tw = colW - cover - colW * 0.07f
            val bar = cover * 0.2f
            drawRoundRect(Ink.copy(alpha = 0.42f), Offset(tx, y0 + cover * 0.18f), Size(tw * 0.9f, bar), CornerRadius(bar / 2))
            drawRoundRect(Ink.copy(alpha = 0.22f), Offset(tx, y0 + cover * 0.58f), Size(tw * 0.55f, bar), CornerRadius(bar / 2))
        }
    }
}

private fun DrawScope.drawPhone(rows: Boolean = true) {
    val r = size.width * 0.07f
    drawRoundRect(Ink.copy(alpha = 0.07f), cornerRadius = CornerRadius(r))
    drawRoundRect(Ink.copy(alpha = 0.3f), cornerRadius = CornerRadius(r), style = Stroke(width = size.width * 0.02f))
    // the page's title, and (when asked) a few rows of what the page holds
    val pad = size.width * 0.09f
    val bar = size.width * 0.04f
    drawRoundRect(Ink.copy(alpha = 0.4f), Offset(pad, size.height * 0.07f), Size(size.width * 0.34f, bar * 1.5f), CornerRadius(bar))
    if (rows) for (i in 0 until 3) {
        val y = size.height * (0.22f + i * 0.17f)
        drawRoundRect(Ink.copy(alpha = 0.16f), Offset(pad, y), Size(size.width - pad * 2, size.height * 0.12f), CornerRadius(bar))
    }
}

/** The black bar at the foot of the screen, edge to edge. */
private fun DrawScope.drawDockedBar(width: Float = 1f) {
    val h = size.height * 0.13f
    val w = size.width * width
    val top = size.height - h
    // One shape, drawn once (two translucent ones over each other made the top half paler): the bar's rectangle cut by the
    // screen's own rounded corners.
    val screen = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(size.width * 0.07f))) }
    clipPath(screen) { drawRect(Ink.copy(alpha = 0.3f), Offset(0f, top), Size(w, h)) }
    val cover = h * 0.58f
    drawRoundRect(Ink.copy(alpha = 0.4f), Offset(size.width * 0.07f, top + (h - cover) / 2f), Size(cover, cover), CornerRadius(cover * 0.2f))
    val bar = cover * 0.2f
    drawRoundRect(Ink.copy(alpha = 0.55f), Offset(size.width * 0.07f + cover * 1.4f, top + h * 0.32f), Size(w * 0.3f, bar), CornerRadius(bar / 2))
    drawRoundRect(Ink.copy(alpha = 0.3f), Offset(size.width * 0.07f + cover * 1.4f, top + h * 0.56f), Size(w * 0.2f, bar), CornerRadius(bar / 2))
}

/** The pastille of glass, floating above the foot of the screen with air all round it. */
private fun DrawScope.drawGlassPill() {
    val h = size.height * 0.11f
    val w = size.width * 0.8f
    val x = (size.width - w) / 2f
    val y = size.height - h - size.height * 0.04f
    drawRoundRect(Ink.copy(alpha = 0.24f), Offset(x, y), Size(w, h), CornerRadius(h / 2))
    drawRoundRect(Ink.copy(alpha = 0.55f), Offset(x, y), Size(w, h), CornerRadius(h / 2), style = Stroke(width = size.width * 0.018f))
    val cover = h * 0.66f
    drawRoundRect(Ink.copy(alpha = 0.5f), Offset(x + h * 0.2f, y + (h - cover) / 2f), Size(cover, cover), CornerRadius(cover * 0.5f))
    val bar = cover * 0.22f
    drawRoundRect(Ink.copy(alpha = 0.6f), Offset(x + h * 0.2f + cover * 1.3f, y + h * 0.3f), Size(w * 0.3f, bar), CornerRadius(bar / 2))
    drawRoundRect(Ink.copy(alpha = 0.35f), Offset(x + h * 0.2f + cover * 1.3f, y + h * 0.56f), Size(w * 0.2f, bar), CornerRadius(bar / 2))
}

/** A finger's swipe over the page: a line with an arrowhead at each end. */
private fun DrawScope.drawSwipeHint() {
    val y = size.height * 0.62f
    val x0 = size.width * 0.2f
    val x1 = size.width * 0.8f
    val head = size.width * 0.06f
    val s = Stroke(width = size.width * 0.03f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(Ink.copy(alpha = 0.7f), Offset(x0, y), Offset(x1, y), strokeWidth = s.width, cap = s.cap)
    drawLine(Ink.copy(alpha = 0.7f), Offset(x0, y), Offset(x0 + head, y - head), strokeWidth = s.width, cap = s.cap)
    drawLine(Ink.copy(alpha = 0.7f), Offset(x0, y), Offset(x0 + head, y + head), strokeWidth = s.width, cap = s.cap)
    drawLine(Ink.copy(alpha = 0.7f), Offset(x1, y), Offset(x1 - head, y - head), strokeWidth = s.width, cap = s.cap)
    drawLine(Ink.copy(alpha = 0.7f), Offset(x1, y), Offset(x1 - head, y + head), strokeWidth = s.width, cap = s.cap)
}

/** The round button beside the player, with a small house on it. */
private fun DrawScope.drawSwitchButton() {
    val h = size.height * 0.13f
    val d = h * 0.9f
    val cx = size.width * 0.66f + (size.width * 0.34f) / 2f
    val cy = size.height - h / 2f
    drawCircle(Ink.copy(alpha = 0.3f), d / 2f, Offset(cx, cy))
    drawCircle(Ink.copy(alpha = 0.6f), d / 2f, Offset(cx, cy), style = Stroke(width = size.width * 0.015f))
    val g = d * 0.2f
    val roof = Path().apply {
        moveTo(cx - g * 1.3f, cy - g * 0.1f); lineTo(cx, cy - g * 1.4f); lineTo(cx + g * 1.3f, cy - g * 0.1f)
    }
    drawPath(roof, Ink.copy(alpha = 0.85f), style = Stroke(width = size.width * 0.02f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    drawRect(Ink.copy(alpha = 0.85f), Offset(cx - g * 0.8f, cy - g * 0.1f), Size(g * 1.6f, g * 1.2f))
}

/**
 * Two songs where one ends and the next begins. Without Automix the end of one fades out over a fixed time while the other
 * fades in, their beats not meeting; with it the beats are the same pace and fall together, over a longer stretch.
 */
@Composable
internal fun AutomixDiagram(enabled: Boolean) {
    val u = designUnit()
    val accent = settingsAccent()
    val t by animateFloatAsState(if (enabled) 1f else 0f, tween(420), label = "automix-diagram")
    Column(Modifier.fillMaxWidth().padding(horizontal = u * 28f, vertical = u * 28f)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2.4f)
                .clip(RoundedCornerShape(u * 28f))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(u * 24f),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val laneH = h * 0.36f
                val overlapStart = w * lerpF(0.55f, 0.42f, t)
                val overlapEnd = w * lerpF(0.75f, 0.78f, t)
                // song 1, then song 2: bars as the level of the music
                val barW = w * 0.012f
                val step = w * 0.026f
                var x = 0f
                var i = 0
                while (x < w) {
                    val env = 0.45f + 0.4f * abs(sin(i * 0.9f)) * 0.8f
                    val aFade = 1f - ((x - overlapStart) / (overlapEnd - overlapStart)).coerceIn(0f, 1f)
                    val bFade = ((x - overlapStart) / (overlapEnd - overlapStart)).coerceIn(0f, 1f)
                    if (x < overlapEnd) {
                        val bh = laneH * env * aFade.coerceAtLeast(0.06f)
                        drawRoundRect(Ink.copy(alpha = 0.55f * (0.35f + 0.65f * aFade)), Offset(x, laneH - bh), Size(barW, bh), CornerRadius(barW / 2))
                    }
                    if (x > overlapStart) {
                        val bh = laneH * env * bFade.coerceAtLeast(0.06f)
                        drawRoundRect(accent.copy(alpha = 0.9f * (0.35f + 0.65f * bFade)), Offset(x, h - bh), Size(barW, bh), CornerRadius(barW / 2))
                    }
                    x += step
                    i++
                }
                // the overlap
                drawRoundRect(
                    Ink.copy(alpha = 0.1f), Offset(overlapStart, 0f), Size(overlapEnd - overlapStart, h), CornerRadius(w * 0.02f),
                )
                // the beats of each song through it: apart without Automix, together with it
                val beat = w * 0.06f
                var bx = overlapStart
                var k = 0
                while (bx <= overlapEnd) {
                    drawLine(Ink.copy(alpha = 0.8f), Offset(bx, laneH * 1.02f), Offset(bx, laneH * 1.22f), strokeWidth = w * 0.006f)
                    val bx2 = bx + beat * 0.5f * (1f - t)
                    drawLine(accent, Offset(bx2, h - laneH * 1.22f), Offset(bx2, h - laneH * 1.02f), strokeWidth = w * 0.006f)
                    if (t > 0.5f) drawLine(Ink.copy(alpha = 0.4f * t), Offset(bx, laneH * 1.22f), Offset(bx2, h - laneH * 1.22f), strokeWidth = w * 0.003f)
                    bx += beat
                    k++
                }
            }
        }
        Text(
            if (enabled) "Les temps des deux titres tombent ensemble, sur une plus longue transition."
            else "Un fondu de longueur fixe : les temps des deux titres ne se rencontrent pas.",
            color = Color.White.copy(alpha = 0.6f),
            style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(28f), lineHeight = designSp(36f)),
            modifier = Modifier.padding(top = u * 16f, start = u * 4f),
        )
    }
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
