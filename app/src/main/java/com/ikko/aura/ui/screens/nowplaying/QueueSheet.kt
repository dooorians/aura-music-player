package com.ikko.aura.ui.screens.nowplaying

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.screens.queue.QueueList
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.PlayerUiState
import com.ikko.aura.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the sheet's content starts and ends: the queue rows' covers sit here, so the header and the player line up with them. */
private val SHEET_EDGE = 24.dp

private val SheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/** Fast enough that a flick means "go there" whatever side of the middle the sheet was let go on, in px/s. */
private const val FLICK = 800f

/**
 * Where the "À suivre" sheet is: [offset] pixels down from fully open, [hidden] being fully closed. Two
 * resting places and nothing between them. Only the sheet's position lives here — the queue itself stays
 * the viewmodel's.
 */
@Stable
class QueueSheetState(private val scope: CoroutineScope, private val animations: () -> Boolean) {
    private var hidden by mutableFloatStateOf(0f)
    private var offset by mutableFloatStateOf(Float.MAX_VALUE)
    private var job: Job? = null

    /** Whether the sheet is meant to be up; true from the moment it starts opening to the moment it has closed. */
    var wantOpen by mutableStateOf(false)
        private set

    val shown: Float get() = min(offset, hidden)
    val visible: Boolean get() = shown < hidden
    /** 0 closed, 1 open. */
    val progress: Float get() = if (hidden <= 0f) 0f else 1f - shown / hidden

    fun fitTo(px: Float) {
        hidden = px
        if (!wantOpen || offset > px) offset = if (wantOpen) 0f else px
    }

    fun open() = animateTo(0f)
    fun close() = animateTo(hidden)

    /** Closed at once, for when the player itself is leaving. */
    fun snapClosed() {
        job?.cancel()
        wantOpen = false
        offset = hidden
    }

    /** Moves by [delta] pixels, within the two ends; returns how much of it was used. */
    fun dragBy(delta: Float): Float {
        job?.cancel()
        val new = (shown + delta).coerceIn(0f, hidden)
        val used = new - shown
        offset = new
        wantOpen = new < hidden
        return used
    }

    /** Let go at [velocity] px/s (positive is down): to the nearer end, or the one the flick points to. */
    fun settle(velocity: Float) {
        animateTo(if (velocity > FLICK || (velocity > -FLICK && shown > hidden / 2f)) hidden else 0f)
    }

    private fun animateTo(target: Float) {
        job?.cancel()
        wantOpen = target == 0f
        job = scope.launch {
            if (animations()) {
                animate(shown, target, animationSpec = spring(1f, Spring.StiffnessMediumLow)) { v, _ -> offset = v }
            }
            offset = target
        }
    }
}

@Composable
fun rememberQueueSheetState(): QueueSheetState {
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    // The system's own "remove animations": the sheet then jumps between its two places.
    return remember(scope) {
        QueueSheetState(scope) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
    }
}

/**
 * The queue as a sheet over the player: the current track in a compact player, then the whole queue, scrolled so
 * that what comes next is at the top — what has been played is above it. Same window as the player, no glass: a
 * plain surface lit with the cover's colour, so the list reads on any cover.
 */
@Composable
fun QueueSheet(
    state: QueueSheetState,
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    navController: NavHostController,
) {
    val density = LocalDensity.current
    val screenHeightPx = LocalContext.current.resources.displayMetrics.heightPixels
    val topPx = WindowInsets.statusBars.getTop(density) + with(density) { 8.dp.toPx() }
    val sheetHeightPx = screenHeightPx - topPx
    remember(sheetHeightPx) { state.fitTo(sheetHeightPx) }
    val sheetHeight = with(density) { sheetHeightPx.toDp() }

    // Back closes the sheet first, the player second (this handler is registered after the player's own).
    BackHandler(enabled = state.wantOpen) { state.close() }

    // Closed, it is nothing: not a layout, not a layer — the player is composed and drawn exactly as if it were not here.
    if (!state.visible) return

    Box(Modifier.fillMaxSize()) {
        // What lies behind dims as the sheet comes up; a tap on it is a way out.
        Box(
            Modifier
                .fillMaxSize()
                .background(AuraColors.Black.copy(alpha = 0.5f * state.progress))
                .pointerInput(Unit) { detectTapGestures { state.close() } },
        )

        val connection = remember(state) { SheetScrollConnection(state) }

        MediaContextHost(navController = navController, playerViewModel = viewModel) { menu ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(sheetHeight)
                    .offset { IntOffset(0, (topPx + state.shown).roundToInt()) }
                    .clip(SheetShape)
                    .background(AuraColors.Black)
                    .semantics { paneTitle = "À suivre" },
            ) {
                // Handle, title and the playing track: dragging any of it moves the sheet.
                Column(
                    Modifier.draggable(
                        state = rememberDraggableState { state.dragBy(it) },
                        orientation = Orientation.Vertical,
                        onDragStopped = { state.settle(it) },
                    ),
                ) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(AuraColors.TextTertiary))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = SHEET_EDGE, end = SHEET_EDGE),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "À suivre",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = AuraColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = state::close, modifier = Modifier.size(48.dp)) {
                            Icon(CupertinoIcons.ChevronDown, "Fermer la file", tint = AuraColors.TextPrimary)
                        }
                    }
                    CompactPlayer(uiState, viewModel)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(AuraColors.GlassFill))

                if (uiState.queue.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Rien en cours de lecture", style = MaterialTheme.typography.bodyLarge, color = AuraColors.TextSecondary)
                    }
                } else {
                    // Opens on what comes next; the played tracks are above it.
                    val listState = rememberLazyListState(
                        initialFirstVisibleItemIndex = (uiState.queueIndex + 1).coerceIn(0, uiState.queue.lastIndex),
                    )
                    // Follows the playing track when the list was resting on "next" — and only then: someone
                    // browsing the history, or the end of a long queue, is not pulled back.
                    var lastIndex by remember { mutableIntStateOf(uiState.queueIndex) }
                    LaunchedEffect(uiState.queueIndex) {
                        val atNext = listState.firstVisibleItemIndex == lastIndex + 1 && listState.firstVisibleItemScrollOffset == 0
                        lastIndex = uiState.queueIndex
                        val target = (uiState.queueIndex + 1).coerceIn(0, uiState.queue.lastIndex)
                        if (atNext) {
                            if (state.progress == 1f) listState.animateScrollToItem(target) else listState.scrollToItem(target)
                        }
                    }
                    QueueList(
                        uiState = uiState,
                        playerViewModel = viewModel,
                        listState = listState,
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 8.dp,
                            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp,
                        ),
                        onRowLongClick = menu::show,
                        modifier = Modifier.nestedScroll(connection),
                        dimPlayed = true,
                    )
                }
            }
        }
    }
}

/** Hands the list's overscroll to the sheet: pulled down from the top, it closes; pushed up, it comes back first. */
private class SheetScrollConnection(private val state: QueueSheetState) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f && state.shown > 0f) Offset(0f, state.dragBy(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, state.dragBy(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (state.shown <= 0f) return Velocity.Zero
        state.settle(available.y)
        return available
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        state.settle(available.y)
        return available
    }
}

/** The playing track in one row: cover, names, and the three controls that matter. */
@Composable
private fun CompactPlayer(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val track = uiState.currentTrack
    Row(
        Modifier.fillMaxWidth().padding(start = SHEET_EDGE, end = SHEET_EDGE, top = 6.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            uri = track?.artworkUri,
            contentDescription = track?.album,
            shape = AuraShapes.AlbumCover,
            targetSizeDp = 48.dp,
            cacheKey = track?.albumId?.toString(),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track?.title.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = AuraColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track?.artist.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = AuraColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = viewModel::skipPrevious, modifier = Modifier.size(48.dp)) {
            Icon(CupertinoIcons.SkipPrevious, "Précédent", tint = AuraColors.TextPrimary, modifier = Modifier.size(22.dp))
        }
        IconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = if (uiState.isPlaying) CupertinoIcons.Pause else CupertinoIcons.Play,
                contentDescription = if (uiState.isPlaying) "Pause" else "Lecture",
                tint = PlayTint,
                modifier = Modifier.size(28.dp),
            )
        }
        IconButton(onClick = viewModel::skipNext, modifier = Modifier.size(48.dp)) {
            Icon(CupertinoIcons.SkipNext, "Suivant", tint = AuraColors.TextPrimary, modifier = Modifier.size(22.dp))
        }
    }
}
