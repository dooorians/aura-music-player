package com.ikko.aura.ui.screens.artist

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.fillMaxSize
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
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    GradientScreenScaffold {
      MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
       androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        // The aurora shows at the top of the page; below, the page is AMOLED black.
        ArtistGround(listState)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
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
                                    playerViewModel.playQueue(uiState.tracks.shuffled(), 0)
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
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(uiState.albums, key = { it.id }) { album ->
                            ArtistAlbumCard(album = album, navController = navController)
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
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            items(uiState.tracks, key = { it.id }) { track ->
                val index = uiState.tracks.indexOf(track)
                TrackListItem(
                    track = track,
                    onClick = {
                        playerViewModel.playQueue(uiState.tracks, index)
                    },
                    artSize = 56.dp,
                    // The artist is the page's own title: the album is what tells the rows apart.
                    subtitle = track.album,
                    onLongClick = { menu.show(track) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
       }
      }
    }
}

@Composable
private fun ArtistAlbumCard(album: Album, navController: NavHostController) {
    Column(modifier = Modifier.width(140.dp)) {
        SquircleCard(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            onClick = { navController.navigate(album.route()) },
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

/** Where the aurora is fully clear, and where the black is fully there, from the top of the page. */
private const val GROUND_CLEAR_DP = 170
private const val GROUND_SOLID_DP = 360

/**
 * What turns the app's background into an artist page: the aurora shows through at the top, and a gradient over it
 * gives way to AMOLED black below. It rides up with the header as the list scrolls — the aurora is a surface behind the
 * window and cannot move, so this is what slides over it — and once the header is gone the page is black throughout.
 */
@Composable
private fun ArtistGround(listState: androidx.compose.foundation.lazy.LazyListState) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = -(if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else GROUND_SOLID_DP.dp.toPx())
                    .coerceAtMost(GROUND_SOLID_DP.dp.toPx())
            }
            .drawBehind {
                val fadeStart = GROUND_CLEAR_DP.dp.toPx()
                val fadeEnd = GROUND_SOLID_DP.dp.toPx()
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0f),
                        0.42f to Color.Black.copy(alpha = 0.38f),
                        0.78f to Color.Black.copy(alpha = 0.86f),
                        1f to Color.Black,
                        startY = fadeStart,
                        endY = fadeEnd,
                    ),
                    topLeft = Offset(0f, fadeStart),
                    size = Size(size.width, fadeEnd - fadeStart),
                )
                drawRect(Color.Black, topLeft = Offset(0f, fadeEnd), size = Size(size.width, size.height * 3f))
            },
    )
}
