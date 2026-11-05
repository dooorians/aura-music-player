package com.ikko.aura.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.RecoSettingsViewModel

/** "Ton profil": what the algorithm learned, and therefore why it recommends what it does. */
@Composable
fun TasteProfileScreen(navController: NavHostController, vm: RecoSettingsViewModel = hiltViewModel()) {
    val summary = vm.profileSummary.collectAsStateWithLifecycle().value
    val u = designUnit()
    GradientScreenScaffold(applyStatusBarInset = false) {
        Column(Modifier.fillMaxSize().settingsPageBody().verticalScroll(rememberScrollState())) {
            SettingsHeader("Ton profil") {}
            SettingsGroup(footer = "Ce que l'app a appris de tes écoutes, de tes likes et de tes réponses — et pourquoi elle propose ce qu'elle propose.") {
                val s = summary
                if (s == null) {
                    Text(
                        "Lecture du profil…", color = Color.White.copy(alpha = 0.6f),
                        style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(32f)),
                        modifier = Modifier.padding(u * 28f),
                    )
                } else {
                    Block("Tes artistes", s.topArtists.map { (n, w) -> "$n · ${(w * 100).toInt()} %" })
                    SettingsDivider()
                    Block("Tes styles", s.genres.map { (n, w) -> "$n · ${(w * 100).toInt()} %" })
                    SettingsDivider()
                    Block("Tes décennies", s.decades.map { (y, w) -> "${y}s · ${(w * 100).toInt()} %" })
                }
            }
            Spacer(Modifier.height(LocalPlayerPillHeight.current + u * 60f))
        }
    }
}

@Composable
private fun Block(title: String, lines: List<String>) {
    val u = designUnit()
    Column(Modifier.padding(horizontal = u * 28f, vertical = u * 20f)) {
        Text(title, color = Color.White, fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = designSp(36f))
        Spacer(Modifier.height(u * 8f))
        lines.forEach {
            Text(
                it, color = Color.White.copy(alpha = 0.82f), fontFamily = AuraFontFamily,
                fontWeight = FontWeight.Medium, fontSize = designSp(32f), lineHeight = designSp(42f),
            )
        }
        if (lines.isEmpty()) {
            Text("Rien pour l'instant", color = Color.White.copy(alpha = 0.5f), fontFamily = AuraFontFamily, fontSize = designSp(32f))
        }
    }
}
