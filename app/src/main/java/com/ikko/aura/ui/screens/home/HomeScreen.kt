package com.ikko.aura.ui.screens.home

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.shadow
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.LocalPageMotion
import com.ikko.aura.ui.components.PageHeader
import com.ikko.aura.ui.components.HeroState
import com.ikko.aura.ui.components.heroAnchor
import com.ikko.aura.ui.components.heroRipple
import com.ikko.aura.ui.components.heroSideLine
import com.ikko.aura.ui.components.ProvideTabEntry
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.tabPanelTransition
import com.ikko.aura.ui.components.tabStagger
import com.ikko.aura.ui.components.tabTextRise
import com.ikko.aura.ui.theme.LocalSearchOpener
import com.ikko.aura.ui.components.TabMotion
import com.ikko.aura.ui.components.tabStagger
import com.ikko.aura.ui.components.tabTextRise
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Album
import com.ikko.aura.navigation.route
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.LibraryContextTarget
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.components.keepsDragInRow
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LibraryTypography
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.HomeWidgetsViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.rememberAlbumTracks

/** The design is drawn on a 1080-px-wide screen, 432 dp on this device. */
private const val DESIGN_WIDTH_PX = 1080f

private val HOME_TABS = listOf("Récents", "Favoris", "Nouveautés", "Explorer")

private val HomeTabActiveStyle = TextStyle(
    fontFamily = AuraFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 15.5.sp,
    lineHeight = 20.sp,
)

private val HomeTabInactiveStyle = TextStyle(
    fontFamily = AuraFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.5.sp,
    lineHeight = 20.sp,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    viewModel: HomeWidgetsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val profileUri by viewModel.profileImageUri.collectAsStateWithLifecycle()
    val u = designUnit()
    val openSearch = LocalSearchOpener.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // One scroll state per tab: during a change both carousels are on screen at once (the old one leaving, the new one
    // arriving), and a shared state moved the leaving one back to its first card under the finger's eye.
    val rowStates = List(4) { rememberLazyListState() }
    val tabDir = remember { intArrayOf(selectedTab, 1) }
    if (tabDir[0] != selectedTab) {
        tabDir[1] = if (selectedTab > tabDir[0]) 1 else -1
        tabDir[0] = selectedTab
        // The tab being opened starts from its first card; coming back to the page (after an album) leaves it as it was.
        rowStates[selectedTab].requestScrollToItem(0)
    }

    val tracksOf = rememberAlbumTracks()
    val coverShape = remember(u) { RoundedCornerShape(u * 52f) }
    val reduceMotion = LocalPageMotion.current?.reduceMotion == true
    val panelSpec = tabPanelTransition<Int>(reduceMotion) { from, to -> if (to >= from) 1 else -1 }
    val cardPx = 456f

    // The carousels' covers are decoded ahead, at the size they are drawn at, so scrolling brings in covers that are
    // simply there rather than ones that load and fade in: the cards beyond the screen are not composed, and a cover
    // not in memory used to be fetched only when its card scrolled into view.
    val context = androidx.compose.ui.platform.LocalContext.current
    val coverPx = with(androidx.compose.ui.platform.LocalDensity.current) { (u * cardPx).toPx().toInt() }.coerceAtLeast(1)
    LaunchedEffect(state.resumeAlbums, state.favoriteAlbums, state.newAlbums, state.discoveryAlbums, coverPx, selectedTab) {
        // The tab on screen first, then the others, every card of each (a tab holds 12): a swipe, or a change of tab,
        // finds all of them ready. The cache's budget is ample for it (a cover this size is about 0.8 MB). Three decodes at a time rather than one after the other,
        // so a carousel flung at once finds its middle covers ready and not only its ends.
        val order = listOf(selectedTab) + (0..3).filter { it != selectedTab }
        val lists = listOf(state.resumeAlbums, state.favoriteAlbums, state.newAlbums, state.discoveryAlbums)
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        kotlinx.coroutines.coroutineScope {
            for (tab in order) {
                for (album in lists[tab].take(12)) {
                    launch {
                        gate.withPermit {
                            com.ikko.aura.ui.components.AlbumArtCache.load(
                                context, album.artworkUri, coverPx, album.id.toString(), shrink = true,
                            )
                        }
                    }
                }
            }
        }
    }

    MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
        GradientScreenScaffold(applyStatusBarInset = false) {
            Box(Modifier.fillMaxSize()) {
                // Onglets: 40/48 gras, 44 px entre eux; le gris devient blanc en 220 ms.
                Row(
                    modifier = Modifier
                        .padding(start = u * PageHeader.MARGIN, top = u * PageHeader.TABS_TOP)
                        .height(u * PageHeader.TABS_HEIGHT)
                        .heroRipple(fast = true),
                    horizontalArrangement = Arrangement.spacedBy(u * PageHeader.TAB_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HOME_TABS.forEachIndexed { index, title ->
                        val color by animateColorAsState(
                            if (selectedTab == index) Color.White else Color(0xFF7F7F89),
                            tween(220), label = "home-tab-color",
                        )
                        Text(
                            text = title,
                            color = color,
                            fontFamily = AuraFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = designSp(PageHeader.TAB_TEXT),
                            lineHeight = designSp(PageHeader.TAB_LINE),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { selectedTab = index },
                        )
                    }
                }

                // Le carrousel seul change d'un onglet à l'autre (sortie 170 ms, entrée 360 ms, pochettes décalées de 60 ms).
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = panelSpec,
                    modifier = Modifier.padding(top = u * 396f).fillMaxWidth(),
                    label = "home-tab",
                ) { tab ->
                    val entering = transition.currentState == EnterExitState.PreEnter
                    ProvideTabEntry(entering, tabDir[1]) {
                        val albums = when (tab) {
                            0 -> state.resumeAlbums
                            1 -> state.favoriteAlbums
                            2 -> state.newAlbums
                            else -> state.discoveryAlbums
                        }
                        val rowState = rowStates[tab.coerceIn(0, 3)]
                        com.ikko.aura.ui.components.EndTabEntryWhenScrolled(rowState.isScrollInProgress)
                        val rowEffect = rememberOverscrollEffect()
                        val cardSizePx = with(androidx.compose.ui.platform.LocalDensity.current) { (u * cardPx).toPx() }
                        LazyRow(
                            modifier = Modifier.keepsDragInRow(rowEffect, maxDragY = cardSizePx),
                            overscrollEffect = rowEffect,
                            state = rowState,
                            contentPadding = PaddingValues(horizontal = u * 40f),
                            horizontalArrangement = Arrangement.spacedBy(u * 28f),
                        ) {
                            itemsIndexed(albums, key = { _, a -> a.id }) { index, album ->
                                HomeSquareAlbumCard(
                                    album = album,
                                    index = index,
                                    tab = tab,
                                    lastPlayedAt = state.lastPlayedAt[album.id],
                                    u = u,
                                    coverShape = coverShape,
                                    onClick = { navController.navigate(album.route()) },
                                    onPlay = {
                                        val tracks = tracksOf(album.id)
                                        if (tracks.isNotEmpty()) playerViewModel.playQueue(tracks, 0)
                                    },
                                    onLongClick = { menu.show(LibraryContextTarget.AlbumTarget(album, tracksOf(album.id))) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Il y a 2 h", "Hier", "Il y a 3 j": when the album was last played, as short as the board writes it. */
private fun ago(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val minutes = ((nowMs - thenMs) / 60_000L).coerceAtLeast(0)
    val cal = java.util.Calendar.getInstance()
    val today = cal.apply { timeInMillis = nowMs; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
    val days = ((today - thenMs + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(0)
    return when {
        minutes < 1 -> "À l'instant"
        minutes < 60 -> "Il y a $minutes min"
        thenMs >= today -> "Il y a ${minutes / 60} h"
        days <= 1 -> "Hier"
        days < 30 -> "Il y a $days j"
        else -> "Il y a ${days / 30} mois"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeSquareAlbumCard(
    album: Album,
    index: Int,
    tab: Int,
    lastPlayedAt: Long?,
    u: Dp,
    coverShape: RoundedCornerShape,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onLongClick: () -> Unit,
) {
    val textRise = Modifier.tabTextRise(index)
    val hero = com.ikko.aura.ui.components.LocalHero.current
    val heroKey = "album:${album.id}"
    val coverAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
    val titleAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
    val subAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val titleStyle = androidx.compose.ui.text.TextStyle(
        fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = designSp(40f), lineHeight = designSp(48f),
    )
    val subStyle = androidx.compose.ui.text.TextStyle(
        fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(34f), lineHeight = designSp(42f),
    )
    val startHero: () -> Boolean = {
        hero.begin(
            key = heroKey,
            uri = album.artworkUri,
            cacheKey = album.id.toString(),
            cover = coverAnchor,
            radiusPx = with(density) { (u * 52f).toPx() },
            title = titleAnchor to com.ikko.aura.ui.components.HeroText(album.title, androidx.compose.ui.geometry.Rect.Zero, titleStyle, Color.White, maxLines = 2),
            sub = subAnchor to com.ikko.aura.ui.components.HeroText(album.artist, androidx.compose.ui.geometry.Rect.Zero, subStyle, Color(0xFF8B8B95)),
            playButton = true,
            sizeDp = u * 456f,
        )
    }
    val third = when (tab) {
        0 -> lastPlayedAt?.let { ago(it) }
        1 -> "Favori"
        2 -> "Album"
        else -> "Suggestion"
    }
    Column(modifier = Modifier.width(u * 456f).heroRipple(heroKey)) {
        Box(
            modifier = Modifier
                .tabStagger(index, TabMotion.COVER_STEP_MS, TabMotion.COVER_MS, risePx = 0f, slidePx = 56f, stepCap = Int.MAX_VALUE)
                .size(u * 456f)
                .heroAnchor(heroKey, coverAnchor)
                .clip(coverShape)
                .background(Color(0xFF1A1A20))
                .combinedClickable(onClick = { if (startHero()) onClick() }, onLongClick = onLongClick),
        ) {
            AlbumArt(
                uri = album.artworkUri,
                contentDescription = album.title,
                modifier = Modifier.fillMaxSize(),
                shape = coverShape,
                targetSizeDp = u * 456f,
                cacheKey = album.id.toString(),
                keepLoading = true,
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = u * 26f, bottom = u * 36f)
                    .size(u * 96f)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xFFA9DCEF))
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CupertinoIcons.Play, "Lire ${album.title}", tint = Color(0xFF0E1A1F), modifier = Modifier.size(u * 52f))
            }
        }
        Column(Modifier.padding(top = u * 14f).then(textRise)) {
            Text(
                album.title, color = Color.White, fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold,
                fontSize = designSp(40f), lineHeight = designSp(48f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heroAnchor(heroKey, titleAnchor, HeroState.ROLE_TITLE),
            )
            Text(
                album.artist, color = Color(0xFF8B8B95), fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold,
                fontSize = designSp(34f), lineHeight = designSp(42f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heroAnchor(heroKey, subAnchor, HeroState.ROLE_SUB),
            )
            if (third != null) {
                Text(
                    third, color = Color(0xFF6F6F79), fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold,
                    fontSize = designSp(30f), lineHeight = designSp(36f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heroSideLine(heroKey),
                )
            }
        }
    }
}
