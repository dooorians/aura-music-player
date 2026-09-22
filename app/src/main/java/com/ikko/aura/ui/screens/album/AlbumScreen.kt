package com.ikko.aura.ui.screens.album

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.AudioFormat
import com.ikko.aura.data.model.Track
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.AddToPlaylistDialog
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.FillableIcon
import com.ikko.aura.ui.components.AuraButton
import com.ikko.aura.ui.components.AuraIconButton
import com.ikko.aura.ui.components.ButtonTone
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LocalPageTheme
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.AlbumUiState
import com.ikko.aura.viewmodel.FileTags
import com.ikko.aura.viewmodel.AlbumViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

private val PAGE_PADDING = 22.dp
private val ART_SIZE = 96.dp
private val ART_SHAPE = RoundedCornerShape(24.dp)
private val ACTION_HEIGHT = 44.dp
private val ROW_SHAPE = RoundedCornerShape(20.dp)

@Composable
fun AlbumScreen(
    albumId: String,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    albumViewModel: AlbumViewModel = hiltViewModel(),
) {
    val uiState by albumViewModel.uiState.collectAsStateWithLifecycle()
    val page by albumViewModel.page.collectAsStateWithLifecycle()
    var showAddToPlaylist by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Narrowed to the two things a row cares about. Collecting the whole player state here would
    // recompose the page twice a second for the progress bar's sake. Seeded from the current state:
    // starting from "nothing playing" made the playing row light up a beat after the page appeared.
    val nowPlaying by remember(playerViewModel) {
        playerViewModel.uiState.map { it.currentTrack?.id to it.isPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = playerViewModel.uiState.value.let { it.currentTrack?.id to it.isPlaying },
    )

    // Nothing of the page is drawn until its colours and tags are ready — a few frames, inside the
    // transition that brought us here — so it appears whole instead of assembling itself: buttons
    // changing colour, a caption arriving and pushing the list down, were what read as flicker.
    val current = page
    if (current == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val theme = current.theme
    val fileTags = current.tags

    // The background behind this page is lit with this album, not with what is playing.
    val pageTheme = LocalPageTheme.current
    DisposableEffect(theme) {
        pageTheme.value = theme
        onDispose { if (pageTheme.value === theme) pageTheme.value = null }
    }

    val accent = rememberUpdatedState(theme.accent)
    val accentSoft = rememberUpdatedState(theme.accentSoft)

    MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
        GradientScreenScaffold(applyStatusBarInset = false) {
            Box(modifier = Modifier.fillMaxSize()) {
                AlbumGround(ground = theme.solid, listState = listState)
                run {

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(key = "header") {
                        AlbumHeader(
                            uiState = uiState,
                            fileTags = fileTags,
                            accentSoft = accentSoft,
                            onPlayAll = {
                                if (uiState.tracks.isNotEmpty()) {
                                    playerViewModel.playQueue(uiState.tracks, 0)
                                }
                            },
                            onShuffle = {
                                if (uiState.tracks.isNotEmpty()) {
                                    playerViewModel.playQueue(uiState.tracks.shuffled(), 0)
                                }
                            },
                            onFavorite = albumViewModel::toggleFavorite,
                            onMore = { showAddToPlaylist = true },
                        )
                    }
                    itemsIndexed(uiState.tracks, key = { _, track -> track.id }) { index, track ->
                        AlbumTrackRow(
                            number = index + 1,
                            track = track,
                            albumArtist = uiState.artist,
                            isCurrent = nowPlaying.first == track.id,
                            isPlaying = nowPlaying.second,
                            accent = accent,
                            onClick = {
                                playerViewModel.playQueue(uiState.tracks, index)
                            },
                            onLongClick = { menu.show(track) },
                        )
                    }
                }

                }
                StatusBarScrim(solid = theme.solid, listState = listState)
            }
        }

        if (showAddToPlaylist) {
            AddToPlaylistDialog(
                trackIds = uiState.tracks.map { it.id },
                onDismiss = { showAddToPlaylist = false },
            )
        }
    }
}

/**
 * What turns the background behind this page into an album page: the aurora shows through at the top,
 * and a gradient over it gives way to one flat, dark shade of the record below.
 *
 * It rides up with the header as the list scrolls — the aurora is on a surface of its own behind the
 * window and cannot move, so the ground is what slides over it, and by the time the header has gone
 * the whole screen is the flat colour. Drawn as three fills that never change, so scrolling costs
 * the translation of one layer.
 */
@Composable
private fun AlbumGround(ground: Color, listState: LazyListState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = -listState.headerScrollPx(GROUND_TRAVEL_PX) }
            .drawBehind {
                val fadeStart = GROUND_CLEAR_DP.dp.toPx()
                val fadeEnd = GROUND_SOLID_DP.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to ground.copy(alpha = 0f),
                        0.42f to ground.copy(alpha = 0.38f),
                        0.78f to ground.copy(alpha = 0.86f),
                        1f to ground,
                        startY = fadeStart,
                        endY = fadeEnd,
                    ),
                    topLeft = Offset(0f, fadeStart),
                    size = Size(size.width, fadeEnd - fadeStart),
                )
                drawRect(ground, topLeft = Offset(0f, fadeEnd), size = Size(size.width, size.height * 3f))
            },
    )
}

/** Where the aurora is fully clear, and where the flat colour is fully there, from the top. */
private const val GROUND_CLEAR_DP = 170
private const val GROUND_SOLID_DP = 360

/** Scrolled past the header: far enough that the flat colour covers the whole screen. */
private const val GROUND_TRAVEL_PX = 2400f

/** How far the list has scrolled, in pixels, for anything that should ride along with the header. */
private fun LazyListState.headerScrollPx(fallback: Float): Float =
    if (firstVisibleItemIndex == 0) firstVisibleItemScrollOffset.toFloat() else fallback

/**
 * Keeps the clock and battery readable once rows scroll up under the status bar: nothing at rest,
 * the resting colour fading in as the header leaves.
 */
@Composable
private fun StatusBarScrim(solid: Color, listState: LazyListState) {
    val height = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 18.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                alpha = (listState.headerScrollPx(Float.MAX_VALUE) / SCRIM_RAMP_PX).coerceIn(0f, 1f)
            }
            .drawWithCache {
                val brush = Brush.verticalGradient(
                    0f to solid.copy(alpha = 0.94f),
                    1f to solid.copy(alpha = 0f),
                )
                onDrawBehind { drawRect(brush) }
            },
    )
}

private const val SCRIM_RAMP_PX = 260f

@Composable
private fun AlbumHeader(
    uiState: AlbumUiState,
    fileTags: FileTags,
    accentSoft: State<Color>,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val meta = remember(uiState.tracks, fileTags) { albumMeta(uiState.tracks, fileTags) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PAGE_PADDING, end = PAGE_PADDING, top = statusTop + 10.dp, bottom = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.shadow(
                    elevation = 22.dp,
                    shape = ART_SHAPE,
                    ambientColor = Color.Black.copy(alpha = 0.55f),
                    spotColor = Color.Black.copy(alpha = 0.65f),
                ),
            ) {
                AlbumArt(
                    uri = uiState.tracks.firstOrNull()?.artworkUri,
                    contentDescription = uiState.title,
                    shape = ART_SHAPE,
                    modifier = Modifier.size(ART_SIZE),
                    targetSizeDp = ART_SIZE,
                    cacheKey = uiState.tracks.firstOrNull()?.albumId?.toString(),
                )
            }
            Spacer(Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                Text(
                    text = uiState.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    lineHeight = 28.sp,
                    color = AuraColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = uiState.artist,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accentSoft.value,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraColors.TextSecondary.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayAllButton(onClick = onPlayAll)
            ShuffleButton(onClick = onShuffle)
            Spacer(Modifier.weight(1f))
            CircleAction(onClick = onFavorite) {
                FillableIcon(
                    outline = CupertinoIcons.Star,
                    filled = CupertinoIcons.StarFilled,
                    active = uiState.isFavorite,
                    contentDescription = "Favori",
                    tint = Color.White,
                    modifier = Modifier.size(21.dp),
                )
            }
            CircleAction(onClick = onMore) {
                Icon(
                    CupertinoIcons.MoreVertical,
                    contentDescription = "Plus d'options",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayAllButton(onClick: () -> Unit) {
    AuraButton(
        onClick = onClick,
        tone = ButtonTone.Primary,
        minHeight = ACTION_HEIGHT,
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) {
        Icon(CupertinoIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(text = "Lire tout", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ShuffleButton(onClick: () -> Unit) {
    AuraButton(
        onClick = onClick,
        minHeight = ACTION_HEIGHT,
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        Icon(CupertinoIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(text = "Mélanger", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun CircleAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    AuraIconButton(onClick = onClick, size = ACTION_HEIGHT) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackRow(
    number: Int,
    track: Track,
    albumArtist: String,
    isCurrent: Boolean,
    isPlaying: Boolean,
    accent: State<Color>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // "Wild And Alone (feat. PinkPantheress)" is the title and its credit run together; the page
    // sets them apart, the credit small beneath the title.
    val (shownTitle, subtitle) = remember(track.title, track.artist, albumArtist) {
        val split = splitFeaturing(track.title)
        val credit = split.second?.let { "feat. $it" }
            ?: trackSubtitle(albumArtist, track.artist)?.takeUnless { featuringIsInTitle(track.title, it) }
        split.first to credit
    }
    val currentTint by remember { derivedStateOf { lerp(accent.value, Color.White, 0.30f) } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_PADDING)
            .clip(ROW_SHAPE)
            .then(
                if (isCurrent) Modifier.drawWithCache {
                    onDrawBehind { drawRect(accent.value.copy(alpha = 0.16f)) }
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) {
            if (isCurrent) {
                PlayingBars(color = currentTint, animate = isPlaying)
            } else {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraColors.TextTertiary,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shownTitle,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) currentTint else AuraColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = formatTrackDuration(track.durationMs),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = com.ikko.aura.ui.theme.TABULAR_NUMBERS),
            color = if (isCurrent) AuraColors.TextSecondary else AuraColors.TextTertiary,
            maxLines = 1,
        )
    }
}

/**
 * Three bars, the way a "now playing" marker is drawn everywhere: moving while the music is, frozen at
 * a resting shape when it is paused.
 *
 * Driven by a coarse clock that only runs while it is moving — twenty steps a second is plenty for a
 * 16dp marker. An infinite animation here redrew the page on every frame at 90Hz for as long as a
 * track was playing, which meant the whole screen (and the mini player's blur of it) was being
 * re-rendered continuously for the sake of three small bars.
 */
@Composable
private fun PlayingBars(color: Color, animate: Boolean) {
    val clock = remember { mutableFloatStateOf(0f) }
    if (animate) {
        LaunchedEffect(Unit) {
            while (true) {
                withFrameMillis { clock.floatValue = it / 1000f }
                delay(BARS_STEP_MS)
            }
        }
    }
    Canvas(modifier = Modifier.size(width = 16.dp, height = 15.dp)) {
        val barWidth = 3.dp.toPx()
        val gap = (size.width - barWidth * 3) / 2
        val t = clock.floatValue
        val heights = if (animate) {
            floatArrayOf(wave(t, 6.4f, 0f), wave(t, 4.6f, 1.7f), wave(t, 5.5f, 3.1f))
        } else {
            floatArrayOf(0.5f, 1f, 0.7f)
        }
        heights.forEachIndexed { i, fraction ->
            val barHeight = size.height * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

private const val BARS_STEP_MS = 40L

/** A bar's height between 0.3 and 1, on its own rate and phase. */
private fun wave(t: Float, rate: Float, phase: Float): Float = 0.65f + 0.35f * sin(t * rate + phase)

/** "Électro · 2025 · Lossless": whatever of genre, year and quality the files actually carry. */
private fun albumMeta(tracks: List<Track>, tags: FileTags): String {
    if (tracks.isEmpty()) return ""
    val genre = tags.genre
    val year = tracks.maxOf { it.year }.takeIf { it > 0 } ?: tags.year.takeIf { it > 0 }
    val quality = when {
        tracks.any { it.isHiRes } -> "Hi-Res"
        tracks.any { it.isLossless } -> "Lossless"
        else -> tracks.firstOrNull { it.format != AudioFormat.UNKNOWN }?.format?.name
    }
    // A record with no tags to speak of still gets a line, rather than a gap under the artist.
    val count = if (genre == null && year == null) "${tracks.size} titre${if (tracks.size > 1) "s" else ""}" else null
    return listOfNotNull(genre, year?.toString(), count, quality).joinToString(" · ")
}

private val FEATURING_SEPARATORS = Regex("\\s*(?:;|/|,|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b)\\s*", RegexOption.IGNORE_CASE)

/**
 * What to print under a track's title: nothing when it is by the album's artist, "feat. …" when the
 * album artist is credited alongside others, and the artist as tagged when it is somebody else's.
 */
private fun trackSubtitle(albumArtist: String, trackArtist: String): String? {
    if (trackArtist.isBlank() || trackArtist.equals(albumArtist, ignoreCase = true)) return null
    val parts = trackArtist.split(FEATURING_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
    val others = parts.filterNot { it.equals(albumArtist, ignoreCase = true) }
    return if (others.size < parts.size && others.isNotEmpty()) "feat. ${others.joinToString(", ")}" else trackArtist
}

private val TITLE_FEATURING = Regex(
    "\\s*[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|with)\\s+([^)\\]]+?)\\s*[)\\]]\\s*$",
    RegexOption.IGNORE_CASE,
)

/** The title without a trailing "(feat. X)", and X if there was one. */
private fun splitFeaturing(title: String): Pair<String, String?> {
    val match = TITLE_FEATURING.find(title) ?: return title to null
    return title.removeRange(match.range).trim().ifEmpty { title } to match.groupValues[1]
}

/** "Switchblade (feat. Ty Dolla Sign)" already says so; saying it again underneath is noise. */
private fun featuringIsInTitle(title: String, subtitle: String): Boolean {
    val names = subtitle.removePrefix("feat. ").split(", ").filter { it.isNotBlank() }
    return names.isNotEmpty() && names.all { title.contains(it, ignoreCase = true) }
}

private fun formatTrackDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
