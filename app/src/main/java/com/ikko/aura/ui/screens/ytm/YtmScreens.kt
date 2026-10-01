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
import com.ikko.aura.ui.screens.album.AlbumPageContent
import com.ikko.aura.ui.screens.album.StreamedAlbumControls
import com.ikko.aura.ui.screens.artist.ArtistPageContent
import com.ikko.aura.viewmodel.RemoteAlbumViewModel
import com.ikko.aura.viewmodel.RemoteArtistViewModel
import com.ikko.aura.viewmodel.YtmAlbumViewModel
import com.ikko.aura.viewmodel.YtmArtistViewModel

/** An album of YouTube Music, drawn by the very page a local album uses. */
@Composable
fun YtmAlbumScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    viewModel: YtmAlbumViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val page by viewModel.page.collectAsStateWithLifecycle()
    val kept by playerViewModel.keptStreamedIds.collectAsStateWithLifecycle()
    val downloads by playerViewModel.downloadStates.collectAsStateWithLifecycle()
    when {
        state.loading -> GradientScreenScaffold { Centered("Chargement…") }
        state.failed -> GradientScreenScaffold { Failed(onRetry = viewModel::load) }
        else -> {
            val tracks = uiState.tracks
            AlbumPageContent(
                uiState = uiState,
                page = page,
                navController = navController,
                playerViewModel = playerViewModel,
                isTransitioning = false,
                onFavorite = viewModel::toggleFavorite,
                streamed = StreamedAlbumControls(
                    kept = tracks.isNotEmpty() && tracks.all { it.id in kept },
                    onToggleKeep = {
                        if (tracks.all { it.id in kept }) playerViewModel.unkeepStreamed(tracks) else playerViewModel.keepStreamed(tracks)
                    },
                    downloads = downloads,
                    onDownloadAlbum = { playerViewModel.downloadStreamed(tracks) },
                    onRemoveAlbumDownloads = { playerViewModel.removeDownloads(tracks) },
                    onDownloadTrack = { playerViewModel.downloadStreamed(listOf(it)) },
                    prepare = { playerViewModel.rememberForUse(tracks) },
                ),
            )
        }
    }
}

/** An artist of YouTube Music, drawn by the very page a local artist uses. */
@Composable
fun YtmArtistScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    viewModel: YtmArtistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    when {
        state.loading -> GradientScreenScaffold { Centered("Chargement…") }
        state.failed -> GradientScreenScaffold { Failed(onRetry = viewModel::load) }
        state.tracks.isEmpty() && state.albums.isEmpty() -> GradientScreenScaffold { Centered("Rien trouvé pour ${state.name}") }
        else -> ArtistPageContent(uiState, navController, playerViewModel)
    }
}

/** An album of an added source, drawn by the very page a local album uses. */
@Composable
fun RemoteAlbumScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    viewModel: RemoteAlbumViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val page by viewModel.page.collectAsStateWithLifecycle()
    val kept by playerViewModel.keptStreamedIds.collectAsStateWithLifecycle()
    val downloads by playerViewModel.downloadStates.collectAsStateWithLifecycle()
    when {
        state.loading -> GradientScreenScaffold { Centered("Chargement…") }
        state.failed -> GradientScreenScaffold { Failed(onRetry = viewModel::load) }
        else -> {
            val tracks = uiState.tracks
            AlbumPageContent(
                uiState = uiState,
                page = page,
                navController = navController,
                playerViewModel = playerViewModel,
                isTransitioning = false,
                onFavorite = viewModel::toggleFavorite,
                streamed = StreamedAlbumControls(
                    kept = tracks.isNotEmpty() && tracks.all { it.id in kept },
                    onToggleKeep = {
                        if (tracks.all { it.id in kept }) playerViewModel.unkeepStreamed(tracks) else playerViewModel.keepStreamed(tracks)
                    },
                    downloads = downloads,
                    onDownloadAlbum = { playerViewModel.downloadStreamed(tracks) },
                    onRemoveAlbumDownloads = { playerViewModel.removeDownloads(tracks) },
                    onDownloadTrack = { playerViewModel.downloadStreamed(listOf(it)) },
                    prepare = { playerViewModel.rememberForUse(tracks) },
                ),
            )
        }
    }
}

/** An artist of an added source, drawn by the very page a local artist uses. */
@Composable
fun RemoteArtistScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    viewModel: RemoteArtistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    when {
        state.loading -> GradientScreenScaffold { Centered("Chargement…") }
        state.failed -> GradientScreenScaffold { Failed(onRetry = viewModel::load) }
        state.tracks.isEmpty() && state.albums.isEmpty() -> GradientScreenScaffold { Centered("Rien trouvé pour ${state.name}") }
        else -> ArtistPageContent(uiState, navController, playerViewModel)
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
