package com.ikko.aura.ui.screens.queue

import com.ikko.aura.ui.components.listTopBlur
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.components.TrackListItem
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.ui.components.PageHeader
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.keyAt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import com.ikko.aura.ui.components.AuraButton
import com.ikko.aura.ui.components.ButtonTone

@Composable
fun QueueScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
) {
    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()

    MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
    GradientScreenScaffold {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "File d'attente",
                style = MaterialTheme.typography.headlineLarge,
                color = AuraColors.TextPrimary,
            )
            // Only offered when there is something to clear beyond the track playing.
            if (uiState.queue.size > 1) {
                AuraButton(
                    onClick = playerViewModel::clearQueue,
                    tone = ButtonTone.Neutral,
                    minHeight = 36.dp,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    Text(text = "Vider", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        if (uiState.queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Rien en cours de lecture",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AuraColors.TextSecondary,
                )
            }
            return@GradientScreenScaffold
        }

        // Opens on the playing track; what was played before it is above, greyed, a swipe down the list away.
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = uiState.queueIndex.coerceAtLeast(0))
        QueueList(
            uiState = uiState,
            playerViewModel = playerViewModel,
            listState = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = LocalPlayerPillHeight.current + 12.dp),
            onRowLongClick = menu::show,
            modifier = Modifier.listTopBlur(listState),
            dimPlayed = true,
        )
    }
    }
}
