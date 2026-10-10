package com.ikko.aura.ui.screens.playlist

import com.ikko.aura.ui.components.listTopBlur
import com.ikko.aura.ui.components.AuraMenu
import com.ikko.aura.ui.components.DesignMenu
import com.ikko.aura.ui.components.DesignMenuDivider
import com.ikko.aura.ui.components.DesignMenuRow
import com.ikko.aura.ui.components.glassMenuAnchor
import com.ikko.aura.ui.components.rememberGlassMenuState
import com.ikko.aura.data.playlist.FAVORITES_PLAYLIST_ID
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ikko.aura.ui.components.rememberPlayerAccent
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
    val reco by playlistDetailViewModel.reco.collectAsStateWithLifecycle()
    val recoRefreshing by playlistDetailViewModel.refreshing.collectAsStateWithLifecycle()
    val menuState = rememberGlassMenuState()
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
    // The "F" page is lit by blurred blobs: the weekly card's own mood when it is one, else the page's default (player-lit).
    val immersive = com.ikko.aura.ui.components.LocalImmersivePalette.current
    val immersivePalette = remember(autoKey, ambience) {
        (com.ikko.aura.ui.components.immersiveOfKey(autoKey) ?: ambience?.let { com.ikko.aura.ui.components.immersiveOfAmbience(it) })
            ?.let { com.ikko.aura.ui.components.ImmersivePalette(it.base, it.blobs) }
    }
    DisposableEffect(immersivePalette) {
        immersive.value = immersivePalette
        onDispose { if (immersive.value === immersivePalette) immersive.value = null }
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
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item(key = "header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = com.ikko.aura.ui.components.designUnit() * 40f, end = com.ikko.aura.ui.components.designUnit() * 40f, top = com.ikko.aura.ui.components.designUnit() * 128f, bottom = com.ikko.aura.ui.components.designUnit() * 48f),
                    ) {
                        Column {
                            Text(
                                text = current.name,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = com.ikko.aura.ui.components.designSp(80f),
                                lineHeight = com.ikko.aura.ui.components.designSp(88f),
                                letterSpacing = com.ikko.aura.ui.components.designSp(80f * -0.03f),
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = (if (isFavorites) "Tes favoris" else "Playlist") + " · " + meta,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = com.ikko.aura.ui.components.designSp(34f),
                                lineHeight = com.ikko.aura.ui.components.designSp(42f),
                                color = Color.White.copy(alpha = 0.78f),
                                modifier = Modifier.padding(top = com.ikko.aura.ui.components.designUnit() * 6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // A recommended playlist says when it was made, and can be made again now: the listener's
                            // answers and the profile are kept.
                            reco?.let { page ->
                                val updated = if (page.updatedAtMs > 0) {
                                    "Mis à jour le " + java.time.Instant.ofEpochMilli(page.updatedAtMs)
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.FRENCH))
                                } else "Pas encore mis à jour"
                                Row(
                                    modifier = Modifier.padding(top = com.ikko.aura.ui.components.designUnit() * 10f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(com.ikko.aura.ui.components.designUnit() * 20f),
                                ) {
                                    Text(
                                        text = updated + when (page.status) { "unavailable" -> " · sources indisponibles, liste conservée"; "kept" -> " · pas assez de nouveautés, liste conservée"; else -> "" },
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = com.ikko.aura.ui.components.designSp(30f),
                                        lineHeight = com.ikko.aura.ui.components.designSp(36f),
                                        color = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = if (recoRefreshing) "Mise à jour…" else "Actualiser",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = com.ikko.aura.ui.components.designSp(30f),
                                        lineHeight = com.ikko.aura.ui.components.designSp(36f),
                                        color = Color.White,
                                        modifier = Modifier
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .clickable(enabled = !recoRefreshing) { playlistDetailViewModel.refresh() }
                                            .padding(horizontal = com.ikko.aura.ui.components.designUnit() * 6f, vertical = com.ikko.aura.ui.components.designUnit() * 6f),
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
                        Spacer(Modifier.height(com.ikko.aura.ui.components.designUnit() * 52f))
                        if (current.tracks.isNotEmpty()) Row(
                            horizontalArrangement = Arrangement.spacedBy(com.ikko.aura.ui.components.designUnit() * 25f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ShuffleButton(onClick = { playerViewModel.playShuffled(current.tracks, context = autoKey) })
                            PlayAllButton(
                                onClick = { playerViewModel.playQueue(current.tracks, 0, context = autoKey) },
                                label = "Lire",
                                modifier = Modifier.weight(1f),
                                inkOverride = com.ikko.aura.ui.components.immersiveInkOfKey(autoKey),
                            )
                            // Favoris is kept: no renaming or deleting it, so no menu.
                            if (!isFavorites) Box(Modifier.glassMenuAnchor(menuState)) {
                                CircleAction(onClick = { menuState.open() }) {
                                    Icon(CupertinoIcons.MoreVertical, contentDescription = "Plus d'options", tint = Color.White, modifier = Modifier.size(com.ikko.aura.ui.components.designUnit() * 44f))
                                }
                                // The design's playlist menu (600 px wide, the page's colour at 24 %).
                                DesignMenu(state = menuState, width = 240.dp, shade = 0.24f) {
                                    DesignMenuRow("Renommer", { menuState.close(); renaming = true }, icon = CupertinoIcons.Pencil)
                                    DesignMenuDivider()
                                    DesignMenuRow(
                                        "Supprimer la playlist",
                                        {
                                            menuState.close()
                                            playlistDetailViewModel.delete()
                                            navController.popBackStack()
                                        },
                                        icon = CupertinoIcons.Delete,
                                        danger = true,
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
                            modifier = Modifier.padding(horizontal = com.ikko.aura.ui.components.designUnit() * 40f, vertical = 24.dp),
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
                        onClick = { playerViewModel.playQueue(current.tracks, index, context = autoKey) },
                        onLongClick = { menu.show(track) },
                        leading = {
                            AlbumArt(
                                uri = track.artworkUri,
                                contentDescription = null,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(com.ikko.aura.ui.components.designUnit() * 32f),
                                modifier = Modifier.size(com.ikko.aura.ui.components.designUnit() * 130f),
                                targetSizeDp = com.ikko.aura.ui.components.designUnit() * 130f,
                                cacheKey = track.albumId.toString(),
                            )
                        },
                        extraLine = reco?.reasons?.get(track.id),
                        trailing = {
                            if (reco != null) {
                                RecoRowMenu(
                                    track = track,
                                    onDislike = { playlistDetailViewModel.dislike(track) },
                                    onKnown = { playlistDetailViewModel.markKnown(track) },
                                    onHide = { playlistDetailViewModel.hide(track) },
                                    onBlock = { playlistDetailViewModel.blockArtist(track.artist) },
                                )
                            } else {
                            IconButton(onClick = { playlistDetailViewModel.removeTrack(track.id) }, modifier = Modifier.size(com.ikko.aura.ui.components.designUnit() * 88f)) {
                                Icon(CupertinoIcons.Delete, contentDescription = "Retirer de la playlist", tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(com.ikko.aura.ui.components.designUnit() * 36f))
                            }
                            }
                        },
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

/** A title whose file is not there right now: kept in the playlist, greyed, and it comes back with the file. */
@Composable
private fun UnavailableTrackRow(entry: UnavailableEntry, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_PADDING, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(62.dp))
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

/**
 * The listener's answers about a recommended song, in the app's own menu: "Je n'aime pas" (this song is not proposed
 * again; its artist is nudged down in this playlist only), "Déjà connu" (not a rejection), "Masquer" and blocking the
 * artist (every playlist, collaborations included).
 */
@Composable
private fun RecoRowMenu(
    track: Track,
    onDislike: () -> Unit,
    onKnown: () -> Unit,
    onHide: () -> Unit,
    onBlock: () -> Unit,
) {
    val state = rememberGlassMenuState()
    androidx.compose.foundation.layout.Box(Modifier.glassMenuAnchor(state)) {
        IconButton(onClick = { state.open() }, modifier = Modifier.size(com.ikko.aura.ui.components.designUnit() * 88f)) {
            Icon(
                CupertinoIcons.MoreVertical, contentDescription = "Plus d'options",
                tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(com.ikko.aura.ui.components.designUnit() * 40f),
            )
        }
        DesignMenu(state = state, width = 260.dp, shade = 0.24f) {
            DesignMenuRow("Je n'aime pas", { state.close(); onDislike() }, icon = CupertinoIcons.Close)
            DesignMenuRow("Déjà connu", { state.close(); onKnown() }, icon = CupertinoIcons.Check)
            DesignMenuRow("Masquer ce titre", { state.close(); onHide() }, icon = CupertinoIcons.Delete)
            DesignMenuDivider()
            DesignMenuRow("Bloquer ${track.artist.take(24)}", { state.close(); onBlock() }, icon = CupertinoIcons.Person, danger = true)
        }
    }
}
