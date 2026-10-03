package com.ikko.aura.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
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
import com.ikko.aura.source.ytm.DownloadEntry
import com.ikko.aura.source.ytm.DownloadState
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.CupertinoIcons

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
    /** A song streamed from YouTube Music: no file to delete, no album or artist page of its own. */
    streamed: Boolean = false,
    keptInLibrary: Boolean = false,
    onToggleKeep: () -> Unit = {},
    streamedHasAlbum: Boolean = false,
    /** An album downloaded from another source: its menu deletes the downloads, not files of the system's. */
    streamedAlbum: Boolean = false,
    /** The streamed song's download, when it has one. */
    download: DownloadEntry? = null,
    onDownload: () -> Unit = {},
    onRemoveDownload: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AuraMenu.tint().copy(alpha = 0.97f),
        contentColor = AuraColors.TextPrimary,
        shape = AuraMenu.SheetShape,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f),
        dragHandle = {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f)),
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            Header(target)
            Spacer(Modifier.height(6.dp))

            ContextAction(CupertinoIcons.Play, "Lire ensuite") { onPlayNext(); onDismiss() }
            ContextAction(CupertinoIcons.Queue, "Ajouter à la file d'attente") { onAddToQueue(); onDismiss() }
            ContextAction(CupertinoIcons.PlaylistAdd, "Ajouter à une playlist") { onAddToPlaylist() }

            if (streamed) {
                if (streamedHasAlbum) ContextAction(CupertinoIcons.Album, "Voir l'album") { onOpenAlbum(); onDismiss() }
                ContextAction(CupertinoIcons.Person, "Voir l'artiste") { onOpenArtist(); onDismiss() }
                val downloaded = download?.state == DownloadState.DONE
                when (download?.state) {
                    // A downloaded song is a file of the library: one action takes both away.
                    DownloadState.DONE ->
                        ContextAction(CupertinoIcons.Delete, "Supprimer le titre et son téléchargement", tint = AuraColors.AccentPink) { onRemoveDownload(); onDismiss() }
                    DownloadState.QUEUED, DownloadState.DOWNLOADING -> Unit
                    else -> ContextAction(CupertinoIcons.ArrowDownCircle, "Télécharger") { onDownload(); onDismiss() }
                }
                if (!downloaded) {
                    if (keptInLibrary) {
                        ContextAction(CupertinoIcons.Delete, "Retirer de la bibliothèque", tint = AuraColors.AccentPink) { onToggleKeep(); onDismiss() }
                    } else {
                        ContextAction(CupertinoIcons.Add, "Ajouter à la bibliothèque") { onToggleKeep(); onDismiss() }
                    }
                }
                return@Column
            }

            if (target is LibraryContextTarget.TrackTarget) {
                ContextAction(CupertinoIcons.Album, "Voir l'album") { onOpenAlbum(); onDismiss() }
            }
            ContextAction(CupertinoIcons.Person, "Voir l'artiste") { onOpenArtist(); onDismiss() }

            ContextAction(
                icon = CupertinoIcons.Delete,
                label = when (target) {
                    is LibraryContextTarget.TrackTarget -> "Supprimer le fichier"
                    is LibraryContextTarget.AlbumTarget -> if (streamedAlbum) "Supprimer les téléchargements" else "Supprimer l'album"
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
