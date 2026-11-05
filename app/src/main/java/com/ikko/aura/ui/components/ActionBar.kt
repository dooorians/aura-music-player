package com.ikko.aura.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.screens.album.rememberAlbumButtonColors

// Round buttons of 120 units (about 46 dp on a phone): the 94 of the other headers were hard to hit.
private const val BUTTON_U = 120f
private const val PLAY_U = 150f

/**
 * The action bar of an album or an artist: the source switch (online / on the device), then the round buttons (favourite, add to a
 * playlist, more), and on the right the shuffle and the play button. The round buttons are the app's dark glass, as everywhere
 * else (search, shuffle, sort, queue); only the active segment and the play button are filled, in the page's accent.
 *
 * A button whose callback is null is not drawn. [online] says which side of the switch is on; a side that is [onlineAvailable] or
 * [localAvailable] false stays visible but dimmed, and does nothing.
 */
@Composable
fun ActionBar(
    online: Boolean,
    onSource: (toOnline: Boolean) -> Unit,
    onShuffle: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onlineAvailable: Boolean = true,
    localAvailable: Boolean = true,
    favorite: Boolean? = null,
    onFavorite: () -> Unit = {},
    /** The "+" of the page, with its anchor and its menu, when it has one. */
    add: (@Composable () -> Unit)? = null,
    /** A hairline under the bar, as under the rows of the lists, between it and what it acts on. */
    divider: Boolean = true,
) {
    val colors = rememberAlbumButtonColors()
    val u = designUnit()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(u * 12f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceSwitch(online, onSource, onlineAvailable, localAvailable, colors.primaryFill, colors.primaryInk)
            if (favorite != null) GlassCircle(onFavorite) {
                FillableIcon(
                    outline = CupertinoIcons.Star, filled = CupertinoIcons.StarFilled, active = favorite,
                    contentDescription = "Favori", tint = Color.White, modifier = Modifier.size(u * 52f),
                )
            }
            add?.invoke()
            Box(Modifier.weight(1f))
            GlassCircle(onShuffle) {
                Icon(CupertinoIcons.Shuffle, contentDescription = "Aléatoire", tint = Color.White, modifier = Modifier.size(u * 52f))
            }
            Box(
                modifier = Modifier
                    .size(u * PLAY_U)
                    .clip(CircleShape)
                    .background(colors.primaryFill)
                    .border(u * 3f, Color.White.copy(alpha = 0.28f), CircleShape)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CupertinoIcons.Play, contentDescription = "Lire", tint = colors.primaryInk, modifier = Modifier.size(u * 62f))
            }
        }
        if (divider) {
            Spacer(Modifier.height(u * 30f))
            // The hairline of the lists: 1.5 units, white at 16 %.
            Box(Modifier.fillMaxWidth().height(u * 1.5f).background(Color.White.copy(alpha = 0.16f)))
        }
    }
}

/** The three dots of a page: a round glass button that opens the page's menu. */
@Composable
fun MoreButton(onClick: () -> Unit) {
    GlassCircle(onClick) {
        Icon(CupertinoIcons.MoreVertical, contentDescription = "Plus d'options", tint = Color.White, modifier = Modifier.size(designUnit() * 52f))
    }
}

/** The "+" of a page: a round glass button that opens the page's menu (add to a playlist, play next, download…). */
@Composable
fun AddButton(onClick: () -> Unit) {
    GlassCircle(onClick) {
        Icon(CupertinoIcons.Add, contentDescription = "Ajouter", tint = Color.White, modifier = Modifier.size(designUnit() * 56f))
    }
}

/** One round button of dark glass, the size of the app's header buttons. */
@Composable
fun GlassCircle(onClick: () -> Unit, content: @Composable () -> Unit) {
    val u = designUnit()
    Box(
        modifier = Modifier
            .size(u * BUTTON_U)
            .glassSurface(CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SourceSwitch(
    online: Boolean,
    onSource: (Boolean) -> Unit,
    onlineAvailable: Boolean,
    localAvailable: Boolean,
    activeFill: Color,
    activeInk: Color,
) {
    val u = designUnit()
    Row(
        modifier = Modifier
            .height(u * BUTTON_U)
            .glassSurface(CircleShape)
            .clip(CircleShape)
            .padding(u * 7f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment("En ligne", selected = online, enabled = onlineAvailable, fill = activeFill, ink = activeInk) { onSource(true) }
        Segment("Local", selected = !online, enabled = localAvailable, fill = activeFill, ink = activeInk) { onSource(false) }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, enabled: Boolean, fill: Color, ink: Color, onClick: () -> Unit) {
    val u = designUnit()
    // Read while drawing: the fill's fade redraws the segment, it does not recompose it.
    val background = animateColorAsState(if (selected) fill else Color.Transparent, label = "segment-fill")
    val text by animateColorAsState(if (selected) ink else Color.White.copy(alpha = 0.78f), label = "segment-ink")
    Box(
        modifier = Modifier
            .height(u * (BUTTON_U - 14f))
            .clip(CircleShape)
            .drawBehind { drawRect(background.value) }
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = u * 30f),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = text, fontSize = designSp(34f), fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
