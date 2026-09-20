package com.ikko.aura.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.ikko.aura.ui.theme.AuraColorMode
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

    GradientScreenScaffold {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
        ) {
            item {
                Text(
                    text = "Réglages",
                    style = MaterialTheme.typography.headlineLarge,
                    color = AuraColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }

            item { SectionLabel("Apparence") }
            item {
                // Phrased from the AMOLED side rather than as "animated background off": on this
                // panel that is the choice being made, and it is the one with a measurable effect
                // — library scrolling drops from ~5% janky frames to 0.3% with the backdrop off,
                // since the blurred cover is a full-screen layer Haze then re-samples every frame.
                SettingsSwitchRow(
                    title = "Fond AMOLED noir",
                    subtitle = "Noir pur au lieu du dégradé tiré de la pochette — plus fluide et moins gourmand en batterie",
                    checked = uiState.colorMode == AuraColorMode.MONOCHROME,
                    onCheckedChange = { amoled ->
                        settingsViewModel.setColorMode(
                            if (amoled) AuraColorMode.MONOCHROME else AuraColorMode.MESH_DYNAMIC,
                        )
                    },
                )
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
                        { Text("Annuler", style = MaterialTheme.typography.labelLarge, color = AuraColors.AccentPink) }
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = AuraColors.TextTertiary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
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
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = AuraColors.TextSecondary)
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
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = AuraColors.TextSecondary)
        }
        trailing?.invoke()
    }
}

@Composable
private fun SettingsInfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = AuraColors.TextPrimary)
        Text(text = value, style = MaterialTheme.typography.bodyLarge, color = AuraColors.TextSecondary)
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
