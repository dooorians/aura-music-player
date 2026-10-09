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
import androidx.compose.ui.graphics.toArgb
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
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.navigation.route
import com.ikko.aura.ui.components.keepsDragInRow
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

/** The cards are 620 × 620 px of the 1080-px design: short enough to end well above the mini player, leaving a band to swipe to the library. */
private const val CARD_HEIGHT_PX = 620f

/** 1 is the design's own drift, 0 holds the colours still. */

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

    // The cards' colours stay still: they used to drift as the row was swiped, which the user did not want.
    val rowState = rememberLazyListState()
    val day = remember { java.time.LocalDate.now().toEpochDay() }

    fun open(card: com.ikko.aura.viewmodel.ForYouCard) {
        val route = card.content.openRoute
        if (route != null) navController.navigate(route)
        else playerViewModel.playQueue(card.content.tracks, card.content.startIndex)
    }

    val cover = com.ikko.aura.ui.theme.LocalCoverColors.current
    GradientScreenScaffold(applyStatusBarInset = false) {
        Box(
            Modifier
                .fillMaxSize()
                // The colour of the record that is playing, laid over the top of the page and fading to black down it, as
                // on YouTube Music. [CoverColors.accent] is read here, in draw, so a change of record fades without recomposing.
                .drawBehind {
                    val hsl = FloatArray(3)
                    androidx.core.graphics.ColorUtils.colorToHSL(cover.accent.toArgb(), hsl)
                    val wash = Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsl[0], 0.55f, 0.28f)))
                    drawRect(
                        Brush.verticalGradient(
                            0f to wash.copy(alpha = 0.95f), 0.35f to wash.copy(alpha = 0.55f), 0.7f to wash.copy(alpha = 0.18f), 1f to Color.Transparent,
                            startY = 0f, endY = 760f * unit.toPx(),
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
                val cardsEffect = androidx.compose.foundation.rememberOverscrollEffect()
                LazyRow(
                    modifier = Modifier.keepsDragInRow(cardsEffect),
                    overscrollEffect = cardsEffect,
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = margin),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    // No keys: with them the row stayed anchored on the card that was first when the first list arrived, so a card
                    // joining at the front (the favourites arrive after the rest) left the row already scrolled by one.
                    items(state.cards) { card ->
                        ForYouCardView(
                            day = day,
                            card = card,
                            width = unit * 620f,
                            height = unit * CARD_HEIGHT_PX,
                            unit = unit,
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
                    val recentEffect = androidx.compose.foundation.rememberOverscrollEffect()
                    LazyRow(
                        modifier = Modifier.keepsDragInRow(recentEffect),
                        overscrollEffect = recentEffect,
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
                                shape = AuraShapes.AlbumCover,
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
