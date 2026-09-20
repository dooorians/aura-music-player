package com.ikko.aura.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.viewmodel.ArtistPlayCount
import com.ikko.aura.viewmodel.StatsViewModel

@Composable
fun StatsScreen(
    navController: NavHostController,
    statsViewModel: StatsViewModel = hiltViewModel(),
) {
    val uiState by statsViewModel.uiState.collectAsStateWithLifecycle()

    GradientScreenScaffold {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
        ) {
            item {
                Text(
                    text = "Statistiques",
                    style = MaterialTheme.typography.headlineLarge,
                    color = AuraColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatTile(label = "Titres", value = uiState.totalTracks.toString(), modifier = Modifier.weight(1f))
                    StatTile(label = "Albums", value = uiState.totalAlbums.toString(), modifier = Modifier.weight(1f))
                    StatTile(label = "Artistes", value = uiState.totalArtists.toString(), modifier = Modifier.weight(1f))
                }
            }

            item { Spacer(Modifier.height(12.dp)) }

            item {
                // Sum of every track's length, not time actually listened — the old
                // "Écoute totale" label promised playback history the app doesn't track.
                StatTile(
                    label = "Durée totale",
                    value = formatDuration(uiState.totalDurationMs),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            }

            if (uiState.topArtists.isNotEmpty()) {
                item {
                    Text(
                        text = "Artistes les plus présents",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AuraColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
                items(uiState.topArtists, key = { it.name }) { artist -> TopArtistRow(artist) }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(AuraColors.GlassFill)
            .padding(vertical = 16.dp, horizontal = 12.dp),
    ) {
        Text(text = value, style = MaterialTheme.typography.headlineMedium, color = AuraColors.TextPrimary)
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = AuraColors.TextSecondary)
    }
}

@Composable
private fun TopArtistRow(artist: ArtistPlayCount) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = artist.name, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextPrimary)
        Text(
            text = "${artist.trackCount} titre${if (artist.trackCount > 1) "s" else ""}",
            style = MaterialTheme.typography.bodyMedium,
            color = AuraColors.TextSecondary,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h${minutes.toString().padStart(2, '0')}" else "${minutes}min"
}
