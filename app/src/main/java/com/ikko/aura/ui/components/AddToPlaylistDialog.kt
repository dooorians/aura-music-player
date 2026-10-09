package com.ikko.aura.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikko.aura.data.playlist.PlaylistSummary
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.PlaylistsViewModel

/**
 * "Ajouter à la playlist": a tall sheet with a close button and the title on top, a search field, the playlists last
 * used, then all of them; a round + at the bottom makes a new one holding the songs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistDialog(
    trackIds: List<String>,
    onDismiss: () -> Unit,
    playlistsViewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists by playlistsViewModel.playlists.collectAsStateWithLifecycle()
    val recentIds by playlistsViewModel.recentIds.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val shown = remember(playlists, query) {
        val q = query.trim()
        if (q.isEmpty()) playlists else playlists.filter { it.name.contains(q, ignoreCase = true) }
    }
    val recent = remember(playlists, recentIds, query) {
        if (query.isNotBlank()) emptyList() else recentIds.mapNotNull { id -> playlists.firstOrNull { it.id == id } }.take(3)
    }
    fun add(playlist: PlaylistSummary) {
        trackIds.forEach { playlistsViewModel.addTrack(playlist.id, it) }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AuraMenu.tint().copy(alpha = 0.97f),
        contentColor = AuraColors.TextPrimary,
        shape = AuraMenu.SheetShape,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = null,
    ) {
        Box(Modifier.fillMaxHeight(0.92f)) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AuraIconButton(onClick = onDismiss, size = 44.dp) {
                        Icon(CupertinoIcons.Close, contentDescription = "Fermer", modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = "Ajouter à la playlist",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AuraColors.TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(44.dp))
                }
                SearchField(query, { query = it })
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                ) {
                    if (recent.isNotEmpty()) {
                        item(key = "h-recent") { SectionTitle("Récentes") }
                        items(recent, key = { "r${it.id}" }) { PlaylistRow(it) { add(it) } }
                    }
                    item(key = "h-all") { SectionTitle("Toutes les playlists") }
                    if (shown.isEmpty()) {
                        item(key = "none") {
                            Text(
                                text = if (playlists.isEmpty()) "Aucune playlist pour le moment" else "Aucun résultat",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AuraColors.TextSecondary,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(shown, key = { "a${it.id}" }) { PlaylistRow(it) { add(it) } }
                }
            }
            Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 24.dp)) {
                AuraIconButton(onClick = { creating = true }, tone = ButtonTone.Primary, size = 56.dp) {
                    Icon(CupertinoIcons.Add, contentDescription = "Nouvelle playlist", modifier = Modifier.size(24.dp))
                }
            }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Nouvelle playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("Nom") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AuraColors.TextPrimary,
                        unfocusedTextColor = AuraColors.TextPrimary,
                        focusedBorderColor = AuraColors.TextPrimary,
                        unfocusedBorderColor = AuraColors.GlassBorder,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        playlistsViewModel.createPlaylistAndAddTracks(newName, trackIds)
                        creating = false
                        onDismiss()
                    }
                }) { Text("Créer") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CupertinoIcons.Search, contentDescription = null, tint = AuraColors.TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Rechercher dans les playlists", color = AuraColors.TextTertiary, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AuraColors.TextPrimary),
                cursorBrush = SolidColor(AuraColors.TextPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = AuraColors.TextPrimary,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun PlaylistRow(playlist: PlaylistSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playlist.isFavorites) CupertinoIcons.HeartFilled else CupertinoIcons.PlaylistMusic,
                contentDescription = null,
                tint = AuraColors.TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${playlist.trackCount} titre${if (playlist.trackCount > 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = AuraColors.TextTertiary,
            )
        }
    }
}
