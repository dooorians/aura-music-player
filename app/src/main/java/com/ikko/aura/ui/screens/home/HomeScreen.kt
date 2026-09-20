package com.ikko.aura.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Album
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.viewmodel.HomeViewModel
import com.ikko.aura.viewmodel.PlayerViewModel

@Composable
fun HomeScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()

    GradientScreenScaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
        ) {
            SectionHeader(
                title = "Favoris",
                onSeeAll = { navController.navigate(AuraDestination.AlbumListPattern.route("favorites")) },
            )
            if (uiState.favoriteAlbums.isEmpty()) {
                Text(
                    text = "Marque un album favori (★) pour le retrouver ici",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraColors.TextTertiary,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(8.dp))
            } else {
                AlbumCarousel(albums = uiState.favoriteAlbums, navController = navController)
            }

            Spacer(Modifier.height(20.dp))

            if (uiState.recentAlbums.isNotEmpty()) {
                SectionHeader(
                    title = "Écoutés récemment",
                    onSeeAll = { navController.navigate(AuraDestination.AlbumListPattern.route("recent")) },
                )
                AlbumCarousel(albums = uiState.recentAlbums, navController = navController)
                Spacer(Modifier.height(20.dp))
            }

            SectionHeader(
                title = "Redécouvrir",
                onSeeAll = { navController.navigate(AuraDestination.AlbumListPattern.route("discover")) },
            )
            AlbumCarousel(albums = uiState.rediscoverAlbums, navController = navController)

            Spacer(Modifier.height(110.dp))
        }
    }
}

// Shared with LibraryScreen's LibraryHeader so both screens' titles sit at the exact same
// height regardless of what's in the trailing slot (a TextButton here, an IconButton there) —
// fixing the row's height and centering within it makes the text position independent of that.
private val SECTION_HEADER_HEIGHT = 56.dp

@Composable
private fun SectionHeader(title: String, onSeeAll: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SECTION_HEADER_HEIGHT)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineLarge, color = AuraColors.TextPrimary)
        TextButton(onClick = onSeeAll) {
            Text("Tout voir", style = MaterialTheme.typography.labelLarge, color = AuraColors.TextSecondary)
        }
    }
}

/**
 * Material 3's multi-browse carousel, which is what gives the rows their shape.
 *
 * Items are *masked* rather than scaled as they reach the edges: an album leaving the screen
 * narrows to a sliver with its cover cropped, not squashed, and grows back into a full square as
 * it returns. A LazyRow can only slide rectangles past a clip, which is what this used to do.
 *
 * The title sits inside the card over a scrim rather than under it, because a label outside the
 * mask would keep its full width while the artwork above it narrowed — the one arrangement that
 * makes the effect look broken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumCarousel(albums: List<Album>, navController: NavHostController) {
    if (albums.isEmpty()) return
    val state = rememberCarouselState { albums.size }

    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = CAROUSEL_ITEM_WIDTH,
        itemSpacing = 10.dp,
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(CAROUSEL_ITEM_WIDTH),
    ) { index ->
        val album = albums[index]
        Box(
            modifier = Modifier
                .height(CAROUSEL_ITEM_WIDTH)
                .maskClip(AuraShapes.SquircleMedium)
                .clickable {
                    navController.navigate(AuraDestination.AlbumPattern.route(album.id.toString()))
                },
        ) {
            AlbumArt(
                uri = album.artworkUri,
                contentDescription = album.title,
                shape = AuraShapes.SquircleMedium,
                modifier = Modifier.fillMaxSize(),
                targetSizeDp = CAROUSEL_ITEM_WIDTH,
                cacheKey = album.id.toString(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CAROUSEL_LABEL_HEIGHT)
                    .align(Alignment.BottomStart)
                    // Three stops rather than two: a straight ramp from nothing to black leaves
                    // the text sitting on the pale middle of it, which a light cover wins.
                    //
                    // Built per card rather than shared at file scope, which is the opposite of
                    // the usual advice and is what this carousel needs: multi-browse lays its
                    // items out at different widths as they approach the edges, and a ShaderBrush
                    // caches one shader against the last size it was asked for — so a single
                    // instance across cards of changing width rebuilds its gradient on nearly
                    // every draw. Measured on the home page, sharing one took the 90th-percentile
                    // GPU time from 10ms to 15ms.
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.45f to AuraColors.Black.copy(alpha = 0.45f),
                            1.0f to AuraColors.Black.copy(alpha = 0.88f),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = AuraColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.labelSmall,
                    color = AuraColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Square cards, so the width is also the row's height. */
private val CAROUSEL_ITEM_WIDTH = 146.dp
private val CAROUSEL_LABEL_HEIGHT = 68.dp
