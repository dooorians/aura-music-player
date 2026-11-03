package com.ikko.aura.ui.screens.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.spring
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.navigation.route
import com.ikko.aura.ui.components.CreatePlaylistDialog
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.glassSurface
import com.ikko.aura.ui.screens.home.underHeaderBlur
import com.ikko.aura.ui.screens.library.PlaylistsList
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LocalSearchOpener
import com.ikko.aura.viewmodel.LibraryViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.PlaylistsViewModel

@Composable
fun PlaylistsOverviewScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    playlistsViewModel: PlaylistsViewModel = hiltViewModel(),
    libraryViewModel: LibraryViewModel = hiltViewModel(),
) {
    val u = designUnit()
    val playlists by playlistsViewModel.playlists.collectAsStateWithLifecycle()
    val customArtworks by playlistsViewModel.customArtworks.collectAsStateWithLifecycle()
    val uiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val tracksById = remember(uiState.tracks) { uiState.tracks.associateBy { it.id } }
    val listState = rememberLazyListState()
    var creatingPlaylist by remember { mutableStateOf(false) }
    val searchOpener = LocalSearchOpener.current
    val scope = rememberCoroutineScope()
    val headerVisible = remember { androidx.compose.animation.core.Animatable(1f) }
    var scrollAccumulator by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density
    val scrollConnection = remember(density) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy < -4f) {
                    if (scrollAccumulator > 0f) scrollAccumulator = 0f
                    scrollAccumulator += dy
                    if (scrollAccumulator < -10f * density && headerVisible.targetValue > 0f) {
                        scope.launch {
                            headerVisible.animateTo(
                                0f,
                                spring(dampingRatio = 0.85f, stiffness = 400f),
                            )
                        }
                    }
                } else if (dy > 2f) {
                    if (scrollAccumulator < 0f) scrollAccumulator = 0f
                    scrollAccumulator += dy
                    if (scrollAccumulator > 4f * density && headerVisible.targetValue < 1f) {
                        scope.launch {
                            headerVisible.animateTo(
                                1f,
                                spring(dampingRatio = 0.85f, stiffness = 400f),
                            )
                        }
                    }
                }
                return Offset.Zero
            }
        }
    }

    GradientScreenScaffold(applyStatusBarInset = false) {
        Box(Modifier.fillMaxSize()) {
            // Scrollable list under header with progressive blur
            Box(
                Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollConnection),
            ) {
                PlaylistsList(
                    playlists = playlists,
                    customArtworks = customArtworks,
                    tracksById = tracksById,
                    listState = listState,
                    creating = false,
                    onCreate = { name ->
                        playlistsViewModel.createPlaylist(name)
                        creatingPlaylist = false
                    },
                    onCancelCreate = { creatingPlaylist = false },
                    onOpen = { id -> navController.navigate(AuraDestination.PlaylistPattern.route(id.toString())) },
                )
            }

            // Header above the list, with progressive blur staying permanently underneath
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(u * 236f)
                    .graphicsLayer {
                        val clamped = headerVisible.value.coerceIn(0f, 1f)
                        alpha = clamped
                        translationY = -u.toPx() * 50f * (1f - clamped)
                    }
                    .padding(top = u * 112f, start = u * 40f, end = u * 40f, bottom = u * 24f),
            ) {
                // Title at exactly 40, 112 with 84sp ExtraBold, translucent at 42%
                Text(
                    text = "Playlists",
                    color = Color.White.copy(alpha = 0.42f),
                    style = TextStyle(
                        fontFamily = AuraFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = designSp(84f),
                        lineHeight = designSp(96f),
                        letterSpacing = designSp(84f * -0.03f),
                    ),
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterStart),
                )

                // Actions: Search and Add buttons
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(u * 16f),
                ) {
                    Box(
                        Modifier
                            .size(u * 96f)
                            .glassSurface(CircleShape)
                            .clip(CircleShape)
                            .clickable(onClick = searchOpener),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            CupertinoIcons.Search,
                            contentDescription = "Rechercher",
                            tint = Color.White,
                            modifier = Modifier.size(u * 44f),
                        )
                    }

                    Box(
                        Modifier
                            .size(u * 96f)
                            .glassSurface(CircleShape)
                            .clip(CircleShape)
                            .clickable { creatingPlaylist = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            CupertinoIcons.Add,
                            contentDescription = "Nouvelle playlist",
                            tint = Color.White,
                            modifier = Modifier.size(u * 46f),
                        )
                    }
                }
            }
        }

        if (creatingPlaylist) {
            CreatePlaylistDialog(
                onDismiss = { creatingPlaylist = false },
                onCreate = { name, artworkUri ->
                    playlistsViewModel.createPlaylist(name, artworkUri)
                    creatingPlaylist = false
                },
            )
        }
    }
}
