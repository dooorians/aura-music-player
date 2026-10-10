package com.ikko.aura.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LocalCoverColors
import com.ikko.aura.ui.theme.LocalPageTheme
import kotlin.math.roundToInt

/*
 * The menus of the Claude Design project ("Menu album", "Menu playlist", "Menu tri"), to its measures (1080 px design
 * at 2.5 px per dp): a scrim of 50 % black over the page, the button that opened the menu kept above it as a close
 * button (white 30 %, a 1.5 px rim), and the panel under it — 56 px corners, 12 px padding, a 1.5 px rim at 18 %, a light
 * line on its top edge at 12 %, a 30/80 shadow at 55 %, on the page's colour taken down to a fraction of its brightness
 * at 96 %. Rows: 104 px tall, 44 px corners, 32 px sides, an icon of 44 px and 28 px before the label, 38 px bold.
 */

private val PanelCorner = 22.4.dp
private val PanelPadding = 4.8.dp
private val RowHeight = 41.6.dp
private val RowCorner = 17.6.dp
private val RowSide = 12.8.dp
private val IconSize = 17.6.dp
private val IconGap = 11.2.dp
private val Rim = 0.6.dp
private val Danger = Color(0xFFFF8AA8)

/** The page's colour, for the menu to be made from: the album or card the page is lit with, else the playing track's. */
@Composable
private fun menuBase(): Color =
    LocalPageTheme.current.value?.accent ?: LocalCoverColors.current.accent

/**
 * [state] says whether the menu is out and where its button is. [width] is the design's (760 px for the album's menu, 600
 * for the others); [shade] how much of the colour's brightness the panel keeps (0.30 album, 0.24 playlist, 0.16 sort).
 * [closeIcon] is what the button above the menu shows: the three dots, or a check for the sort menu.
 */
@Composable
fun DesignMenu(
    state: GlassMenuState,
    width: Dp,
    shade: Float,
    modifier: Modifier = Modifier,
    closeIcon: ImageVector = CupertinoIcons.MoreVertical,
    closeTint: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!state.visible) return
    val base = menuBase()
    val surface = remember(base, shade) {
        val c = base.toArgb()
        Color(
            android.graphics.Color.argb(
                (0.96f * 255).roundToInt(),
                (android.graphics.Color.red(c) * shade).roundToInt(),
                (android.graphics.Color.green(c) * shade).roundToInt(),
                (android.graphics.Color.blue(c) * shade).roundToInt(),
            ),
        )
    }
    val density = LocalDensity.current
    val anchor: Rect = state.anchor
    val shape = RoundedCornerShape(PanelCorner)
    Dialog(
        onDismissRequest = { state.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.close() },
        ) {
            // The button, kept where it was and lit as a close button.
            Box(
                Modifier
                    .offset { IntOffset(anchor.left.roundToInt(), anchor.top.roundToInt()) }
                    .size(with(density) { anchor.width.toDp() }, with(density) { anchor.height.toDp() })
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.3f))
                    .border(0.6.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    .clickable { state.close() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(closeIcon, contentDescription = "Fermer", tint = closeTint, modifier = Modifier.size(IconSize))
            }
            // The panel, right-aligned on the button, a little below it.
            val top = with(density) { (anchor.bottom + 7.2.dp.toPx()).roundToInt() }
            Column(
                modifier = modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, top) }
                    .padding(end = 16.dp)
                    .width(width)
                    .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = 0.55f), spotColor = Color.Black.copy(alpha = 0.55f))
                    .clip(shape)
                    .background(surface)
                    .border(BorderStroke(Rim, Color.White.copy(alpha = 0.18f)), shape)
                    // The light line along the top edge.
                    .drawBehind {
                        drawRect(Color.White.copy(alpha = 0.12f), size = androidx.compose.ui.geometry.Size(size.width, 0.8.dp.toPx()))
                    }
                    .padding(PanelPadding)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                content = content,
            )
        }
    }
}

/** A row of a [DesignMenu]: an icon (or the room for one), the label, and a check at the end when [selected] is not null. */
@Composable
fun DesignMenuRow(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    danger: Boolean = false,
    selected: Boolean? = null,
    accent: Color = Color.White,
) {
    val ink = when {
        danger -> Danger
        selected == true -> accent
        else -> Color.White
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .clip(RoundedCornerShape(RowCorner))
            .then(if (selected == true) Modifier.background(Color.White.copy(alpha = 0.10f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = RowSide),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IconGap),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(IconSize))
        Text(
            text = label,
            color = ink,
            fontSize = 15.2.sp,
            lineHeight = 18.4.sp,
            fontWeight = if (selected == true) FontWeight.ExtraBold else FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (selected == true) Icon(CupertinoIcons.Check, contentDescription = "Choisi", tint = accent, modifier = Modifier.size(IconSize))
        else if (selected == false) Spacer(Modifier.size(IconSize))
    }
}

/** The thin line between groups of rows. */
@Composable
fun DesignMenuDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = RowSide, vertical = 2.4.dp)
            .height(0.6.dp)
            .background(Color.White.copy(alpha = 0.12f)),
    )
}
