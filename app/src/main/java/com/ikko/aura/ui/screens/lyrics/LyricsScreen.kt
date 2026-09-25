package com.ikko.aura.ui.screens.lyrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.lyrics.LyricLine
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.SweepLyricLine
import com.ikko.aura.ui.components.rememberLyricClock
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.LyricsUiState
import com.ikko.aura.viewmodel.LyricsViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.ui.components.AuraIconButton

@Composable
fun LyricsScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    lyricsViewModel: LyricsViewModel = hiltViewModel(),
) {
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val positionMs by playerViewModel.positionMs.collectAsStateWithLifecycle()
    val lyricsState by lyricsViewModel.uiState.collectAsStateWithLifecycle()
    val currentTrack = playerState.currentTrack

    LaunchedEffect(currentTrack?.id) { lyricsViewModel.loadFor(currentTrack) }

    GradientScreenScaffold {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AuraIconButton(onClick = { navController.popBackStack() }, size = 42.dp) {
                    Icon(CupertinoIcons.ChevronLeft, contentDescription = "Retour", modifier = Modifier.size(20.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Paroles", style = MaterialTheme.typography.titleMedium, color = AuraColors.TextPrimary)
                    // The source is named rather than hidden: these come off three different
                    // hosts with very different timing quality, and when a line lands early
                    // this is the first thing worth knowing.
                    val subtitle = (lyricsState as? LyricsUiState.Available)?.sourceLabel
                        ?: currentTrack?.title
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = AuraColors.TextSecondary, maxLines = 1)
                    }
                }
                AuraIconButton(onClick = { lyricsViewModel.refetch(currentTrack) }, size = 42.dp) {
                    Icon(CupertinoIcons.Refresh, contentDescription = "Rechercher les paroles à nouveau", modifier = Modifier.size(18.dp))
                }
            }

            when (val state = lyricsState) {
                is LyricsUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AuraColors.TextPrimary)
                }
                is LyricsUiState.Unavailable -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Paroles indisponibles pour ce titre",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AuraColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
                is LyricsUiState.Available -> SyncedLyrics(
                    lines = state.lines,
                    positionMs = { positionMs },
                    isPlaying = playerState.isPlaying,
                    onSeek = playerViewModel::seekTo,
                )
            }
        }
    }
}

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    positionMs: () -> Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
) {
    // Read here rather than by the screen: a playback tick recomposes the list alone, not the header above it.
    val position = positionMs()
    val listState = rememberLazyListState()
    val clock = rememberLyricClock(position, isPlaying)

    // The scroll cursor stays on the coarse position: it only has to change when the line
    // does, and driving it off the frame clock would recompose the list ninety times a
    // second to arrive at the same index. The sweep inside the line is the part that
    // needs the fine clock, and it reads it in the draw phase.
    val activeIndex = remember(lines, position) {
        lines.indexOfLast { it.timeMs <= position }
    }
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 200.dp, horizontal = 24.dp),
    ) {
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            when {
                // An instrumental stretch. Three dots rather than an empty row, so a long
                // break reads as "nothing is sung here" instead of as a gap in the file.
                line.isGap -> Text(
                    text = "• • •",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (index == activeIndex) AuraColors.TextSecondary else AuraColors.TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                )
                index == activeIndex -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeek(line.timeMs) }
                        .padding(vertical = 10.dp),
                ) {
                    SweepLyricLine(
                        line = line,
                        positionProvider = { clock.longValue },
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        litColor = AuraColors.TextPrimary,
                        dimColor = AuraColors.TextTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    line.background?.let { backing ->
                        Spacer(Modifier.height(2.dp))
                        SweepLyricLine(
                            line = backing,
                            positionProvider = { clock.longValue },
                            style = MaterialTheme.typography.titleSmall,
                            litColor = AuraColors.TextSecondary,
                            dimColor = AuraColors.TextTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                else -> Text(
                    text = line.background?.let { "${line.text} ${it.text}" } ?: line.text,
                    style = MaterialTheme.typography.titleMedium,
                    color = AuraColors.TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeek(line.timeMs) }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}
