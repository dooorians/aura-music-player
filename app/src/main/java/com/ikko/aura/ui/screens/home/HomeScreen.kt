package com.ikko.aura.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavHostController
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.navigation.route
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.ProfileAvatar
import com.ikko.aura.ui.theme.CoverText
import com.ikko.aura.ui.theme.LibraryTypography
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.HomeWidgetsViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

/** The design is drawn on a 1080-px-wide screen, 432 dp on this device. */
private const val DESIGN_WIDTH_PX = 1080f

/** 1 is the design's own drift, 0 holds the colours still. */
private const val MOTION = 1f

/**
 * The Home: "Pour toi", a row of big cards each dressed in its own mood of drifting colour, then the albums played lately.
 *
 * The colours of the cards move with the row, as it is swiped, and are still the rest of the time.
 */
@Composable
fun HomeScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    viewModel: HomeWidgetsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val profileImageUri by viewModel.profileImageUri.collectAsStateWithLifecycle()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val screenDp = LocalConfiguration.current.screenWidthDp
    val unit = (screenDp / DESIGN_WIDTH_PX).dp
    val margin = unit * 50f
    val gap = unit * 25f

    // The colours drift with the row: they move as the cards are swiped and rest when the finger does. A drift of their own
    // was measured on the device: every frame it draws is a frame the whole window renders (the mini player's glass
    // included), 54 % of a core on an idle Home against 7 % without it.
    val rowState = rememberLazyListState()
    val cardStepPx = with(LocalDensity.current) { (unit * (620f + 25f)).toPx() }
    val phase: () -> Float = { (rowState.firstVisibleItemIndex * cardStepPx + rowState.firstVisibleItemScrollOffset) / (cardStepPx * 2.5f) }

    fun open(card: com.ikko.aura.viewmodel.ForYouCard) {
        val route = card.content.openRoute
        if (route != null) navController.navigate(route)
        else playerViewModel.playQueue(card.content.tracks, card.content.startIndex)
    }

    GradientScreenScaffold(applyStatusBarInset = false) {
        Box(
            Modifier
                .fillMaxSize()
                // The violet wash the design lays over the top of the page.
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color(0x61_5828BE), 0.5f to Color(0x1F_5828BE), 1f to Color.Transparent,
                            startY = 0f, endY = 520f * unit.toPx(),
                        ),
                    )
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = statusBar + 8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = margin),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverText(text = "Pour toi", style = LibraryTypography.PageTitle, maxLines = 1, modifier = Modifier.weight(1f))
                    ProfileAvatar(
                        imageUri = profileImageUri,
                        contentDescription = "Ouvrir les réglages du profil",
                        modifier = Modifier.size(36.dp).clickable {
                            navController.navigate(AuraDestination.Settings.route) { launchSingleTop = true }
                        },
                    )
                }
                Spacer(Modifier.height(unit * 30f))
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = margin),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    items(state.cards, key = { it.id }) { card ->
                        ForYouCardView(
                            card = card,
                            width = unit * 620f,
                            height = unit * 780f,
                            unit = unit,
                            phase = phase,
                            motion = MOTION,
                            onClick = { open(card) },
                        )
                    }
                }

                if (state.recent.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = margin, end = margin, top = unit * 56f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverText(text = "Écoutés récemment", style = LibraryTypography.PageTitle, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(
                            "Tout voir",
                            style = LibraryTypography.AlbumArtist,
                            color = Color(0xFFB3B3B3),
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.clickable { navController.navigate(AuraDestination.AlbumListPattern.route("recent")) },
                        )
                    }
                    Spacer(Modifier.height(unit * 25f))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = margin),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        items(state.recent, key = { it.id }) { album ->
                            AlbumArt(
                                uri = album.artworkUri,
                                contentDescription = album.title,
                                modifier = Modifier
                                    .size(unit * 320f)
                                    .clickable { navController.navigate(album.route()) },
                                shape = RoundedCornerShape(unit * 60f),
                                targetSizeDp = unit * 320f,
                                cacheKey = album.id.toString(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(LocalPlayerPillHeight.current + 24.dp))
            }
        }
    }
}
