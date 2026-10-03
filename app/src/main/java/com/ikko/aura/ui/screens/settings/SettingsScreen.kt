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
import androidx.compose.runtime.LaunchedEffect
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
import com.ikko.aura.ui.components.AuraSwitch
import com.ikko.aura.ui.components.auraMenuSurface
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

            item { SectionLabel("Automix") }
            item {
                SettingsSwitchRow(
                    title = "Automix",
                    subtitle = "Analyse le tempo, l'énergie et la structure de chaque titre pour enchaîner en mesure au lieu d'un fondu de longueur fixe",
                    checked = uiState.automixEnabled,
                    onCheckedChange = settingsViewModel::setAutomixEnabled,
                )
            }

            item { SectionLabel("Sources") }
            item {
                SettingsSwitchRow(
                    title = "YouTube Music (non officiel)",
                    subtitle = "Recherche et écoute des titres en flux, audio uniquement, sans compte. Non autorisé par YouTube et susceptible de cesser de fonctionner. Désactivé, plus rien n'est lu ni cherché",
                    checked = uiState.ytmEnabled,
                    onCheckedChange = settingsViewModel::setYtmEnabled,
                )
            }

            item {
                val songs by settingsViewModel.downloadedSongs.collectAsStateWithLifecycle()
                val bytes by settingsViewModel.downloadedBytes.collectAsStateWithLifecycle()
                var confirm by remember { mutableStateOf(false) }
                SettingsActionRow(
                    title = "Titres téléchargés",
                    subtitle = if (songs == 0) "Aucun. Télécharge un album ou un titre depuis sa page YouTube Music"
                    else "$songs titre${if (songs > 1) "s" else ""} · ${bytes / (1024 * 1024)} Mo sur l'appareil. Touche pour tout supprimer",
                    trailing = null,
                    onClick = { if (songs > 0) confirm = true },
                )
                if (confirm) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Supprimer tous les téléchargements ?") },
                        text = { Text("Les fichiers sont effacés de l'appareil. Les titres restent dans la bibliothèque et se lisent en flux.") },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirm = false; settingsViewModel.deleteAllDownloads() }) { Text("Supprimer") } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Annuler") } },
                    )
                }
            }
            item {
                val cacheBytes by settingsViewModel.streamCacheBytes.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { settingsViewModel.refreshStreamCacheSize() }
                SettingsActionRow(
                    title = "Vider le cache des flux",
                    subtitle = "${cacheBytes / (1024 * 1024)} Mo gardés pour réécouter sans réseau, 512 Mo au plus. Ce n'est pas un téléchargement : c'est effacé tout seul",
                    trailing = null,
                    onClick = settingsViewModel::clearStreamCache,
                )
            }

            item {
                val sourcesViewModel: com.ikko.aura.viewmodel.SourcesViewModel = hiltViewModel()
                val configs by sourcesViewModel.configs.collectAsStateWithLifecycle()
                var editing by remember { mutableStateOf<com.ikko.aura.source.remote.SourceConfigEntity?>(null) }
                var adding by remember { mutableStateOf(false) }
                androidx.compose.foundation.layout.Column {
                    configs.forEach { config ->
                        SettingsActionRow(
                            title = config.name,
                            subtitle = "${com.ikko.aura.source.remote.RemoteKind.valueOf(config.kind).label} · ${config.baseUrl.removePrefix("http://").removePrefix("https://")}",
                            trailing = { com.ikko.aura.ui.components.AuraSwitch(checked = config.enabled, onCheckedChange = { sourcesViewModel.setEnabled(config.id, it) }) },
                            onClick = { editing = config },
                        )
                    }
                    SettingsActionRow(
                        title = "Priorité des sources",
                        subtitle = "Glisse-les pour choisir celle qui passe en premier",
                        trailing = null,
                        onClick = { navController.navigate(AuraDestination.SourceOrder.route) },
                    )
                    SettingsActionRow(
                        title = "Ajouter une source",
                        subtitle = "Un serveur de musique à toi (Navidrome, Subsonic…), cherché et lu comme le reste",
                        trailing = null,
                        onClick = { adding = true },
                    )
                }
                if (adding) {
                    SourceDialog(
                        initial = null,
                        onDismiss = { adding = false },
                        onTest = sourcesViewModel::test,
                        onSave = { kind, name, url, user, password -> sourcesViewModel.add(kind, name, url, user, password); adding = false },
                        onDelete = null,
                    )
                }
                editing?.let { config ->
                    SourceDialog(
                        initial = config,
                        onDismiss = { editing = null },
                        onTest = sourcesViewModel::test,
                        onSave = { _, name, url, user, password -> sourcesViewModel.update(config.id, name, url, user, password); editing = null },
                        onDelete = { sourcesViewModel.remove(config.id); editing = null },
                    )
                }
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
        AuraSwitch(checked = checked, onCheckedChange = onCheckedChange)
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
                .auraMenuSurface()
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
