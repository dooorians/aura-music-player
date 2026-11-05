package com.ikko.aura.ui.screens.equalizer

import com.ikko.aura.ui.components.listTopBlur
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.AuraSwitch
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.ui.components.PageHeader
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.viewmodel.EqBand
import com.ikko.aura.viewmodel.EqualizerViewModel
import java.util.Locale
import kotlin.math.roundToInt
import com.ikko.aura.ui.components.AuraChip

@Composable
fun EqualizerScreen(
    navController: NavHostController,
    equalizerViewModel: EqualizerViewModel = hiltViewModel(),
) {
    val uiState by equalizerViewModel.uiState.collectAsStateWithLifecycle()

    GradientScreenScaffold {
        PageHeader(title = "Égaliseur")

        if (!uiState.available) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Égaliseur indisponible sur cet appareil",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AuraColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            return@GradientScreenScaffold
        }

        val eqListState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(
            state = eqListState,
            modifier = Modifier.fillMaxWidth().weight(1f).listTopBlur(eqListState),
            contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
        ) {
            item(key = "eq_switch", contentType = "switch_row") {
                EqSwitchRow(
                    title = "Égaliseur",
                    subtitle = "Ajuste le rendu sonore de la lecture",
                    checked = uiState.enabled,
                    onCheckedChange = equalizerViewModel::setEnabled,
                )
            }

            if (uiState.presets.isNotEmpty()) {
                item(key = "label_presets", contentType = "section_label") { SectionLabel("Préréglages") }
                item(key = "eq_presets", contentType = "presets_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(uiState.presets.size, key = { uiState.presets[it] }, contentType = { "preset" }) { index ->
                            val label = uiState.presets[index]
                            val isSelected = uiState.selectedPresetIndex == index
                            AuraChip(label = label, selected = isSelected, onClick = { equalizerViewModel.applyPreset(index) })
                        }
                    }
                }
            }

            item(key = "label_bands", contentType = "section_label") { SectionLabel("Bandes de fréquences") }
            items(uiState.bands, key = { it.index }, contentType = { "band" }) { band ->
                EqBandRow(band = band, onLevelChange = { equalizerViewModel.setBandLevel(band.index, it) })
            }

            if (uiState.bassBoostSupported) {
                item(key = "label_bass", contentType = "section_label") { SectionLabel("Bass Boost") }
                item(key = "eq_bass_switch", contentType = "switch_row") {
                    EqSwitchRow(
                        title = "Bass Boost",
                        subtitle = "Renforce les basses fréquences",
                        checked = uiState.bassBoostEnabled,
                        onCheckedChange = equalizerViewModel::setBassBoostEnabled,
                    )
                }
                item(key = "eq_bass_slider", contentType = "slider") {
                    Slider(
                        value = uiState.bassBoostStrength.toFloat(),
                        onValueChange = { equalizerViewModel.setBassBoostStrength(it.roundToInt()) },
                        valueRange = 0f..1000f,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = AuraColors.TextPrimary,
                            activeTrackColor = AuraColors.TextPrimary,
                            inactiveTrackColor = AuraColors.GlassBorder,
                        ),
                    )
                }
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
private fun EqSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = AuraColors.TextSecondary)
        }
        AuraSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun EqBandRow(band: EqBand, onLevelChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatFrequency(band.centerFreqHz),
            style = MaterialTheme.typography.labelLarge,
            color = AuraColors.TextSecondary,
            modifier = Modifier.width(56.dp),
        )
        Slider(
            value = band.levelMillibel.toFloat(),
            onValueChange = { onLevelChange(it.roundToInt()) },
            valueRange = band.minLevelMillibel.toFloat()..band.maxLevelMillibel.toFloat(),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = AuraColors.TextPrimary,
                activeTrackColor = AuraColors.TextPrimary,
                inactiveTrackColor = AuraColors.GlassBorder,
            ),
        )
        Spacer(Modifier.width(4.dp))
        val db = (band.levelMillibel / 100f).roundToInt()
        Text(
            text = "${if (db >= 0) "+" else ""}$db dB",
            style = MaterialTheme.typography.labelLarge,
            color = AuraColors.TextSecondary,
            modifier = Modifier.width(56.dp),
        )
    }
}

private fun formatFrequency(hz: Int): String {
    if (hz < 1000) return "$hz Hz"
    val kHz = hz / 1000.0
    val formatted = if (kHz == kHz.toInt().toDouble()) kHz.toInt().toString() else String.format(Locale.US, "%.1f", kHz)
    return "$formatted kHz"
}
