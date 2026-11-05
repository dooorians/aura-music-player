package com.ikko.aura.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.ikko.aura.ui.theme.CoverText
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes

private val ROW_PRESS_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)

// The "F" design's row: 170 px high, a 130 px cover with 32 px corners, 30 px to the text (design px, see designUnit()).
private val ROW_TITLE = androidx.compose.ui.text.TextStyle(
    fontFamily = com.ikko.aura.ui.theme.AuraFontFamily, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
)


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListItem(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artSize: Dp = designUnit() * 130f,
    onLongClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    // Under the title. The artist, except where the page is already about the artist (see ArtistScreen).
    subtitle: String = track.artist,
    isPlayingTrack: Boolean = false,
) {
    val accent = rememberPlayerAccent()
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
            .heightIn(min = designUnit() * 170f)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            uri = track.artworkUri,
            contentDescription = track.album,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(designUnit() * 32f),
            modifier = Modifier.size(artSize),
            targetSizeDp = artSize,
            cacheKey = track.albumId.toString(),
        )
        Spacer(Modifier.width(designUnit() * 30f))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = ROW_TITLE.copy(fontSize = designSp(40f), lineHeight = designSp(48f)),
                color = if (isPlayingTrack) accent else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverText(
                    text = subtitle,
                    style = ROW_TITLE.copy(fontSize = designSp(32f), lineHeight = designSp(40f),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    alpha = 0.78f,
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
