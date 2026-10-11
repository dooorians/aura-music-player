package com.ikko.aura.ui.screens.artist

import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import com.ikko.aura.ui.components.topScrollPx
import com.ikko.aura.ui.components.scrollTopBlur
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Album
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.SquircleCard
import com.ikko.aura.ui.components.TrackListItem
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.ArtistUiState
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.navigation.route
import com.ikko.aura.viewmodel.ArtistViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.ui.components.AuraButton
import com.ikko.aura.ui.components.ButtonTone
import androidx.compose.ui.unit.sp

@Composable
fun ArtistScreen(
    artistId: String,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    artistViewModel: ArtistViewModel = hiltViewModel(),
) {
    val uiState by artistViewModel.uiState.collectAsStateWithLifecycle()
    ArtistPageContent(uiState, navController, playerViewModel)
}

/**
 * The artist page itself, for any artist: a local one from [ArtistScreen], a YouTube Music one from its own screen.
 * One layout, so the two cannot drift apart.
 */
@Composable
fun ArtistPageContent(
    uiState: ArtistUiState,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
) {
    val statusBarTop = with(androidx.compose.ui.platform.LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    GradientScreenScaffold(
        applyStatusBarInset = false,
        modifier = Modifier.scrollTopBlur(topInset = statusBarTop) { listState.topScrollPx() },
    ) {
      val tracksOf = com.ikko.aura.viewmodel.rememberAlbumTracks()
      MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = statusBarTop, bottom = LocalPlayerPillHeight.current + 12.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
                    Text(
                        text = uiState.name,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = AuraColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${uiState.albums.size} album${if (uiState.albums.size > 1) "s" else ""} · " +
                            "${uiState.tracks.size} titre${if (uiState.tracks.size > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AuraColors.TextSecondary,
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AuraButton(
                            onClick = {
                                if (uiState.tracks.isNotEmpty()) {
                                    playerViewModel.playQueue(uiState.tracks, 0)
                                }
                            },
                            tone = ButtonTone.Primary,
                        ) {
                            Icon(CupertinoIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Lire tout", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        AuraButton(
                            onClick = {
                                if (uiState.tracks.isNotEmpty()) {
                                    playerViewModel.playShuffled(uiState.tracks)
                                }
                            },
                        ) {
                            Icon(CupertinoIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Mélanger", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (uiState.albums.isNotEmpty()) {
                item {
                    Text(
                        text = "Albums",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AuraColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.albums, key = { it.id }) { album ->
                            ArtistAlbumCard(album = album, navController = navController, onLongClick = { menu.show(com.ikko.aura.ui.components.LibraryContextTarget.AlbumTarget(album, tracksOf(album.id))) })
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            item {
                Text(
                    text = "Titres",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AuraColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            itemsIndexed(uiState.tracks, key = { _, it -> it.id }) { index, track ->
                TrackListItem(
                    track = track,
                    onClick = {
                        playerViewModel.playQueue(uiState.tracks, index)
                    },
                    artSize = 56.dp,
                    // The artist is the page's own title: the album is what tells the rows apart.
                    subtitle = track.album,
                    onLongClick = { menu.show(track) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
      }
    }
}

@Composable
private fun ArtistAlbumCard(album: Album, navController: NavHostController, onLongClick: () -> Unit) {
    Column(modifier = Modifier.width(140.dp)) {
        SquircleCard(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            onClick = { navController.navigate(album.route()) },
            onLongClick = onLongClick,
        ) {
            AlbumArt(uri = album.artworkUri, contentDescription = album.title, modifier = Modifier.fillMaxWidth().aspectRatio(1f), targetSizeDp = 140.dp, cacheKey = album.id.toString())
        }
        Text(
            text = album.title,
            style = MaterialTheme.typography.labelLarge,
            color = AuraColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
