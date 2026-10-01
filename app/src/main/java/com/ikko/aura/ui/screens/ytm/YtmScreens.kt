package com.ikko.aura.ui.screens.ytm

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Track
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.source.ytm.YtmAlbumRef
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.AuraButton
import com.ikko.aura.ui.components.ButtonTone
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.components.TrackListItem
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.YtmAlbumViewModel
import com.ikko.aura.viewmodel.YtmArtistViewModel

/** An album of YouTube Music: its cover, its songs in order, and the keys to play or keep them. */
@Composable
fun YtmAlbumScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    viewModel: YtmAlbumViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val kept by playerViewModel.keptStreamedIds.collectAsStateWithLifecycle()
    GradientScreenScaffold {
        MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
            when {
                state.loading -> Centered("Chargement…")
                state.failed -> Failed(onRetry = viewModel::load)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = LocalPlayerPillHeight.current + 12.dp),
                ) {
                    item {
                        Row(modifier = Modifier.padding(top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            AlbumArt(
                                uri = state.tracks.firstOrNull()?.artworkUri,
                                contentDescription = state.title,
                                shape = AuraShapes.SquircleLarge,
                                modifier = Modifier.size(120.dp),
                                targetSizeDp = 120.dp,
                                cacheKey = state.tracks.firstOrNull()?.albumId?.toString(),
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(state.title, style = MaterialTheme.typography.headlineMedium, color = AuraColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(state.artist, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${state.tracks.size} titre${if (state.tracks.size > 1) "s" else ""} · YouTube Music",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AuraColors.TextSecondary,
                                )
                            }
                        }
                    }
                    item {
                        val allKept = state.tracks.all { it.id in kept }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AuraButton(onClick = { playerViewModel.playQueue(state.tracks, 0) }, tone = ButtonTone.Primary) {
                                Icon(CupertinoIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Lire tout", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                            AuraButton(onClick = { playerViewModel.playQueue(state.tracks.shuffled(), 0) }) {
                                Icon(CupertinoIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Mélanger", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = {
                                if (allKept) playerViewModel.unkeepStreamed(state.tracks) else playerViewModel.keepStreamed(state.tracks)
                            }) {
                                Icon(
                                    if (allKept) CupertinoIcons.Check else CupertinoIcons.Add,
                                    contentDescription = if (allKept) "Retirer l'album de la bibliothèque" else "Ajouter l'album à la bibliothèque",
                                    tint = AuraColors.TextPrimary,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    itemsIndexed(state.tracks, key = { _, t -> t.id }) { index, track ->
                        StreamedRow(track, track.id in kept, playerViewModel, menu::show) { playerViewModel.playQueue(state.tracks, index) }
                    }
                }
            }
        }
    }
}

/** An artist of YouTube Music, by name: their albums, then their songs. */
@Composable
fun YtmArtistScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    viewModel: YtmArtistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val kept by playerViewModel.keptStreamedIds.collectAsStateWithLifecycle()
    GradientScreenScaffold {
        MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
            when {
                state.loading -> Centered("Chargement…")
                state.failed -> Failed(onRetry = viewModel::load)
                state.tracks.isEmpty() && state.albums.isEmpty() -> Centered("Rien trouvé pour ${state.name}")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
                ) {
                    item {
                        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
                            Text(state.name, style = MaterialTheme.typography.headlineLarge, color = AuraColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("YouTube Music", style = MaterialTheme.typography.bodyMedium, color = AuraColors.TextSecondary)
                        }
                    }
                    if (state.tracks.isNotEmpty()) item {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AuraButton(onClick = { playerViewModel.playQueue(state.tracks, 0) }, tone = ButtonTone.Primary) {
                                Icon(CupertinoIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Lire tout", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                            AuraButton(onClick = { playerViewModel.playQueue(state.tracks.shuffled(), 0) }) {
                                Icon(CupertinoIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Mélanger", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (state.albums.isNotEmpty()) {
                        item { Section("Albums") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(state.albums, key = { it.id }) { album ->
                                    YtmAlbumCard(album) { navController.navigate(AuraDestination.YtmAlbumPattern.route(album.id)) }
                                }
                            }
                        }
                    }
                    if (state.tracks.isNotEmpty()) {
                        item { Section("Titres") }
                        itemsIndexed(state.tracks, key = { _, t -> t.id }) { index, track ->
                            StreamedRow(track, track.id in kept, playerViewModel, menu::show, Modifier.padding(horizontal = 20.dp)) {
                                playerViewModel.playQueue(state.tracks, index)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A streamed album as a card: its cover and its name. Shared with the search. */
@Composable
fun YtmAlbumCard(album: YtmAlbumRef, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier = modifier.width(120.dp).clickable(onClick = onClick)) {
        AlbumArt(
            uri = album.thumbnailUrl?.let(android.net.Uri::parse),
            contentDescription = album.title,
            modifier = Modifier.size(120.dp),
            targetSizeDp = 120.dp,
            cacheKey = "ytmalbum:${album.id}",
        )
        Text(album.title, style = MaterialTheme.typography.labelLarge, color = AuraColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(album.artist, style = MaterialTheme.typography.bodySmall, color = AuraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StreamedRow(
    track: Track,
    kept: Boolean,
    playerViewModel: PlayerViewModel,
    onLongClick: (Track) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TrackListItem(
        track = track,
        onClick = onClick,
        onLongClick = { onLongClick(track) },
        modifier = modifier,
        trailingContent = {
            IconButton(onClick = { if (kept) playerViewModel.unkeepStreamed(listOf(track)) else playerViewModel.keepStreamed(listOf(track)) }) {
                Icon(
                    if (kept) CupertinoIcons.Check else CupertinoIcons.Add,
                    contentDescription = if (kept) "Retirer de la bibliothèque" else "Ajouter à la bibliothèque",
                    tint = AuraColors.TextPrimary,
                )
            }
        },
    )
}

@Composable
private fun Section(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = AuraColors.TextPrimary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).padding(top = 8.dp),
    )
}

@Composable
private fun Centered(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = AuraColors.TextSecondary)
    }
}

@Composable
private fun Failed(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("YouTube Music ne répond pas pour le moment", style = MaterialTheme.typography.bodyLarge, color = AuraColors.TextSecondary)
            Spacer(Modifier.height(12.dp))
            AuraButton(onClick = onRetry) { Text("Réessayer", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
