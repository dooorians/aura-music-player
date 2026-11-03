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
import com.ikko.aura.ui.components.LocalHero
import com.ikko.aura.ui.components.heroPageOut
import com.ikko.aura.ui.components.heroRise
import com.ikko.aura.ui.components.suppressEdgeBackRipple

@Composable
fun QueueScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
) {
    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val hero = LocalHero.current

    val sheetCard = com.ikko.aura.LocalSheetCard.current
    androidx.activity.compose.BackHandler(enabled = sheetCard.owner == null || sheetCard.leavingSideways) {
        if (hero.canClose) hero.startClosing()
        navController.popBackStack()
    }

    MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
    GradientScreenScaffold(modifier = Modifier.suppressEdgeBackRipple()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "File d'attente",
                style = MaterialTheme.typography.headlineLarge,
                color = AuraColors.TextPrimary,
                modifier = Modifier.heroRise(0),
            )
            // Only offered when there is something to clear beyond the track playing.
            if (uiState.queue.size > 1) {
                AuraButton(
                    onClick = playerViewModel::clearQueue,
                    tone = ButtonTone.Neutral,
                    minHeight = 36.dp,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.heroRise(1),
                ) {
                    Text(text = "Vider", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        if (uiState.queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().heroRise(1).heroPageOut(), contentAlignment = Alignment.Center) {
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
            modifier = Modifier.heroPageOut().listTopBlur(listState),
            dimPlayed = true,
            animateEntry = true,
        )
    }
    }
}
