package com.ikko.aura.ui.screens.album

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Track
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.AddToPlaylistDialog
import com.ikko.aura.ui.components.collapsingHeight
import com.ikko.aura.ui.components.rememberCollapseOffset
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.components.HiResBadge
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.PhosphorIcons
import com.ikko.aura.viewmodel.AlbumViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import kotlin.math.roundToInt

private val ALBUM_HEADER_HEIGHT = 154.dp

@Composable
fun AlbumScreen(
    albumId: String,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    albumViewModel: AlbumViewModel = hiltViewModel(),
) {
    val uiState by albumViewModel.uiState.collectAsStateWithLifecycle()
    var showAddToPlaylist by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val headerHeightPx = with(density) { ALBUM_HEADER_HEIGHT.toPx() }
    var headerOffsetPx by rememberCollapseOffset()
    // Same rule as the top nav row: collapse while scrolling down, and only come back once the
    // list has run out of upward scroll (leftover in onPostScroll), so the header stays out of the
    // way until you are actually back at the top rather than reappearing on the first reversal.
    val headerScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                val newOffset = (headerOffsetPx + available.y).coerceAtLeast(-headerHeightPx)
                val consumed = newOffset - headerOffsetPx
                headerOffsetPx = newOffset
                return Offset(0f, consumed)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y <= 0f) return Offset.Zero
                val newOffset = (headerOffsetPx + available.y).coerceAtMost(0f)
                val used = newOffset - headerOffsetPx
                headerOffsetPx = newOffset
                return Offset(0f, used)
            }
        }
    }

    MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
    GradientScreenScaffold {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .collapsingHeight(headerHeightPx) { headerOffsetPx }
                .clipToBounds(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp)
                    .offset { IntOffset(0, headerOffsetPx.roundToInt()) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AlbumArt(
                        uri = uiState.tracks.firstOrNull()?.artworkUri,
                        contentDescription = uiState.title,
                        shape = AuraShapes.SquircleMedium,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = uiState.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = AuraColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = uiState.artist,
                            style = MaterialTheme.typography.bodyLarge,
                            color = AuraColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        HiResBadge(
                            isHiRes = uiState.tracks.any { it.isHiRes },
                            hasExternalDac = false,
                        )
                    }
                    IconButton(onClick = albumViewModel::toggleFavorite) {
                        Icon(
                            imageVector = PhosphorIcons.Star,
                            contentDescription = "Favori",
                            tint = if (uiState.isFavorite) AuraColors.AccentOrange else AuraColors.TextPrimary,
                        )
                    }
                    IconButton(onClick = { showAddToPlaylist = true }) {
                        Icon(PhosphorIcons.MoreVertical, contentDescription = "Plus d'options", tint = AuraColors.TextPrimary)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            if (uiState.tracks.isNotEmpty()) {
                                playerViewModel.playQueue(uiState.tracks, 0)
                                navController.navigate(AuraDestination.NowPlaying.route)
                            }
                        },
                        shape = AuraShapes.Pill,
                        colors = ButtonDefaults.buttonColors(containerColor = AuraColors.TextPrimary, contentColor = AuraColors.Black),
                    ) {
                        Icon(PhosphorIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Lire tout")
                    }
                    Button(
                        onClick = {
                            if (uiState.tracks.isNotEmpty()) {
                                playerViewModel.playQueue(uiState.tracks.shuffled(), 0)
                                navController.navigate(AuraDestination.NowPlaying.route)
                            }
                        },
                        shape = AuraShapes.Pill,
                        colors = ButtonDefaults.buttonColors(containerColor = AuraColors.GlassFill, contentColor = AuraColors.TextPrimary),
                    ) {
                        Icon(PhosphorIcons.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Mélanger")
                    }
                }
            }
        }

        // One column, not two: a track row is number + artwork + title + artist + duration, and
        // splitting the 432dp width in half truncated every title to a few characters
        // ("after the sun goes down" became "after th..."). The two-column rule is for artwork
        // grids, where the cover carries the recognition; a text row needs the full width.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .nestedScroll(headerScrollConnection),
            contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(uiState.tracks, key = { _, track -> track.id }) { index, track ->
                AlbumTrackRow(
                    number = index + 1,
                    track = track,
                    onClick = {
                        playerViewModel.playQueue(uiState.tracks, index)
                        navController.navigate(AuraDestination.NowPlaying.route)
                    },
                    onLongClick = { menu.show(track) },
                )
            }
        }
    }

    if (showAddToPlaylist) {
        AddToPlaylistDialog(
            trackIds = uiState.tracks.map { it.id },
            onDismiss = { showAddToPlaylist = false },
        )
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackRow(number: Int, track: Track, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = AuraColors.TextTertiary,
            modifier = Modifier.width(22.dp),
        )
        AlbumArt(
            uri = track.artworkUri,
            contentDescription = track.title,
            shape = AuraShapes.SquircleSmall,
            modifier = Modifier.size(48.dp),
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
        Spacer(Modifier.width(6.dp))
        Text(
            text = formatTrackDuration(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = AuraColors.TextTertiary,
            maxLines = 1,
        )
    }
}

private fun formatTrackDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
