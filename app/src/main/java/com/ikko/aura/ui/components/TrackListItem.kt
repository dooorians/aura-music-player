package com.ikko.aura.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ikko.aura.data.model.Track
import androidx.compose.ui.graphics.Color
import com.ikko.aura.ui.theme.LibraryTypography
import com.ikko.aura.ui.theme.LocalCoverColors
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes

private val ROW_PRESS_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListItem(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artSize: Dp = 52.dp,
    onLongClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The press highlight is cut to a rounded shape: uncut, it is the row's own rectangle, a dark
            // square with sharp corners behind two rounded thumbnails.
            .clip(ROW_PRESS_SHAPE)
            // combinedClickable only where a long press is actually handled: it makes every tap
            // wait out the long-press timeout before it reports, and a row with no menu behind it
            // has no reason to pay that.
            .let { base ->
                if (onLongClick == null) {
                    base.clickable(onClick = onClick)
                } else {
                    base.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                }
            }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            uri = track.artworkUri,
            contentDescription = track.album,
            shape = AuraShapes.SquircleSmall,
            modifier = Modifier.size(artSize),
            targetSizeDp = artSize,
            cacheKey = track.albumId.toString(),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = LibraryTypography.AlbumTitle,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.artist,
                    style = LibraryTypography.AlbumArtist,
                    color = LocalCoverColors.current.tint.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(8.dp))
            trailingContent()
        }
    }
}
