package com.ikko.aura.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.rememberArtistImage
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.glassSurface
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.RecoSettingsViewModel

/** "Artistes que tu aimes": each with their photo on the left and a way to take them off the list on the right. */
@Composable
fun LikedArtistsScreen(navController: NavHostController, vm: RecoSettingsViewModel = hiltViewModel()) {
    val seeds = vm.seeds.collectAsStateWithLifecycle().value
    ArtistListPage(
        title = "Artistes que tu aimes",
        empty = "Aucun artiste pour l'instant. Choisis-en dans la grille « Choisir mes artistes ».",
        entries = seeds.map { ArtistEntry(it, it) },
        removeLabel = "Retirer",
        onRemove = { entry -> vm.setSeeds(seeds.filterNot { it == entry.id }) },
        footer = "Ils servent de point de départ aux recommandations. En retirer un lui enlève son poids renforcé.",
        onAdd = { navController.navigate(AuraDestination.TastePicker.route) },
        addLabel = "Choisir d'autres artistes",
    )
}

/** "Artistes bloqués": the same list, to let one back. */
@Composable
fun BlockedArtistsScreen(navController: NavHostController, vm: RecoSettingsViewModel = hiltViewModel()) {
    val blocked = vm.blocked.collectAsStateWithLifecycle().value
    ArtistListPage(
        title = "Artistes bloqués",
        empty = "Aucun artiste bloqué. Reste appuyé sur un artiste (dans « Choisir mes artistes » ou une playlist recommandée) pour le bloquer.",
        entries = blocked.map { ArtistEntry(it.key, it.name) },
        removeLabel = "Débloquer",
        onRemove = { entry -> vm.unblock(entry.id) },
        footer = "Ils sont exclus des sept playlists, de l'Accueil et de la file d'Automix.",
    )
}

private data class ArtistEntry(val id: String, val name: String)

@Composable
private fun ArtistListPage(
    title: String,
    empty: String,
    entries: List<ArtistEntry>,
    removeLabel: String,
    onRemove: (ArtistEntry) -> Unit,
    footer: String,
    onAdd: (() -> Unit)? = null,
    addLabel: String = "",
) {
    val u = designUnit()
    GradientScreenScaffold(applyStatusBarInset = false) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHeader(title) {}
            SettingsGroup(footer = footer) {
                if (entries.isEmpty()) {
                    Text(
                        empty, color = Color.White.copy(alpha = 0.6f),
                        style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(32f), lineHeight = designSp(42f)),
                        modifier = Modifier.padding(u * 28f),
                    )
                }
                entries.forEachIndexed { i, entry ->
                    if (i > 0) SettingsDivider()
                    ArtistRow(entry, removeLabel) { onRemove(entry) }
                }
                if (onAdd != null) {
                    if (entries.isNotEmpty()) SettingsDivider()
                    SettingsRow(title = addLabel, icon = CupertinoIcons.Person, onClick = onAdd, trailing = { ChevronTrailing() })
                }
            }
            Spacer(Modifier.height(LocalPlayerPillHeight.current + u * 60f))
        }
    }
}

/** The photo (or the initial) on the left, the name, and a round button on the right that takes the artist off the list. */
@Composable
private fun ArtistRow(entry: ArtistEntry, removeLabel: String, onRemove: () -> Unit) {
    val u = designUnit()
    val photo = rememberArtistImage(entry.name)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = u * 28f, vertical = u * 22f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u * 26f),
    ) {
        Box(Modifier.size(u * 112f).clip(CircleShape).background(Color(0xFF1F1F27)), contentAlignment = Alignment.Center) {
            Text(
                entry.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
                color = Color(0xFFC4C4CD), fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = designSp(44f),
            )
            photo?.let {
                AlbumArt(
                    uri = it, contentDescription = entry.name, shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    modifier = Modifier.fillMaxSize(), targetSizeDp = u * 112f, cacheKey = it.toString(),
                )
            }
        }
        Text(
            entry.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(40f), lineHeight = designSp(48f)),
        )
        Box(
            Modifier.size(u * 88f).glassSurface(CircleShape).clip(CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(CupertinoIcons.Close, removeLabel, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(u * 38f)) }
    }
}
