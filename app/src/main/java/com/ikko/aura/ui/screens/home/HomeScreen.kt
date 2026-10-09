package com.ikko.aura.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.ui.components.AuraButton
import com.ikko.aura.ui.components.ButtonTone
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.ProfileAvatar
import com.ikko.aura.ui.components.scrollTopBlur
import com.ikko.aura.ui.theme.CoverText
import com.ikko.aura.ui.theme.CupertinoIcons
import java.time.LocalTime
import com.ikko.aura.ui.theme.LibraryTypography
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.HomeWidgetsViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.WidgetItem

private val PAGE_MARGIN = 12.dp
private val SECTION_HEADER_HEIGHT = 56.dp
private val GRID_SPACING = 10.dp

/**
 * Modular, customizable Home screen for Aura.
 *
 * Each card is a slot driven by [HomeWidgetsViewModel] and packed using [HomeWidgetGrid]
 * into a multi-span 2-column squircle grid matching the reference DAP aesthetic.
 *
 * Cards can be tapped to navigate or play, long-pressed or configured via the more button
 * to resize, reorder, change content, hide, or delete.
 */
@Composable
fun HomeScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    widgetsViewModel: HomeWidgetsViewModel = hiltViewModel(),
) {
    val uiState by widgetsViewModel.uiState.collectAsStateWithLifecycle()
    val profileImageUri by widgetsViewModel.profileImageUri.collectAsStateWithLifecycle()
    val profileFirstName by widgetsViewModel.profileFirstName.collectAsStateWithLifecycle()
    var editingItem by remember { mutableStateOf<WidgetItem?>(null) }
    var showGlobalSettings by remember { mutableStateOf(false) }

    val scroll = rememberScrollState()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    GradientScreenScaffold(applyStatusBarInset = false) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(top = statusBar + 8.dp),
        ) {
            // Header matching Library page height and typography
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SECTION_HEADER_HEIGHT)
                    .padding(horizontal = PAGE_MARGIN),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The greeting starts at the page margin, and the profile picture sits at the far right end.
                CoverText(
                    text = homeGreeting(profileFirstName),
                    style = LibraryTypography.PageTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                ProfileAvatar(
                    imageUri = profileImageUri,
                    contentDescription = "Ouvrir les réglages du profil",
                    modifier = Modifier.size(36.dp).clickable {
                        navController.navigate(com.ikko.aura.navigation.AuraDestination.Settings.route) {
                            launchSingleTop = true
                        }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            // Modular Widget Grid
            if (uiState.loaded) {
                val shownItems = remember(uiState.items) { uiState.items.filterNot { it.widget.hidden } }
                HomeWidgetGrid(
                    items = shownItems,
                    onWidgetClick = { item ->
                        val route = item.content.openRoute
                        if (route != null) {
                            navController.navigate(route)
                        } else if (!item.content.isEmpty) {
                            playerViewModel.playQueue(item.content.tracks, item.content.startIndex)
                        }

                    },
                    onWidgetPlay = { item ->
                        if (!item.content.isEmpty) {
                            playerViewModel.playQueue(item.content.tracks, item.content.startIndex)
                        }
                    },
                    onWidgetShuffle = { item ->
                        if (!item.content.isEmpty) {
                            playerViewModel.playShuffled(item.content.tracks)
                        }
                    },
                    onWidgetMore = { item ->
                        editingItem = item
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PAGE_MARGIN),
                    spacing = GRID_SPACING,
                )

                Spacer(Modifier.height(20.dp))

                // Footer customize button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PAGE_MARGIN),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    AuraButton(
                        onClick = { showGlobalSettings = true },
                        tone = ButtonTone.Neutral,
                        minHeight = 38.dp,
                    ) {
                        Icon(CupertinoIcons.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Personnaliser l'accueil", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(LocalPlayerPillHeight.current + 24.dp))
        }
    }

    // Modal sheet for editing a single widget (resize, reorder, change content, hide, remove)
    editingItem?.let { currentSnapshot ->
        val liveItem = uiState.items.firstOrNull { it.widget.id == currentSnapshot.widget.id } ?: currentSnapshot
        HomeWidgetEditSheet(
            item = liveItem,
            pickerOptions = uiState.picker,
            onDismiss = { editingItem = null },
            onResize = { newSize -> widgetsViewModel.resize(liveItem.widget.id, newSize) },
            onShift = { delta -> widgetsViewModel.shift(liveItem.widget.id, delta) },
            onReplaceContent = { kind, arg -> widgetsViewModel.replaceContent(liveItem.widget.id, kind, arg) },
            onHide = { widgetsViewModel.hide(liveItem.widget.id) },
            onRemove = { widgetsViewModel.remove(liveItem.widget.id) },
        )
    }

    // Modal sheet for global home customization (add widget, unhide, reset)
    if (showGlobalSettings) {
        HomeGlobalSettingsSheet(
            items = uiState.items,
            pickerOptions = uiState.picker,
            onDismiss = { showGlobalSettings = false },
            onAdd = { kind, arg -> widgetsViewModel.add(kind, arg) },
            onAddGif = { uri -> widgetsViewModel.addGif(uri) },
            onShow = { widgetId -> widgetsViewModel.show(widgetId) },
            onReset = { widgetsViewModel.reset() },
        )
    }
}

private fun homeGreeting(firstName: String): String {
    val name = firstName.trim()
    if (name.isEmpty()) return "Accueil"
    val hour = LocalTime.now().hour
    return when {
        hour >= 18 || hour < 5 -> "Bonsoir, $name"
        hour < 12 -> "Bonjour, $name"
        else -> "On écoute quoi, $name ?"
    }
}
