package com.ikko.aura.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.home.CardAmbience
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.navigation.route
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.FrostedCircleButton
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.LibraryContextTarget
import com.ikko.aura.ui.components.LocalImmersivePalette
import com.ikko.aura.ui.components.LocalPageMotion
import com.ikko.aura.ui.components.ProfileAvatar
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.immersiveOfAmbience
import com.ikko.aura.ui.components.rememberPlayerAccent
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.ForYouCard
import com.ikko.aura.viewmodel.HomeWidgetsViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.rememberAlbumTracks

/** What each mood writes in: the ink of its selected chip and of its white button, and the tint of its subtitle (FHome*). */
private class Ink(val on: Color, val soft: Color)

private fun inkOf(card: ForYouCard?) = when (card?.id) {
    "top" -> Ink(Color(0xFF4A3000), Color(0xFFFFF0B8))
    "daily" -> Ink(Color(0xFF0C3A08), Color(0xFFDDF8B8))
    "style" -> Ink(Color(0xFF5A0414), Color(0xFFFFD0D6))
    else -> inkOf(card?.ambience ?: CardAmbience.FAVORIS)
}

private fun paletteOf(card: ForYouCard?) = when (card?.id) {
    "top" -> com.ikko.aura.ui.components.ImmersivePalettes.Or
    "daily" -> com.ikko.aura.ui.components.ImmersivePalettes.Vert
    "style" -> com.ikko.aura.ui.components.ImmersivePalettes.Cramoisi
    else -> immersiveOfAmbience(card?.ambience ?: CardAmbience.FAVORIS)
}

private fun inkOf(ambience: CardAmbience) = when (ambience) {
    CardAmbience.FAVORIS -> Ink(Color(0xFF1D1450), Color(0xFFD8CDFC))
    CardAmbience.DECOUVERTE -> Ink(Color(0xFF0A1F6B), Color(0xFFD2E6FF))
    CardAmbience.NOUVEAUTES -> Ink(Color(0xFF7A0B4A), Color(0xFFFFD2EA))
    CardAmbience.ENERGIE -> Ink(Color(0xFF6B2204), Color(0xFFFFE6BE))
    CardAmbience.CHILL -> Ink(Color(0xFF04423D), Color(0xFFC8FFF1))
    CardAmbience.NUIT -> Ink(Color(0xFF08122E), Color(0xFFC4D8FF))
}

/**
 * The Home, as the "F" mock-ups draw it: the title and the profile, a row of moods as chips, the chosen mood's name very
 * large with its line, "Lire" and the shuffle, then the albums played lately — all over the mood's own background.
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
    val u = designUnit()
    val tracksOf = rememberAlbumTracks()
    var organizing by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected: ForYouCard? = state.cards.firstOrNull { it.id == selectedId } ?: state.cards.firstOrNull()
    val ink = inkOf(selected)
    val accent = rememberPlayerAccent()
    val face = TextStyle(fontFamily = AuraFontFamily, color = Color.White)

    // The page's background is the mood's — only while Home is the page showing.
    val slot = LocalImmersivePalette.current
    val motion = LocalPageMotion.current
    val palette = paletteOf(selected)
    LaunchedEffect(palette) {
        snapshotFlow { (motion?.p ?: 0f) < 0.5f }.collect { shown ->
            if (shown) slot.value = palette else if (slot.value == palette) slot.value = null
        }
    }
    DisposableEffect(palette) { onDispose { if (slot.value == palette) slot.value = null } }

    com.ikko.aura.ui.components.MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
        if (organizing) {
            HomeOrganizer(
                slots = state.layout,
                onMove = { id, delta -> viewModel.moveCard(id, delta) },
                onHidden = viewModel::setCardHidden,
                onDismiss = { organizing = false },
            )
        }
        GradientScreenScaffold(applyStatusBarInset = false) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = u * 128f),
            ) {
                // Title and profile.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = u * 40f),
                    horizontalArrangement = Arrangement.spacedBy(u * 25f),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "Pour toi",
                        style = face.copy(fontSize = designSp(66f), lineHeight = designSp(76f), fontWeight = FontWeight.ExtraBold, letterSpacing = designSp(66f * -0.02f)),
                        modifier = Modifier
                            .weight(1f)
                            // Held down, the title opens the organiser: the moods' order, and which ones show.
                            .combinedClickable(onClick = {}, onLongClick = { organizing = true }),
                    )
                    ProfileAvatar(
                        imageUri = profileImageUri,
                        contentDescription = "Ouvrir les réglages du profil",
                        modifier = Modifier
                            .offset(y = u * -18f)
                            .size(u * 112f)
                            .clip(CircleShape)
                            .clickable { navController.navigate(AuraDestination.Settings.route) { launchSingleTop = true } },
                    )
                }

                // The moods, as chips.
                LazyRow(
                    modifier = Modifier.padding(top = u * 26f),
                    contentPadding = PaddingValues(horizontal = u * 40f),
                    horizontalArrangement = Arrangement.spacedBy(u * 12f),
                ) {
                    items(state.cards) { card ->
                        val on = card.id == selected?.id
                        Box(
                            Modifier
                                .height(u * 88f)
                                .clip(CircleShape)
                                .then(
                                    if (on) Modifier.background(Color.White)
                                    else Modifier
                                        .background(Color.White.copy(alpha = 0.14f))
                                        .border(u * 1.5f, Color.White.copy(alpha = 0.24f), CircleShape),
                                )
                                .clickable { selectedId = card.id }
                                .padding(horizontal = u * 44f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                card.heading,
                                style = face.copy(
                                    fontSize = designSp(38f), lineHeight = designSp(46f),
                                    fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Bold,
                                    color = if (on) ink.on else Color.White,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }

                // The chosen mood: its name, very large, and its line.
                if (selected != null) {
                    Column(
                        Modifier
                            .padding(top = u * 48f, start = u * 40f, end = u * 40f)
                            .clickable(enabled = selected.content.openRoute != null) {
                                selected.content.openRoute?.let { navController.navigate(it) }
                            },
                    ) {
                        Text(
                            selected.heading,
                            style = face.copy(fontSize = designSp(96f), lineHeight = designSp(104f), fontWeight = FontWeight.ExtraBold, letterSpacing = designSp(96f * -0.03f)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            selected.kicker,
                            style = face.copy(fontSize = designSp(34f), lineHeight = designSp(42f), fontWeight = FontWeight.SemiBold, color = ink.soft),
                            modifier = Modifier.padding(top = u * 8f),
                        )
                    }

                    // Lire, and the shuffle.
                    Row(
                        Modifier.fillMaxWidth().padding(top = u * 48f, start = u * 40f, end = u * 40f),
                        horizontalArrangement = Arrangement.spacedBy(u * 25f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier
                                .weight(1f)
                                .height(u * 112f)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable { playerViewModel.playQueue(selected.content.tracks, selected.content.startIndex) },
                            horizontalArrangement = Arrangement.spacedBy(u * 18f, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(CupertinoIcons.Play, contentDescription = null, tint = ink.on, modifier = Modifier.size(u * 44f))
                            Text(
                                "Lire",
                                style = face.copy(fontSize = designSp(40f), lineHeight = designSp(48f), fontWeight = FontWeight.ExtraBold, color = ink.on),
                            )
                        }
                        FrostedCircleButton(onClick = { playerViewModel.playShuffled(selected.content.tracks) }, size = u * 112f, scrim = 0.34f) {
                            Icon(CupertinoIcons.Shuffle, contentDescription = "Lecture aléatoire", tint = Color.White, modifier = Modifier.size(u * 44f))
                        }
                    }
                }

                // The albums played lately.
                if (state.recent.isNotEmpty()) {
                    Text(
                        "Écoutés récemment",
                        style = face.copy(fontSize = designSp(56f), lineHeight = designSp(66f), fontWeight = FontWeight.ExtraBold, letterSpacing = designSp(56f * -0.02f)),
                        modifier = Modifier.padding(top = u * 64f, start = u * 40f, end = u * 40f),
                    )
                    LazyRow(
                        modifier = Modifier.padding(top = u * 28f),
                        contentPadding = PaddingValues(horizontal = u * 40f),
                        horizontalArrangement = Arrangement.spacedBy(u * 25f),
                    ) {
                        items(state.recent, key = { it.id }) { album ->
                            AlbumArt(
                                uri = album.artworkUri,
                                contentDescription = album.title,
                                modifier = Modifier
                                    .size(u * 316f)
                                    .combinedClickable(
                                        onClick = { navController.navigate(album.route()) },
                                        onLongClick = { menu.show(LibraryContextTarget.AlbumTarget(album, tracksOf(album.id))) },
                                    ),
                                shape = RoundedCornerShape(u * 32f),
                                targetSizeDp = u * 316f,
                                cacheKey = album.id.toString(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(LocalPlayerPillHeight.current + u * 40f))
            }
        }
    }
}
