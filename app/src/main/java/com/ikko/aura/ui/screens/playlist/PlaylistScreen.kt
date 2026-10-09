package com.ikko.aura.ui.screens.playlist

import com.ikko.aura.ui.components.listTopBlur
import com.ikko.aura.ui.components.AuraMenu
import com.ikko.aura.data.playlist.FAVORITES_PLAYLIST_ID
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import com.ikko.aura.ui.components.albumThemeFromBitmap
import com.ikko.aura.ui.screens.album.AlbumGround
import com.ikko.aura.ui.screens.album.StatusBarScrim
import com.ikko.aura.ui.screens.home.cardAmbienceOf
import com.ikko.aura.ui.screens.home.rememberCardArt
import com.ikko.aura.ui.theme.LocalPageTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.screens.album.AlbumTrackRow
import com.ikko.aura.ui.screens.album.ART_SHAPE
import com.ikko.aura.ui.screens.album.ART_SIZE
import com.ikko.aura.ui.screens.album.CircleAction
import com.ikko.aura.ui.screens.album.HEADER_TOP_BELOW_STATUS
import com.ikko.aura.ui.screens.album.PAGE_PADDING
import com.ikko.aura.ui.screens.album.PlayAllButton
import com.ikko.aura.ui.screens.album.ShuffleButton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

/**
 * A playlist, laid out as an album page is (the albums are the standard): the cover, the title and a line of details at the
 * top, the play / shuffle buttons and the menu under them, then numbered rows with their durations, the playing one marked.
 * Removing a title is the small trash mark at the end of its row; renaming and deleting are in the menu.
 */
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
    val nowPlaying by remember(playerViewModel) {
        playerViewModel.uiState.map { it.currentTrack?.id to it.isPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = playerViewModel.uiState.value.let { it.currentTrack?.id to it.isPlaying },
    )
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // A weekly playlist opened from a Home card wears that card: its picture is the cover, and the page's background
    // (the aurora at the top, the flat colour below) is made from it, as an album's is made from its sleeve.
    val autoKey by playlistDetailViewModel.autoKey.collectAsStateWithLifecycle()
    val ambience = autoKey?.let { cardAmbienceOf(it) }
    val cardArt = ambience?.let { rememberCardArt(it) }
    val theme = remember(cardArt, ambience) {
        cardArt?.let { albumThemeFromBitmap(it, seed = ambience!!.ordinal * 31 + 7) }
    }
    val pageTheme = LocalPageTheme.current
    DisposableEffect(theme) {
        theme?.let { pageTheme.value = it }
        onDispose { if (pageTheme.value === theme) pageTheme.value = null }
    }
    val listState = rememberLazyListState()
    val accent = rememberUpdatedState(theme?.accent ?: AuraColors.TextPrimary.copy(alpha = 0.9f))

    MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
        GradientScreenScaffold(applyStatusBarInset = false) {
            val current = detail
            if (current == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Playlist introuvable", style = MaterialTheme.typography.bodyLarge, color = AuraColors.TextSecondary)
                }
                return@GradientScreenScaffold
            }
            val isFavorites = current.id == FAVORITES_PLAYLIST_ID
            val minutes = remember(current.tracks) { (current.tracks.sumOf { it.durationMs } / 60_000L).toInt() }
            val meta = remember(current.tracks) {
                val count = "${current.tracks.size} titre${if (current.tracks.size > 1) "s" else ""}"
                val length = if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}" else "$minutes min"
                if (current.tracks.isEmpty()) count else "$count · $length"
            }

            Box(Modifier.fillMaxSize()) {
            if (theme != null) AlbumGround(ground = theme.solid, listState = listState)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = PAGE_PADDING, end = PAGE_PADDING, top = statusTop + HEADER_TOP_BELOW_STATUS, bottom = 18.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                modifier = Modifier.shadow(
                                    elevation = 16.dp,
                                    shape = ART_SHAPE,
                                    ambientColor = Color.Black.copy(alpha = 0.45f),
                                    spotColor = Color.Black.copy(alpha = 0.55f),
                                ),
                            ) {
                                // A weekly playlist has no cover: its colours are the page's, and that is all of it.
                                if (ambience == null) {
                                    AlbumArt(
                                        uri = current.tracks.firstOrNull()?.artworkUri,
                                        contentDescription = current.name,
                                        shape = ART_SHAPE,
                                        modifier = Modifier.size(ART_SIZE).border(1.dp, Color.White.copy(alpha = 0.10f), ART_SHAPE),
                                        targetSizeDp = ART_SIZE,
                                        cacheKey = current.tracks.firstOrNull()?.albumId?.toString(),
                                    )
                                }
                            }
                            if (ambience == null) Spacer(Modifier.width(18.dp))
                            Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                                Text(
                                    text = current.name,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 24.sp,
                                    lineHeight = 28.sp,
                                    color = AuraColors.TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    text = if (isFavorites) "Tes favoris" else "Playlist",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AuraColors.TextSecondary,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuraColors.TextSecondary.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (renaming) {
                            RenameField(
                                initialName = current.name,
                                onConfirm = { name -> playlistDetailViewModel.rename(name); renaming = false },
                                onCancel = { renaming = false },
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (current.tracks.isNotEmpty()) {
                                PlayAllButton(onClick = { playerViewModel.playQueue(current.tracks, 0) }, tint = null)
                                ShuffleButton(onClick = { playerViewModel.playShuffled(current.tracks) })
                            }
                            Spacer(Modifier.weight(1f))
                            // Favoris is kept: no renaming or deleting it, so no menu.
                            if (!isFavorites) Box {
                                CircleAction(onClick = { menuExpanded = true }) {
                                    Icon(CupertinoIcons.MoreVertical, contentDescription = "Plus d'options", tint = Color.White, modifier = Modifier.size(20.dp))
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
                                    DropdownMenuItem(text = { Text("Renommer") }, onClick = { menuExpanded = false; renaming = true })
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
                    }
                }
                if (current.tracks.isEmpty() && current.unavailable.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = if (isFavorites) "Touche le cœur dans le lecteur pour ajouter un titre ici"
                            else "Ajoute des titres à cette playlist depuis un album ou la bibliothèque",
                            style = MaterialTheme.typography.bodyLarge,
                            color = AuraColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = PAGE_PADDING + 12.dp, vertical = 24.dp),
                        )
                    }
                }
                itemsIndexed(current.tracks, key = { _, track -> track.id }) { index, track ->
                    AlbumTrackRow(
                        number = index + 1,
                        track = track,
                        albumArtist = "",
                        isCurrent = nowPlaying.first == track.id,
                        isPlaying = nowPlaying.second,
                        accent = accent,
                        onClick = { playerViewModel.playQueue(current.tracks, index) },
                        onLongClick = { menu.show(track) },
                        trailing = {
                            IconButton(onClick = { playlistDetailViewModel.removeTrack(track.id) }, modifier = Modifier.size(32.dp)) {
                                Icon(CupertinoIcons.Delete, contentDescription = "Retirer de la playlist", tint = AuraColors.TextTertiary, modifier = Modifier.size(18.dp))
                            }
                        },
                    )
                }
                items(current.unavailable, key = { "gone-${it.id}" }) { entry ->
                    UnavailableTrackRow(entry, onRemove = { playlistDetailViewModel.removeTrack(entry.id) })
                }
            }
            if (theme != null) StatusBarScrim(solid = theme.solid, listState = listState)
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

/** A title whose file is not there right now: kept in the playlist, greyed, and it comes back with the file. */
@Composable
private fun UnavailableTrackRow(entry: UnavailableEntry, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_PADDING + 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(30.dp))
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
