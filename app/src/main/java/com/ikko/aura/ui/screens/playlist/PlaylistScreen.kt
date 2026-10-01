package com.ikko.aura.ui.screens.playlist

import com.ikko.aura.ui.components.AuraMenu
import com.ikko.aura.data.playlist.FAVORITES_PLAYLIST_ID
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Track
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.data.playlist.UnavailableEntry
import com.ikko.aura.viewmodel.PlaylistDetailViewModel
import com.ikko.aura.ui.components.AuraButton
import com.ikko.aura.ui.components.ButtonTone
import androidx.compose.ui.unit.sp
import com.ikko.aura.ui.components.AuraIconButton

@Composable
fun PlaylistScreen(
    playlistId: String,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    playlistDetailViewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val detail by playlistDetailViewModel.detail.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    GradientScreenScaffold {
        val current = detail
        if (current == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Playlist introuvable",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AuraColors.TextSecondary,
                )
            }
            return@GradientScreenScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = current.name,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = AuraColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${current.tracks.size} titre${if (current.tracks.size > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AuraColors.TextSecondary,
                    )
                }
                // Favoris is kept: no renaming or deleting it, so no menu.
                if (current.id != FAVORITES_PLAYLIST_ID) Box {
                    AuraIconButton(onClick = { menuExpanded = true }) {
                        Icon(CupertinoIcons.MoreVertical, contentDescription = "Plus d'options", modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = AuraMenu.tint().copy(alpha = 0.97f),
                        shape = AuraMenu.Shape,
                        border = AuraMenu.rimStroke,
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Renommer") },
                            onClick = { menuExpanded = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Supprimer la playlist") },
                            onClick = {
                                menuExpanded = false
                                playlistDetailViewModel.delete()
                                navController.popBackStack()
                            },
                        )
                    }
                }
            }

            if (renaming) {
                RenameField(
                    initialName = current.name,
                    onConfirm = { name -> playlistDetailViewModel.rename(name); renaming = false },
                    onCancel = { renaming = false },
                )
            }

            Spacer(Modifier.height(16.dp))

            if (current.tracks.isEmpty() && current.unavailable.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (current.id == FAVORITES_PLAYLIST_ID) {
                            "Touche le cœur dans le lecteur pour ajouter un titre ici"
                        } else {
                            "Ajoute des titres à cette playlist depuis un album ou la bibliothèque"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = AuraColors.TextSecondary,
                    )
                }
            } else {
                if (current.tracks.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AuraButton(
                        onClick = {
                            playerViewModel.playQueue(current.tracks, 0)
                        },
                        tone = ButtonTone.Primary,
                    ) {
                        Icon(CupertinoIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Lire tout", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    AuraButton(
                        onClick = {
                            playerViewModel.playQueue(current.tracks.shuffled(), 0)
                        },
                    ) {
                        Icon(CupertinoIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Mélanger", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
                ) {
                    itemsIndexed(current.tracks, key = { _, track -> track.id }) { index, track ->
                        PlaylistTrackRow(
                            track = track,
                            onClick = {
                                playerViewModel.playQueue(current.tracks, index)
                            },
                            onRemove = { playlistDetailViewModel.removeTrack(track.id) },
                        )
                    }
                    items(current.unavailable, key = { "gone-${it.id}" }) { entry ->
                        UnavailableTrackRow(entry, onRemove = { playlistDetailViewModel.removeTrack(entry.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameField(initialName: String, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initialName) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AuraColors.TextPrimary,
                unfocusedTextColor = AuraColors.TextPrimary,
                focusedBorderColor = AuraColors.TextPrimary,
                unfocusedBorderColor = AuraColors.GlassBorder,
            ),
        )
        AuraIconButton(onClick = { onConfirm(text) }, tone = ButtonTone.Primary, size = 40.dp) {
            Icon(CupertinoIcons.Check, contentDescription = "Valider", modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(6.dp))
        AuraIconButton(onClick = onCancel, size = 40.dp) {
            Icon(CupertinoIcons.Close, contentDescription = "Annuler", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PlaylistTrackRow(track: Track, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            uri = track.artworkUri,
            contentDescription = track.title,
            shape = AuraShapes.SquircleSmall,
            modifier = Modifier.size(48.dp),
            targetSizeDp = 48.dp,
            cacheKey = track.albumId.toString(),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleLarge,
                color = AuraColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = AuraColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(CupertinoIcons.Delete, contentDescription = "Retirer de la playlist", tint = AuraColors.TextTertiary)
        }
    }
}

/** A title whose file is not there right now: kept in the playlist, greyed, and it comes back with the file. */
@Composable
private fun UnavailableTrackRow(entry: UnavailableEntry, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(58.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleLarge,
                color = AuraColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Indisponible · ${entry.artist}",
                style = MaterialTheme.typography.bodyLarge,
                color = AuraColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(CupertinoIcons.Delete, contentDescription = "Retirer de la playlist", tint = AuraColors.TextTertiary)
        }
    }
}
