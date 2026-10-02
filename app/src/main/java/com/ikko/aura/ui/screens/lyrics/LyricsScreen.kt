package com.ikko.aura.ui.screens.lyrics

import com.ikko.aura.ui.components.scrollTopBlur
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.lyrics.LyricLine
import com.ikko.aura.ui.components.AuraIconButton
import com.ikko.aura.ui.components.SweepLyricLine
import com.ikko.aura.ui.components.indexAt
import com.ikko.aura.ui.components.rememberLiveLyricClock
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.LyricsUiState
import com.ikko.aura.viewmodel.LyricsViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** How opaque a line is by its distance, in lines, from the one being sung. Between two entries it is interpolated. */
private val FALLOFF_ALPHA = floatArrayOf(1f, 0.50f, 0.30f, 0.18f, 0.10f)

/** How much a line shrinks at a distance of one line or more from the one being sung. */
private const val INACTIVE_SCALE = 0.90f

/** Where the sung line rests, as a fraction of the page's height from the top. */
private const val ANCHOR = 0.40f

/** How long the page stays where the finger left it before it goes back to following the singing. */
private const val RESUME_AFTER_MS = 2_500L

private val GAP_DOT_SIZE = 11.dp
private val GAP_DOT_GAP = 9.dp
private const val GAP_DOTS = 3
private const val GAP_DOT_REST = 0.28f

/**
 * The lyrics, on the app's own aurora.
 *
 * **Background.** None of its own: the aurora the rest of the app sits on (see AuroraBackground) is drawn behind the
 * window and shows through, in the playing cover's colours. This page used to hide it under a mesh of its own, which
 * made it a different room.
 *
 * **Following.** One clock, read off the player on every frame, chooses the line; one spring moves the whole list to
 * it; and each line's opacity and size are worked out from one shared number, the list's fractional position, in the
 * draw phase. There is no animator per line and nothing recomposes while the page moves, so the movement is a single
 * continuous thing that a new line simply re-aims instead of starting over. Touching the page takes the lead from it;
 * it takes it back [RESUME_AFTER_MS] after the finger leaves.
 */
@Composable
fun LyricsScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    lyricsViewModel: LyricsViewModel = hiltViewModel(),
    isTransitioning: Boolean = false,
) {
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val lyricsState by lyricsViewModel.uiState.collectAsStateWithLifecycle()
    val currentTrack = playerState.currentTrack

    LaunchedEffect(currentTrack?.id) { lyricsViewModel.loadFor(currentTrack) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Composed from the first frame, with the page: held back until the slide was over it left only the buttons on
        // the bare cover for a quarter of a second between the player fading out and the lines arriving.
        Box(Modifier.fillMaxSize()) {
            when (val state = lyricsState) {
                is LyricsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }

                is LyricsUiState.Unavailable -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Paroles indisponibles pour ce titre",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }

                is LyricsUiState.Available -> LyricsPanel(
                    lines = state.lines,
                    livePosition = playerViewModel::livePositionMs,
                    isPlaying = playerState.isPlaying,
                    onSeek = playerViewModel::seekTo,
                )
            }
        }

    }
}

@Composable
private fun LyricsPanel(
    lines: List<LyricLine>,
    livePosition: () -> Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
) {
    val clock = rememberLiveLyricClock(livePosition, isPlaying)
    val listState = rememberLazyListState()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp

    // The line being sung, from the clock: it changes a few times a minute, and that is all that is ever read in
    // composition. Everything that moves every frame is read in the draw phase.
    val activeIndex = remember(lines) { mutableIntStateOf(lines.indexAt(clock.longValue)) }
    LaunchedEffect(lines) {
        snapshotFlow { lines.indexAt(clock.longValue) }
            .distinctUntilChanged()
            .collect { activeIndex.intValue = it }
    }

    // The list's fractional position, which every line's look is a function of. A new line re-aims it from wherever
    // it has got to.
    val focus = remember(lines) { Animatable(activeIndex.intValue.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(lines) {
        snapshotFlow { activeIndex.intValue }.collectLatest { index ->
            focus.animateTo(index.coerceAtLeast(0).toFloat(), spring(dampingRatio = 1f, stiffness = FOCUS_STIFFNESS))
        }
    }

    // Following: the page goes to the sung line unless a finger has it, and goes back to it a while after.
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    val resumes = remember { mutableIntStateOf(0) }
    LaunchedEffect(dragged) {
        if (!dragged) {
            delay(RESUME_AFTER_MS)
            resumes.intValue++
        }
    }
    LaunchedEffect(lines) {
        var first = true
        snapshotFlow { activeIndex.intValue to resumes.intValue }.collectLatest { (index, _) ->
            // Not while a finger is on the page. Its own movement must not be what stops it: only a finger does.
            snapshotFlow { dragged }.first { !it }
            listState.centerOn(index.coerceAtLeast(0), ANCHOR, animate = !first)
            first = false
        }
    }

    val activeStyle = MaterialTheme.typography.headlineMedium.copy(
        fontSize = 24.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Start,
    )
    val inactiveStyle = activeStyle.copy(fontWeight = FontWeight.SemiBold)

    val statusBarTop = with(androidx.compose.ui.platform.LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().scrollTopBlur(topInset = statusBarTop) { 1_000_000f },
        // Room above and below the first and last lines to reach the resting place, so the sung line is where the eye
        // expects it from the first line to the last.
        contentPadding = PaddingValues(
            top = (screenHeightDp * ANCHOR).dp,
            bottom = (screenHeightDp * (1f - ANCHOR)).dp,
            start = 22.dp,
            end = 22.dp,
        ),
    ) {
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            val isActive by remember(index) { derivedStateOf { activeIndex.intValue == index } }

            if (line.isGap) {
                GapDots(
                    line = line,
                    until = lines.getOrNull(index + 1)?.timeMs ?: line.endMs,
                    clock = { clock.longValue },
                    index = index,
                    focus = focus,
                    onClick = { onSeek(line.timeMs) },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp)
                        .graphicsLayer {
                            val distance = abs(index - focus.value)
                            alpha = alphaAt(distance)
                            val s = scaleAt(distance)
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                        .clickable { onSeek(line.timeMs) },
                ) {
                    if (isActive && line.isWordSynced) {
                        SweepLyricLine(
                            line = line,
                            positionProvider = { clock.longValue },
                            style = activeStyle,
                            litColor = Color.White,
                            dimColor = Color.White.copy(alpha = 0.40f),
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = line.background?.let { "${line.text} ${it.text}" } ?: line.text,
                            style = if (isActive) activeStyle else inactiveStyle,
                            color = Color.White,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    line.background?.let { backing ->
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = backing.text,
                            style = (if (isActive) activeStyle else inactiveStyle).copy(fontSize = 17.sp, lineHeight = 22.sp),
                            color = Color.White.copy(alpha = 0.75f),
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** An instrumental break: three dots that fill as it elapses. */
@Composable
private fun GapDots(
    line: LyricLine,
    until: Long,
    clock: () -> Long,
    index: Int,
    focus: Animatable<Float, *>,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(width = GAP_DOT_SIZE * 3 + GAP_DOT_GAP * 2, height = GAP_DOT_SIZE)
                .graphicsLayer {
                    val distance = abs(index - focus.value)
                    // Seen only on or beside the sung line, and growing into place as it becomes it.
                    val presence = (1f - distance).coerceIn(0f, 1f)
                    alpha = alphaAt(distance) * presence
                    val grow = 0.85f + 0.15f * presence
                    scaleX = grow
                    scaleY = grow
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .drawBehind {
                    val span = (until - line.timeMs).coerceAtLeast(1L)
                    val through = ((clock() - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
                    val radius = GAP_DOT_SIZE.toPx() / 2f
                    val stride = (GAP_DOT_SIZE + GAP_DOT_GAP).toPx()
                    repeat(GAP_DOTS) { dot ->
                        val lit = (through * GAP_DOTS - dot).coerceIn(0f, 1f)
                        drawCircle(
                            color = Color.White.copy(alpha = GAP_DOT_REST + (1f - GAP_DOT_REST) * lit),
                            radius = radius,
                            center = Offset(radius + dot * stride, size.height / 2f),
                        )
                    }
                },
        )
    }
}

private const val FOCUS_STIFFNESS = Spring.StiffnessLow * 1.6f

private fun alphaAt(distance: Float): Float {
    val d = distance.coerceIn(0f, FALLOFF_ALPHA.lastIndex.toFloat())
    val i = d.toInt()
    val next = (i + 1).coerceAtMost(FALLOFF_ALPHA.lastIndex)
    return FALLOFF_ALPHA[i] + (FALLOFF_ALPHA[next] - FALLOFF_ALPHA[i]) * (d - i)
}

private fun scaleAt(distance: Float): Float = 1f - (1f - INACTIVE_SCALE) * distance.coerceIn(0f, 1f)

/**
 * Moves the list so that [index] rests at [anchor] of its height. Far from the line it first jumps near, since a line
 * that is not laid out has no position to aim at; then a single spring does the rest.
 */
private suspend fun LazyListState.centerOn(index: Int, anchor: Float, animate: Boolean) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    // The window's own height: viewportStart/EndOffset include the content padding, which would aim far below.
    val viewport = layoutInfo.viewportSize.height
    // Item offsets count from the end of the content padding, so the padding is added back to get a place on the screen.
    val onScreen = item.offset + item.size / 2f - layoutInfo.viewportStartOffset
    val delta = onScreen - viewport * anchor
    if (abs(delta) < 1f) return
    if (animate) animateScrollBy(delta, spring(dampingRatio = 1f, stiffness = SCROLL_STIFFNESS)) else scrollBy(delta)
}

private const val SCROLL_STIFFNESS = Spring.StiffnessMediumLow
