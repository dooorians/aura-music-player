package com.ikko.aura.ui.screens.taste

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.rememberArtistImage
import com.ikko.aura.taste.PickerArtist
import com.ikko.aura.taste.TastePickerUiState
import com.ikko.aura.taste.TastePickerViewModel
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.AuraButton
import com.ikko.aura.ui.components.ButtonTone
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.LocalEctoPalette
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.glassSurface
import com.ikko.aura.ui.components.heroRipple
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons

private val Ink = Color.White.copy(alpha = 0.62f)
private val Muted = Color.White.copy(alpha = 0.42f)

/**
 * "Tes artistes": a grid of round artist bubbles. A tap picks one (an accent ring and a check), a second tap takes it back; each
 * pick brings the artists close to it into the grid, beside it. Five picks unlock the button.
 */
@Composable
fun TastePickerScreen(navController: NavHostController, viewModel: TastePickerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val consent by viewModel.consent.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) {
            if (consent) android.widget.Toast.makeText(context, "Tes goûts sont pris en compte : les playlists se mettent à jour", android.widget.Toast.LENGTH_LONG).show()
            navController.popBackStack()
        }
    }
    TastePickerContent(state, onToggle = viewModel::toggle, onBlock = viewModel::toggleBlock, onConfirm = viewModel::confirm, onQuery = viewModel::setQuery)
}

@Composable
internal fun TastePickerContent(state: TastePickerUiState, onToggle: (PickerArtist) -> Unit, onBlock: (PickerArtist) -> Unit, onConfirm: () -> Unit, onQuery: (String) -> Unit) {
    val u = designUnit()
    // No mini-player on this page: the bar and the list only keep clear of the system's bottom bar.
    val navBottom = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(this).toDp() }
    val barHeight = u * 210f
    GradientScreenScaffold(applyStatusBarInset = false) {
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = u * 40f, end = u * 40f, bottom = navBottom + barHeight),
                horizontalArrangement = Arrangement.spacedBy(u * 26f),
                verticalArrangement = Arrangement.spacedBy(u * 34f),
            ) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "header") {
                    // The header of the settings pages, as it is: 112 down, 40 in, 84 design px, ExtraBold, 72 % white.
                    Column(Modifier.padding(top = u * 112f, bottom = u * 24f)) {
                        Text(
                            "Tes artistes", color = Color.White,
                            style = TextStyle(
                                fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold,
                                fontSize = designSp(84f), lineHeight = designSp(96f), letterSpacing = designSp(84f * -0.03f),
                            ),
                            modifier = Modifier.heroRipple(fast = true, blend = androidx.compose.ui.graphics.BlendMode.Plus, baseAlpha = { 0.42f }),
                        )
                        Text(
                            "Touche ceux que tu aimes : d'autres, proches de tes choix, arrivent à côté. Reste appuyé sur un artiste pour le bloquer.", color = Muted,
                            style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(34f), lineHeight = designSp(44f)),
                            modifier = Modifier.padding(top = u * 12f),
                        )
                    }
                }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "search") {
                    SearchField(state.query, onQuery, Modifier.padding(bottom = u * 10f))
                }
                if (state.query.isNotBlank() && !state.searching && state.results.isEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "none") {
                        Text(
                            "Aucun artiste trouvé pour « ${state.query.trim()} »", color = Muted,
                            style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(34f), lineHeight = designSp(44f)),
                            modifier = Modifier.padding(vertical = u * 30f),
                        )
                    }
                }
                items(state.shown, key = { it.key }) { artist ->
                    ArtistBubbleItem(
                        artist = artist,
                        selected = artist.key in state.selected,
                        blocked = artist.key in state.blocked,
                        onClick = { onToggle(artist) },
                        onLongClick = { onBlock(artist) },
                        // New bubbles slide to their place and fade in; the others make room, with no jump.
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(320, easing = FastOutSlowInEasing),
                            placementSpec = tween(380, easing = FastOutSlowInEasing),
                            fadeOutSpec = tween(160),
                        ),
                    )
                }
            }
            ConfirmBar(state, onConfirm, Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom), barHeight)
        }
    }
}

/** One round artist: the photo (or the initial), the name under it, and what picking it looks like. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ArtistBubbleItem(
    artist: PickerArtist, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    blocked: Boolean = false, onLongClick: () -> Unit = {},
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val u = designUnit()
    val accent = LocalEctoPalette.current.cols[3]
    // A bubble that comes in grows from a little smaller: its own arrival, on top of the grid's.
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { arrived = true }
    val arrival by animateFloatAsState(if (arrived) 1f else 0.7f, tween(320, easing = FastOutSlowInEasing), label = "bubble-in")
    val press by animateFloatAsState(if (selected) 0.94f else 1f, tween(180, easing = FastOutSlowInEasing), label = "bubble-pick")
    val ring by animateFloatAsState(if (selected) 1f else 0f, tween(180), label = "bubble-ring")
    val block by animateFloatAsState(if (blocked) 1f else 0f, tween(200), label = "bubble-block")
    val danger = Color(0xFFE5484D)
    val photo = rememberArtistImage(artist.name)

    Column(
        modifier
            .graphicsLayer { scaleX = arrival; scaleY = arrival; alpha = ((arrival - 0.7f) / 0.3f).coerceIn(0f, 1f) }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onLongClick() },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(u * 14f),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { scaleX = press; scaleY = press }
                    .clip(CircleShape).background(Color(0xFF1F1F27))
                    .graphicsLayer { alpha = 1f - 0.6f * block }
                    .border(BorderStroke(u * 6f * ring, accent.copy(alpha = ring)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    artist.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
                    color = Color(0xFFC4C4CD), fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = designSp(86f),
                )
                photo?.let {
                    AlbumArt(
                        uri = it, contentDescription = artist.name, shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                        modifier = Modifier.fillMaxSize(), targetSizeDp = u * 316f, cacheKey = it.toString(),
                    )
                }
                // Picked: the picture dims a little under the ring, so that the check reads on any photo.
                if (ring > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f * ring)))
            }
            // Blocked: the bubble is dimmed under a red ring and a crossed-out badge; the name is struck through.
            if (block > 0f) {
                Box(Modifier.fillMaxSize().border(BorderStroke(u * 6f * block, danger.copy(alpha = block)), CircleShape))
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(end = u * 6f, bottom = u * 6f).size(u * 76f)
                        .graphicsLayer { scaleX = block; scaleY = block; alpha = block }
                        .clip(CircleShape).background(danger),
                    contentAlignment = Alignment.Center,
                ) { Icon(CupertinoIcons.Close, "Bloqué", tint = Color.White, modifier = Modifier.size(u * 40f)) }
            }
            if (ring > 0f) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(end = u * 6f, bottom = u * 6f).size(u * 76f)
                        .graphicsLayer { scaleX = ring; scaleY = ring; alpha = ring }
                        .clip(CircleShape).background(accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(CupertinoIcons.Check, null, tint = Color.White, modifier = Modifier.size(u * 40f)) }
            }
        }
        Text(
            artist.name, color = if (blocked) danger.copy(alpha = 0.85f) else if (selected) Color.White.copy(alpha = 0.9f) else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(32f), lineHeight = designSp(40f),
                textDecoration = if (blocked) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            ),
        )
    }
}

/** The counter ("Choisis-en encore 3") with its gauge, and the button beside it, which unlocks at five. */
@Composable
private fun ConfirmBar(state: TastePickerUiState, onConfirm: () -> Unit, modifier: Modifier, height: androidx.compose.ui.unit.Dp) {
    val u = designUnit()
    // Read while measuring the gauge below: its 220 ms fill resizes the gauge alone, the bar is not recomposed.
    val progress = animateFloatAsState(
        (state.selected.size.toFloat() / state.minSelection).coerceIn(0f, 1f), tween(220, easing = FastOutSlowInEasing), label = "progress",
    )
    val accent = LocalEctoPalette.current.cols[3]
    Row(
        modifier.fillMaxWidth().height(height)
            // The bubbles fade under the bar rather than showing through it.
            .background(CONFIRM_BAR_FADE)
            .padding(horizontal = u * 40f, vertical = u * 28f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u * 28f),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u * 16f)) {
            Text(
                when {
                    state.remaining == 0 -> "${state.selected.size} artiste${if (state.selected.size > 1) "s" else ""} choisi${if (state.selected.size > 1) "s" else ""}"
                    state.minSelection == 1 -> "Choisis au moins un artiste"
                    state.remaining == 1 -> "Choisis-en encore 1"
                    else -> "Choisis-en encore ${state.remaining}"
                },
                color = Color.White.copy(alpha = 0.85f), maxLines = 1,
                style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(34f), lineHeight = designSp(42f)),
            )
            // A gauge to the minimum, in the page's accent.
            Box(Modifier.fillMaxWidth().height(u * 10f).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))) {
                Box(
                    Modifier
                        // Modifier.fillMaxWidth(progress.value), read at measure time.
                        .layout { measurable, constraints ->
                            val w = if (constraints.hasBoundedWidth) {
                                Math.round(constraints.maxWidth * progress.value).coerceIn(constraints.minWidth, constraints.maxWidth)
                            } else -1
                            val placeable = measurable.measure(if (w >= 0) constraints.copy(minWidth = w, maxWidth = w) else constraints)
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                        .height(u * 10f).clip(CircleShape).background(accent),
                )
            }
        }
        AuraButton(
            onClick = { if (state.canConfirm) onConfirm() },
            tone = ButtonTone.Primary,
            minHeight = 48.dp,
            modifier = Modifier.graphicsLayer { alpha = if (state.canConfirm) 1f else 0.4f },
        ) {
            if (state.saving) {
                // Saving: a spinner in the button, so it is clear that something is going on.
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(u * 40f), strokeWidth = u * 5f, color = Color.Black.copy(alpha = 0.7f),
                )
            } else {
                Text("Valider", fontWeight = FontWeight.Bold, fontFamily = AuraFontFamily, fontSize = designSp(36f))
            }
        }
    }
}

/** The search field: the glass of the round buttons, a magnifier and a cross to clear. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val u = designUnit()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Row(
        modifier.fillMaxWidth().height(u * 104f).glassSurface(CircleShape).clip(CircleShape).padding(horizontal = u * 34f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u * 20f),
    ) {
        Icon(CupertinoIcons.Search, null, tint = Ink, modifier = Modifier.size(u * 44f))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("Chercher un artiste", color = Muted, style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(36f)))
            }
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(36f), color = Color.White.copy(alpha = 0.9f)),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalEctoPalette.current.cols[3]),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                CupertinoIcons.Close, "Effacer", tint = Ink,
                modifier = Modifier.size(u * 40f).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onQuery("") },
            )
        }
    }
}

/** The confirm bar's fade from the bubbles into its dark foot; one brush, not one per recomposition. */
private val CONFIRM_BAR_FADE = androidx.compose.ui.graphics.Brush.verticalGradient(
    listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f), Color.Black.copy(alpha = 0.9f)),
)
