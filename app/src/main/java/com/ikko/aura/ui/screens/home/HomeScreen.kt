package com.ikko.aura.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import com.ikko.aura.viewmodel.rememberAlbumTracks
import com.ikko.aura.ui.components.LibraryContextTarget
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.width
import com.ikko.aura.ui.theme.CupertinoIcons
import androidx.compose.material3.Icon
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    // A card held down is carried along the row and dropped where it is wanted: the order shown while it moves, saved on drop.
    var organizing by remember { androidx.compose.runtime.mutableStateOf(false) }
    var dragOrder by remember { androidx.compose.runtime.mutableStateOf<List<String>?>(null) }
    var draggedId by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var dragOffset by remember { androidx.compose.runtime.mutableStateOf(0f) }
    val shownCards = remember(state.cards, dragOrder) {
        val order = dragOrder
        if (order == null) state.cards else state.cards.sortedBy { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    fun open(card: com.ikko.aura.viewmodel.ForYouCard) {
        val route = card.content.openRoute
        if (route != null) navController.navigate(route)
        else playerViewModel.playQueue(card.content.tracks, card.content.startIndex)
    }

    val tracksOf = rememberAlbumTracks()
    com.ikko.aura.ui.components.MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
    if (organizing) {
        HomeOrganizer(
            slots = state.layout,
            onMove = { id, delta ->
                dragOrder = null
                viewModel.moveCard(id, delta)
            },
            onHidden = viewModel::setCardHidden,
            onDismiss = { organizing = false },
        )
    }
    GradientScreenScaffold(applyStatusBarInset = false) {
        Box(
            Modifier
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = statusBar + 10.5.dp),
            ) {
                Row(
                    // The library's own margin, so the two pages' titles and corner buttons sit on the same lines.
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Held down, the title opens the organiser: the cards' order, and which ones show.
                    CoverText(
                        text = "Pour toi",
                        style = LibraryTypography.PageTitle,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(onClick = {}, onLongClick = { organizing = true }),
                    )
                    ProfileAvatar(
                        imageUri = profileImageUri,
                        contentDescription = "Ouvrir les réglages du profil",
                        modifier = Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable {
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
                    items(shownCards) { card ->
                        val stepPx = with(LocalDensity.current) { (unit * 620f + gap).toPx() }
                        val dragging = draggedId == card.id
                        ForYouCardView(
                            day = day,
                            card = card,
                            width = unit * 620f,
                            height = unit * CARD_HEIGHT_PX,
                            unit = unit,
                            onClick = { open(card) },
                            modifier = Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    if (dragging) {
                                        translationX = dragOffset
                                        scaleX = 1.04f
                                        scaleY = 1.04f
                                    }
                                }
                                .pointerInput(card.id, shownCards.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedId = card.id
                                            dragOffset = 0f
                                            dragOrder = shownCards.map { it.id }
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            dragOffset += delta.x
                                            val order = dragOrder?.toMutableList() ?: return@detectDragGesturesAfterLongPress
                                            val at = order.indexOf(card.id)
                                            // Past half a card's width one way: swapped with the neighbour that way, and the
                                            // card is kept under the finger by taking that step off its offset.
                                            val to = when {
                                                dragOffset > stepPx / 2f && at < order.lastIndex -> at + 1
                                                dragOffset < -stepPx / 2f && at > 0 -> at - 1
                                                else -> at
                                            }
                                            if (to != at) {
                                                order.add(to, order.removeAt(at))
                                                dragOrder = order
                                                dragOffset -= (to - at) * stepPx
                                            }
                                        },
                                        onDragEnd = {
                                            dragOrder?.let { viewModel.setCardOrder(it) }
                                            draggedId = null
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            draggedId = null
                                            dragOffset = 0f
                                        },
                                    )
                                },
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
                                    .combinedClickable(
                                        onClick = { navController.navigate(album.route()) },
                                        onLongClick = { menu.show(LibraryContextTarget.AlbumTarget(album, tracksOf(album.id))) },
                                    ),
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
}
