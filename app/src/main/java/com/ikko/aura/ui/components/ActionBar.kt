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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
    // One pill for the active side, that slides from a segment to the other (and not a fill that fades out of one and into the
    // other). Where each segment is, relative to the switch, is measured as they are laid out.
    var origin by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var onlineX by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var onlineW by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var localX by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var localW by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val pillX = remember { androidx.compose.animation.core.Animatable(0f) }
    val pillW = remember { androidx.compose.animation.core.Animatable(0f) }
    val placed = remember { BooleanArray(1) }
    androidx.compose.runtime.LaunchedEffect(online, onlineX, onlineW, localX, localW) {
        if (onlineW <= 0f || localW <= 0f) return@LaunchedEffect
        val x = if (online) onlineX else localX
        val w = if (online) onlineW else localW
        if (!placed[0]) {
            // First time: it is already where it belongs.
            pillX.snapTo(x); pillW.snapTo(w); placed[0] = true
        } else {
            val spec = androidx.compose.animation.core.tween<Float>(170, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            kotlinx.coroutines.coroutineScope {
                launch { pillX.animateTo(x, spec) }
                launch { pillW.animateTo(w, spec) }
            }
        }
    }
    Box(
        modifier = Modifier
            .height(u * BUTTON_U)
            .glassSurface(CircleShape)
            .clip(CircleShape)
            .onGloballyPositioned { origin = it.positionInRoot().x },
    ) {
        if (pillW.value > 0f) {
            val pillHeight = u * (BUTTON_U - 14f)
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(pillX.value.roundToInt(), (u * 7f).roundToPx()) }
                    .size(width = with(androidx.compose.ui.platform.LocalDensity.current) { pillW.value.toDp() }, height = pillHeight)
                    .clip(CircleShape)
                    .background(activeFill),
            )
        }
        Row(
            modifier = Modifier.height(u * BUTTON_U).padding(u * 7f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Segment(
                "En ligne", selected = online, enabled = onlineAvailable, ink = activeInk,
                modifier = Modifier.onGloballyPositioned { onlineX = it.positionInRoot().x - origin; onlineW = it.size.width.toFloat() },
            ) { onSource(true) }
            Segment(
                "Local", selected = !online, enabled = localAvailable, ink = activeInk,
                modifier = Modifier.onGloballyPositioned { localX = it.positionInRoot().x - origin; localW = it.size.width.toFloat() },
            ) { onSource(false) }
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, enabled: Boolean, ink: Color, modifier: Modifier, onClick: () -> Unit) {
    val u = designUnit()
    val text by animateColorAsState(if (selected) ink else Color.White.copy(alpha = 0.78f), label = "segment-ink")
    Box(
        modifier = modifier
            .height(u * (BUTTON_U - 14f))
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.3f)
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = u * 30f),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = text, fontSize = designSp(34f), fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
