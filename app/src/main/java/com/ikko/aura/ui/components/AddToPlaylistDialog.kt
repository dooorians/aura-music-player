package com.ikko.aura.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikko.aura.data.playlist.PlaylistSummary
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.viewmodel.PlaylistsViewModel
import androidx.compose.foundation.layout.size

/**
 * Adds [trackIds] (a single track, or a whole album's worth) to whichever playlist the user
 * picks, creating a new one inline if needed.
 */
@Composable
fun AddToPlaylistDialog(
    trackIds: List<String>,
    onDismiss: () -> Unit,
    playlistsViewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists by playlistsViewModel.playlists.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = AuraShapes.SquircleLarge,
            color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.auraMenuSurface(AuraShapes.SquircleLarge),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Ajouter à une playlist",
                    style = MaterialTheme.typography.titleLarge,
                    color = AuraColors.TextPrimary,
                )
                Spacer(Modifier.height(16.dp))

                if (playlists.isEmpty()) {
                    Text(
                        text = "Aucune playlist pour le moment",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraColors.TextSecondary,
                    )
                } else {
                    LazyColumn(modifier = Modifier.height((playlists.size * 56).coerceAtMost(280).dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            PlaylistRow(
                                playlist = playlist,
                                onClick = {
                                    trackIds.forEach { playlistsViewModel.addTrack(playlist.id, it) }
                                    onDismiss()
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("Nouvelle playlist") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AuraColors.TextPrimary,
                            unfocusedTextColor = AuraColors.TextPrimary,
                            focusedBorderColor = AuraColors.TextPrimary,
                            unfocusedBorderColor = AuraColors.GlassBorder,
                        ),
                    )
                    AuraIconButton(
                        onClick = {
                            if (newName.isNotBlank()) {
                                playlistsViewModel.createPlaylistAndAddTracks(newName, trackIds)
                                onDismiss()
                            }
                        },
                        tone = ButtonTone.Neutral,
                        size = 42.dp,
                    ) {
                        Icon(CupertinoIcons.Add, contentDescription = "Créer et ajouter", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: PlaylistSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = playlist.name, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextPrimary)
        Text(
            text = "${playlist.trackCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = AuraColors.TextTertiary,
        )
    }
}
