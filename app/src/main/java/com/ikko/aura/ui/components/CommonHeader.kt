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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
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

/**
 * The one header of Home and Library (the brief's §4), still while the pages slide under it: the page's name, very large
 * (84 px, 800, -0.03 em) at 40, 112, turning from "Accueil" into "Bibliothèque" with [progress] (0 Home, 1 Library); a
 * round search button (96, white at 16 %) at 832 and the profile picture (96) at 944. The name is white at 42 % added to
 * the background (its vibrancy: the mesh tints it), so it must sit in the same layer as the mesh, with nothing that
 * isolates it in between.
 */
@Composable
fun CommonHeader(
    profileUri: String?,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    progress: () -> Float,
    ink: Color,
    modifier: Modifier = Modifier,
    /** How much of the whole header shows (it folds away with the library's scroll, and goes with a pushed page). */
    groupAlpha: () -> Float = { 1f },
    /** How far it is carried to the side (with the menu a page is pushed on). */
    shiftX: () -> Float = { 0f },
    /** False while it is hidden under a page of the library: it stays built (so it is there at once on the way back) but takes no touch. */
    enabled: Boolean = true,
    /** Where the profile picture is, for the trip that opens the settings from it (null: the picture opens them without one). */
    profileAnchor: HeroAnchor? = null,
    profileTripKey: String? = null,
) {
    val u = designUnit()
    Box(modifier.fillMaxWidth().height(u * 236f)) {
        MorphingTitle(
            progress = progress,
            modifier = Modifier
                .offset(x = u * (40f - TITLE_CANVAS_OX), y = u * (112f - TITLE_CANVAS_OY))
                .size(u * TITLE_CANVAS_W, u * TITLE_CANVAS_H)
                // One layer for everything that happens to the title - its 42 %, the fade and the shift of the header, the
                // trips' ripple - and added to the mesh: a layer above it (the header's, a trip's) would isolate it from the
                // mesh and it would be blank until that layer went, then come back all at once.
                .heroRipple(
                    fast = true,
                    blend = androidx.compose.ui.graphics.BlendMode.Plus,
                    baseAlpha = { 0.42f * groupAlpha() },
                    shiftX = shiftX,
                ),
        )
        Box(
            Modifier
                .offset(x = u * 832f, y = u * 112f)
                .heroRipple(fast = true, baseAlpha = groupAlpha, shiftX = shiftX)
                .size(u * 96f)
                .glassSurface(CircleShape)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onSearch),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CupertinoIcons.Search, "Rechercher", tint = ink, modifier = Modifier.size(u * 44f))
        }
        Box(
            Modifier
                .offset(x = u * 944f, y = u * 112f)
                .heroRipple(fast = true, baseAlpha = groupAlpha, shiftX = shiftX)
                .size(u * 96f)
                .then(if (profileAnchor != null && profileTripKey != null) Modifier.heroAnchor(profileTripKey, profileAnchor) else Modifier)
                .clip(CircleShape)
                .background(Color(0xFF1F1F27))
                .clickable(enabled = enabled, onClick = onProfile),
            contentAlignment = Alignment.Center,
        ) {
            if (profileUri != null) {
                AlbumArt(
                    uri = android.net.Uri.parse(profileUri), contentDescription = "Profil et réglages",
                    modifier = Modifier.fillMaxSize(), shape = CircleShape, targetSizeDp = u * 96f,
                )
            } else {
                Icon(CupertinoIcons.PersonFilled, "Profil et réglages", tint = Color(0xFFC4C4CD), modifier = Modifier.size(u * 48f))
            }
        }
    }
}

@Suppress("unused")
private fun Dp.keep() = this
