package com.ikko.aura.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons

/**
 * The measures Home and Library share, in design px (1080 wide): the common header (search + avatar) is laid out once,
 * above the pager, so it stays still when the page changes; each page then starts its own row of tabs at [TABS_TOP].
 */
object PageHeader {
    const val SEARCH_TOP = 114f
    const val SEARCH_HEIGHT = 112f
    const val TABS_TOP = 262f
    const val TABS_HEIGHT = 88f
    const val TAB_TEXT = 42f
    const val TAB_LINE = 50f
    const val TAB_GAP = 40f
    const val MARGIN = 40f
    /** Shuffle / sort: a 112 px touch area around a 54 px glyph, centred on the tabs' line. */
    const val ACTION = 112f
    const val ACTION_GLYPH = 54f
    const val COUNT_TOP = TABS_TOP + TABS_HEIGHT + 16f
}

/** Search field (full width) and the avatar on its right: the one header of Home and Library. */
@Composable
fun CommonHeader(
    profileUri: String?,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val u = designUnit()
    val muted = Color(0xFF7F7F89)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = u * PageHeader.MARGIN, end = u * PageHeader.MARGIN, top = u * PageHeader.SEARCH_TOP)
            .height(u * PageHeader.SEARCH_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(u * 24f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Color(0xFF1A1A21))
                .clickable(onClick = onSearch)
                .padding(horizontal = u * 42f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(u * 26f),
        ) {
            Icon(CupertinoIcons.Search, null, tint = muted, modifier = Modifier.size(u * 58f))
            Text(
                "Rechercher", color = muted, fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold,
                fontSize = designSp(44f), lineHeight = designSp(52f), maxLines = 1,
            )
        }
        Box(
            Modifier.size(u * PageHeader.SEARCH_HEIGHT).clip(CircleShape).background(Color(0xFF1F1F27)).clickable(onClick = onProfile),
            contentAlignment = Alignment.Center,
        ) {
            if (profileUri != null) {
                AlbumArt(
                    uri = android.net.Uri.parse(profileUri), contentDescription = "Profil et réglages",
                    modifier = Modifier.fillMaxSize(), shape = CircleShape, targetSizeDp = u * PageHeader.SEARCH_HEIGHT,
                )
            } else {
                Icon(CupertinoIcons.PersonFilled, "Profil et réglages", tint = Color(0xFFC4C4CD), modifier = Modifier.size(u * 58f))
            }
        }
    }
}

@Suppress("unused")
private fun Dp.keep() = this
