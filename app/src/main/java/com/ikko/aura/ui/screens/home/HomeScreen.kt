package com.ikko.aura.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Album
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.navigation.route
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.AuraIconButton
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.scrollTopBlur
import com.ikko.aura.ui.components.SquircleCard
import com.ikko.aura.ui.components.TrackListItem
import com.ikko.aura.ui.components.clipOnce
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LibraryTypography
import com.ikko.aura.ui.theme.LocalCoverColors
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.HomeViewModel
import com.ikko.aura.viewmodel.PlayerViewModel

/**
 * The margin, the type and the colours are the library's: the two pages sit side by side under one
 * gesture, so a title that changed size or tint from one to the other would read as two apps.
 */
private val PAGE_MARGIN = 21.dp

/** Favourites: one row of three covers, nothing under them (the user's choice), and no row to scroll sideways. */
private const val FAVOURITE_COLUMNS = 3
private const val FAVOURITE_ROWS = 1
private val GRID_SPACING = 13.dp

/** The covers fill their columns: narrower, the gaps between them read as uneven. */
private const val FAVOURITE_COVER_FRACTION = 1f

@Composable
fun HomeScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()

    val scroll = rememberScrollState()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // The page runs under the status bar: its inset is inside the scroll, not around it, so at rest nothing
    // moves, and scrolled, the content melts into the bar instead of stopping at a line below it.
    GradientScreenScaffold(applyStatusBarInset = false) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The same blurred top as the library's once the page is scrolled (see topBlur).
                .scrollTopBlur(topInset = statusBar) { scroll.value.toFloat() }
                .verticalScroll(scroll)
                .padding(top = statusBar + 8.dp),
        ) {
            SectionTitle(title = "Albums favoris")
            if (uiState.favoriteAlbums.isEmpty()) {
                Hint("Marque un album favori (★) pour le retrouver ici")
            } else {
                FavouriteGrid(albums = uiState.favoriteAlbums, navController = navController)
            }

            Spacer(Modifier.height(24.dp))

            SectionTitle(
                title = "Écoutés récemment",
                onSeeAll = { navController.navigate(AuraDestination.AlbumListPattern.route("recent")) },
            )
            if (uiState.recentTracks.isEmpty()) {
                Hint("Les derniers titres écoutés apparaîtront ici")
            } else {
                val recent = uiState.recentTracks.take(RECENT_TRACKS)
                recent.forEachIndexed { index, track ->
                    TrackListItem(
                        track = track,
                        onClick = { playerViewModel.playQueue(recent, index) },
                        modifier = Modifier.padding(horizontal = PAGE_MARGIN - 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            SectionTitle(
                title = "Redécouvrir",
                onSeeAll = { navController.navigate(AuraDestination.AlbumListPattern.route("discover")) },
            )
            AlbumCarousel(albums = uiState.rediscoverAlbums, navController = navController)

            Spacer(Modifier.height(LocalPlayerPillHeight.current + 24.dp))
        }
    }
}

/** How many of the recently played show on Home; the arrow beside the title opens the rest. */
private const val RECENT_TRACKS = 5

// Must match LibraryScreen's title row so "Favoris" and "Bibliothèque" sit at the same height when
// swiping between the two pages.
private val SECTION_HEADER_HEIGHT = 56.dp

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null) {
    val cover = LocalCoverColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SECTION_HEADER_HEIGHT)
            .padding(horizontal = PAGE_MARGIN),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = LibraryTypography.PageTitle,
            color = cover.tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onSeeAll != null) {
            Spacer(Modifier.size(8.dp))
            AuraIconButton(onClick = onSeeAll, size = 32.dp, light = 12f) {
                Icon(CupertinoIcons.ChevronRight, contentDescription = "Tout voir", modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AuraColors.TextTertiary,
        modifier = Modifier.padding(horizontal = PAGE_MARGIN),
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * Six favourites in a fixed grid, laid out by hand rather than with a lazy grid: Home is one scrolling
 * column, and a lazy grid inside it would be a second scroller on the same axis.
 */
@Composable
private fun FavouriteGrid(albums: List<Album>, navController: NavHostController) {
    val shown = albums.take(FAVOURITE_COLUMNS * FAVOURITE_ROWS)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_MARGIN),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
    ) {
        shown.chunked(FAVOURITE_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GRID_SPACING), modifier = Modifier.fillMaxWidth()) {
                row.forEach { album ->
                    AlbumCell(album = album, navController = navController, modifier = Modifier.weight(1f))
                }
                // Keeps the last row's covers the same width as the ones above when it is not full.
                repeat(FAVOURITE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AlbumCell(album: Album, navController: NavHostController, modifier: Modifier = Modifier) {
    // The cover alone: its title and artist are still read out (contentDescription), just not written under it.
    SquircleCard(
        modifier = modifier.fillMaxWidth(FAVOURITE_COVER_FRACTION).aspectRatio(1f),
        onClick = { navController.navigate(album.route()) },
    ) {
        AlbumArt(
            uri = album.artworkUri,
            contentDescription = "${album.title}, ${album.artist}",
            modifier = Modifier.fillMaxSize(),
            targetSizeDp = 120.dp,
            cacheKey = album.id.toString(),
        )
    }
}

/**
 * A plain row of album cards. Each card is a square cover with its title and artist over a scrim at
 * the bottom, laid out at one fixed size and scrolled with an ordinary fling.
 */
@Composable
private fun AlbumCarousel(albums: List<Album>, navController: NavHostController) {
    if (albums.isEmpty()) return
    val cover = LocalCoverColors.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = PAGE_MARGIN),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(albums, key = { it.id }) { album ->
            Box(
                modifier = Modifier
                    .size(CAROUSEL_ITEM_WIDTH)
                    .clipOnce(AuraShapes.SquircleMedium)
                    .clickable {
                        navController.navigate(album.route())
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
                        .background(CARD_SCRIM),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = album.title,
                        style = LibraryTypography.AlbumTitle,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = album.artist,
                        style = LibraryTypography.AlbumArtist,
                        color = cover.tint.copy(alpha = 0.72f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val CARD_SCRIM = androidx.compose.ui.graphics.Brush.verticalGradient(
    0.0f to Color.Transparent,
    0.45f to AuraColors.Black.copy(alpha = 0.45f),
    1.0f to AuraColors.Black.copy(alpha = 0.88f),
)

private val CAROUSEL_ITEM_WIDTH = 150.dp
private val CAROUSEL_LABEL_HEIGHT = 68.dp
