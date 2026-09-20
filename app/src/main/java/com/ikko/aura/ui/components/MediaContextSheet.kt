package com.ikko.aura.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Track
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.PhosphorIcons

/** What a long press landed on. The menu's contents follow from it. */
sealed interface LibraryContextTarget {
    data class TrackTarget(val track: Track) : LibraryContextTarget
    data class AlbumTarget(val album: Album, val tracks: List<Track>) : LibraryContextTarget
}

/**
 * Everything you can do to a track or an album without leaving the list.
 *
 * A bottom sheet rather than a dropdown anchored to the row: on a 432dp-wide panel a dropdown
 * would cover the thing it is about, and half of these entries need two words of French to be
 * unambiguous ("ajouter à la file d'attente") which a dropdown has no room for.
 *
 * Deletion is last and red, and it is the only entry that leads anywhere destructive — it hands
 * off to the system's own confirmation, which is both required on modern Android for media the
 * app did not write and the right place for that decision to be made.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaContextSheet(
    target: LibraryContextTarget,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AuraColors.SurfaceVariantDark,
        contentColor = AuraColors.TextPrimary,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            Header(target)
            Spacer(Modifier.height(6.dp))

            ContextAction(PhosphorIcons.Play, "Lire ensuite") { onPlayNext(); onDismiss() }
            ContextAction(PhosphorIcons.Queue, "Ajouter à la file d'attente") { onAddToQueue(); onDismiss() }
            ContextAction(PhosphorIcons.PlaylistAdd, "Ajouter à une playlist") { onAddToPlaylist() }

            if (target is LibraryContextTarget.TrackTarget) {
                ContextAction(PhosphorIcons.Album, "Voir l'album") { onOpenAlbum(); onDismiss() }
            }
            ContextAction(PhosphorIcons.Person, "Voir l'artiste") { onOpenArtist(); onDismiss() }

            ContextAction(
                icon = PhosphorIcons.Delete,
                label = when (target) {
                    is LibraryContextTarget.TrackTarget -> "Supprimer le fichier"
                    is LibraryContextTarget.AlbumTarget -> "Supprimer l'album"
                },
                tint = AuraColors.AccentPink,
            ) { onDelete() }
        }
    }
}

@Composable
private fun Header(target: LibraryContextTarget) {
    val (artworkUri, title, subtitle, cacheKey) = when (target) {
        is LibraryContextTarget.TrackTarget ->
            Header4(target.track.artworkUri, target.track.title, target.track.artist, target.track.albumId.toString())
        is LibraryContextTarget.AlbumTarget ->
            Header4(
                target.album.artworkUri,
                target.album.title,
                "${target.album.artist} · ${target.album.trackCount} titres",
                target.album.id.toString(),
            )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            uri = artworkUri,
            contentDescription = title,
            shape = AuraShapes.SquircleSmall,
            modifier = Modifier.size(46.dp),
            targetSizeDp = 46.dp,
            cacheKey = cacheKey,
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AuraColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AuraColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class Header4(val uri: android.net.Uri?, val title: String, val subtitle: String, val cacheKey: String)

@Composable
private fun ContextAction(
    icon: ImageVector,
    label: String,
    tint: Color = AuraColors.TextPrimary,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
