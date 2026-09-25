package com.ikko.aura.ui.screens.settings

import androidx.compose.foundation.background
import com.ikko.aura.ui.theme.LocalCoverColors
import com.ikko.aura.ui.theme.LibraryTypography
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.BuildConfig
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.scrollTopBlur
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    var showSleepPicker by remember { mutableStateOf(false) }

    if (showSleepPicker) {
        SleepTimerPicker(
            onPick = {
                settingsViewModel.startSleepTimer(it)
                showSleepPicker = false
            },
            onDismiss = { showSleepPicker = false },
        )
    }

    val listState = rememberLazyListState()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Runs under the status bar, its inset inside the list (see HomeScreen).
    GradientScreenScaffold(applyStatusBarInset = false) {
        LazyColumn(
            state = listState,
            // The same blurred top as the library's once the page is scrolled (see topBlur).
            // Past the first row, the list counts as fully scrolled whatever that row's own offset.
            modifier = Modifier.fillMaxWidth().scrollTopBlur(topInset = statusBar) {
                if (listState.firstVisibleItemIndex > 0) Float.MAX_VALUE else listState.firstVisibleItemScrollOffset.toFloat()
            },
            contentPadding = PaddingValues(top = statusBar, bottom = LocalPlayerPillHeight.current + 12.dp),
        ) {
            item {
                // Set like the other pages' titles (Home's "Favoris", the library's "Bibliothèque"): the same face,
                // size and cover tint, the same margin and header height.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(PAGE_HEADER_HEIGHT)
                        .padding(horizontal = PAGE_MARGIN),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "Réglages",
                        style = LibraryTypography.PageTitle,
                        color = LocalCoverColors.current.tint,
                        maxLines = 1,
                    )
                }
            }

            item { SectionLabel("Paroles") }
            item {
                SettingsSwitchRow(
                    title = "Paroles en ligne",
                    subtitle = "Cherche des paroles synchronisées quand le titre n'a pas de fichier .lrc, puis les garde en mémoire",
                    checked = uiState.onlineLyricsEnabled,
                    onCheckedChange = settingsViewModel::setOnlineLyricsEnabled,
                )
            }

            item { SectionLabel("Performance") }
            item {
                SettingsSwitchRow(
                    title = "Économiseur de batterie",
                    subtitle = "Désactive les animations de fond pour économiser l'énergie",
                    checked = uiState.batterySaverEnabled,
                    onCheckedChange = settingsViewModel::setBatterySaverEnabled,
                )
            }

            item { SectionLabel("Automix") }
            item {
                SettingsSwitchRow(
                    title = "Automix",
                    subtitle = "Analyse le tempo, l'énergie et la structure de chaque titre pour enchaîner en mesure au lieu d'un fondu de longueur fixe",
                    checked = uiState.automixEnabled,
                    onCheckedChange = settingsViewModel::setAutomixEnabled,
                )
            }

            item { SectionLabel("Veille") }
            item {
                SettingsActionRow(
                    title = "Minuteur de veille",
                    subtitle = uiState.sleepMinutesLeft
                        ?.let { "La lecture se met en pause dans $it min" }
                        ?: "Mettre la lecture en pause après un délai",
                    trailing = if (uiState.sleepMinutesLeft != null) {
                        { Text("Annuler", style = LibraryTypography.AlbumArtist, color = Color.White) }
                    } else {
                        null
                    },
                    onClick = {
                        if (uiState.sleepMinutesLeft != null) {
                            settingsViewModel.cancelSleepTimer()
                        } else {
                            showSleepPicker = true
                        }
                    },
                )
            }

            item { SectionLabel("Son") }
            item {
                SettingsActionRow(
                    title = "Égaliseur",
                    subtitle = "Bandes de fréquences et Bass Boost",
                    trailing = null,
                    onClick = { navController.navigate(AuraDestination.Equalizer.route) },
                )
            }

            item { SectionLabel("Bibliothèque") }
            item {
                SettingsActionRow(
                    title = "Rescanner la bibliothèque",
                    subtitle = if (uiState.isScanning) "Scan en cours…" else "Recherche de nouveaux titres sur l'appareil",
                    trailing = if (uiState.isScanning) {
                        { CircularProgressIndicator(modifier = Modifier.height(20.dp), color = AuraColors.TextPrimary) }
                    } else {
                        null
                    },
                    onClick = { if (!uiState.isScanning) settingsViewModel.rescanLibrary() },
                )
            }

            item { SectionLabel("À propos") }
            item {
                SettingsInfoRow(title = "Version", value = BuildConfig.VERSION_NAME)
            }
            item {
                SettingsInfoRow(title = "Appareil", value = "iKKO MindOne Pro")
            }
        }
    }
}

/** The margin and title height of the other pages (see HomeScreen). */
private val PAGE_MARGIN = 21.dp
private val PAGE_HEADER_HEIGHT = 56.dp

// Rows set like the lists on the other pages: the title as a track's (white, bold), the line under it as its
// artist's (the cover's tint), and section labels as the library's count.
@Composable
private fun RowTitle(text: String) {
    Text(text = text, style = LibraryTypography.AlbumTitle, color = Color.White)
}

@Composable
private fun RowSubtitle(text: String) {
    Text(text = text, style = LibraryTypography.AlbumArtist, color = LocalCoverColors.current.tint.copy(alpha = 0.72f))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = LibraryTypography.PageCount,
        color = LocalCoverColors.current.tint.copy(alpha = 0.66f),
        modifier = Modifier.padding(start = PAGE_MARGIN, end = PAGE_MARGIN, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = PAGE_MARGIN, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            RowTitle(title)
            RowSubtitle(subtitle)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AuraColors.Black,
                checkedTrackColor = AuraColors.TextPrimary,
                uncheckedThumbColor = AuraColors.TextSecondary,
                uncheckedTrackColor = AuraColors.GlassFill,
                uncheckedBorderColor = AuraColors.GlassBorder,
            ),
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_MARGIN, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            RowTitle(title)
            RowSubtitle(subtitle)
        }
        trailing?.invoke()
    }
}

@Composable
private fun SettingsInfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_MARGIN, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        RowTitle(title)
        RowSubtitle(value)
    }
}

/**
 * Fixed durations rather than a picker: choosing a sleep timer is a two-second decision made with
 * the lights off, and the difference between 45 and 47 minutes is not one anybody is making.
 */
@Composable
private fun SleepTimerPicker(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AuraShapes.SquircleMedium)
                .background(AuraColors.SurfaceVariantDark)
                .padding(vertical = 10.dp),
        ) {
            Text(
                text = "Minuteur de veille",
                style = MaterialTheme.typography.titleSmall,
                color = AuraColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            )
            listOf(15, 30, 45, 60, 90).forEach { minutes ->
                Text(
                    text = "$minutes minutes",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AuraColors.TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(minutes) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}
