package com.ikko.aura.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavHostController
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.heroRipple
import com.ikko.aura.ui.components.heroRise
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.SimilarArtist

/** What an artist's page hands to the page of similar artists it opens (held for the one page that follows). */
object SimilarArtists {
    @Volatile var list: List<SimilarArtist> = emptyList()
}

/** "Artistes similaires": every artist close to the one the page was about, a round picture and a name each. */
@Composable
fun SimilarArtistsScreen(navController: NavHostController) {
    val artists = remember { SimilarArtists.list }
    if (artists.isEmpty()) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val u = designUnit()
    val pageKey = rememberSettingsPageKey()
    val size = designSp(40f)
    val line = designSp(48f)
    val face = remember(size, line) { TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = size, lineHeight = line) }
    GradientScreenScaffold(applyStatusBarInset = false) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().settingsPageBody(),
            contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + u * 60f),
        ) {
            item(key = "similar_header", contentType = "header") {
                SettingsHeader("Artistes similaires") {}
            }
            itemsIndexed(artists, key = { _, it -> it.name }, contentType = { _, _ -> "artist" }) { index, artist ->
                Row(
                    Modifier
                        .heroRipple(pageKey = pageKey)
                        .heroRise(index, rows = true)
                        .fillMaxWidth()
                        .height(u * 138f)
                        .clickable { navController.navigate(AuraDestination.YtmArtistPattern.route(artist.name)) }
                        .padding(horizontal = u * 40f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(u * 28f),
                ) {
                    Box(Modifier.size(u * 116f)) {
                        AlbumArt(
                            uri = artist.pictureUrl?.let(android.net.Uri::parse), contentDescription = artist.name, shape = CircleShape,
                            modifier = Modifier.fillMaxSize(), targetSizeDp = u * 116f, cacheKey = artist.pictureUrl ?: artist.name,
                        )
                    }
                    Text(artist.name, color = androidx.compose.ui.graphics.Color.White, style = face, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
