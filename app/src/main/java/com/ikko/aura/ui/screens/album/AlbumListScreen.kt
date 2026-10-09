package com.ikko.aura.ui.screens.album

import com.ikko.aura.ui.components.listTopBlur
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.navigation.route
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.SquircleCard
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.ui.components.PageHeader
import com.ikko.aura.viewmodel.AlbumListViewModel

@Composable
fun AlbumListScreen(
    mode: String,
    navController: NavHostController,
    albumListViewModel: AlbumListViewModel = hiltViewModel(),
) {
    val uiState by albumListViewModel.uiState.collectAsStateWithLifecycle()

    GradientScreenScaffold {
        PageHeader(title = uiState.title)

        if (uiState.albums.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Rien à afficher ici pour l'instant",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AuraColors.TextSecondary,
                )
            }
        } else {
            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().weight(1f).listTopBlur(gridState),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = LocalPlayerPillHeight.current + 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(uiState.albums, key = { it.id }) { album ->
                    Column {
                        SquircleCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            onClick = { navController.navigate(album.route()) },
                        ) {
                            AlbumArt(
                                uri = album.artworkUri,
                                contentDescription = album.title,
                                modifier = Modifier.fillMaxSize(),
                                targetSizeDp = 180.dp,
                                cacheKey = album.id.toString(),
                            )
                        }
                        Text(
                            text = album.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = AuraColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            text = album.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuraColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
