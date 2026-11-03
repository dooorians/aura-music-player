package com.ikko.aura.ui.screens.settings

import android.content.Intent
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.BuildConfig
import com.ikko.aura.LocalSheetCard
import com.ikko.aura.data.AlbumLayoutMode
import com.ikko.aura.data.TrackLayoutMode
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.LocalEctoPalette
import com.ikko.aura.ui.components.ProfileAvatar
import com.ikko.aura.ui.components.auraMenuSurface
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.heroRipple
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.glassSurface
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

/** The pages the hub opens. */
private enum class SettingsPage(val title: String) {
    APPEARANCE("Apparence"),
    PLAYBACK("Lecture"),
    LIBRARY("Bibliothèque"),
    SOURCES("Sources"),
    RECOMMENDATIONS("Recommandations"),
    PROFILE("Profil"),
    ABOUT("À propos"),
}

// A page comes in as on Android's shared axis (the library's pages do the same): 30 dp from the right, fading in once the
// hub has begun to leave; the hub goes 30 dp to the left and stays faintly in sight under it.
private val AXIS_SHIFT = 30.dp
private const val AXIS_HUB_REST = 0.3f
private const val AXIS_MS = 300

/** Which of the palette's pools tints each hub tile: the light and middle ones, never the deep. */
private val TILE_TONES = intArrayOf(1, 3, 5, 0, 1, 3, 5)

/**
 * The settings: a hub of seven cards (what they are for, and how they stand now), each opening its own page of groups —
 * with small drawings where a choice is easier to see than to read.
 */
@Composable
fun SettingsScreen(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val scope = rememberCoroutineScope()
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    val page = shown?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
    val clip = remember { Animatable(if (page != null) 1f else 0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (shown != null && clip.value < 1f) clip.animateTo(1f, tween(AXIS_MS, easing = FastOutSlowInEasing))
    }
    fun closePage() {
        if (shown == null || closing) return
        closing = true
        scope.launch {
            clip.animateTo(0f, tween(AXIS_MS, easing = FastOutSlowInEasing))
            shown = null
            closing = false
        }
    }
    // The back gesture takes the page back as the finger goes; with no page up it is the screen's own back.
    PredictiveBackHandler(enabled = shown != null && LocalSheetCard.current.owner == null) { events ->
        try {
            events.collect { e -> if (!closing) clip.snapTo(1f - e.progress.coerceIn(0f, 1f)) }
            closePage()
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            clip.animateTo(1f, tween(AXIS_MS, easing = FastOutSlowInEasing))
            throw c
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val c = clip.value
                    translationX = -AXIS_SHIFT.toPx() * c
                    alpha = 1f - (1f - AXIS_HUB_REST) * c
                },
        ) {
            SettingsHub(
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpen = { shown = it.name },
            )
        }
        if (page != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val c = clip.value
                        translationX = (1f - c) * AXIS_SHIFT.toPx()
                        alpha = c
                    }
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
            ) {
                // Its own ground (the same mesh): the hub stays faintly in sight under a page, and must not show through it.
                com.ikko.aura.ui.components.EctoMesh(LocalEctoPalette.current, drifting = false)
                SettingsPageContent(page, settingsViewModel, navController, onBack = ::closePage)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun SettingsHeader(title: String, onBack: () -> Unit) {
    val u = designUnit()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = u * 112f, start = u * 40f, end = u * 40f, bottom = u * 24f),
    ) {
        Text(
            title,
            // The translucent title of Home and the library: white added to what is behind, at 42 %.
            color = Color.White,
            style = TextStyle(
                fontFamily = AuraFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = designSp(84f),
                lineHeight = designSp(96f),
                letterSpacing = designSp(84f * -0.03f),
            ),
            modifier = Modifier.heroRipple(fast = true, blend = androidx.compose.ui.graphics.BlendMode.Plus, baseAlpha = { 0.42f }),
        )
    }
}

@Composable
private fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val u = designUnit()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsHeader(title, onBack)
        content()
        Spacer(Modifier.height(LocalPlayerPillHeight.current + u * 60f))
    }
}

@Composable
private fun SettingsHub(settingsViewModel: SettingsViewModel, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val amoled by settingsViewModel.amoledBackgroundEnabled.collectAsStateWithLifecycle()
    val glass by settingsViewModel.miniPlayerGlass.collectAsStateWithLifecycle()
    val firstName by settingsViewModel.profileFirstName.collectAsStateWithLifecycle()
    val reco: com.ikko.aura.viewmodel.RecoSettingsViewModel = hiltViewModel()
    val recoOn by reco.consent.collectAsStateWithLifecycle()
    val palette = LocalEctoPalette.current
    fun on(b: Boolean) = if (b) "activé" else "désactivé"

    PageScaffold("Réglages", onBack) {
        SettingsGroup {
            SettingsPage.entries.forEachIndexed { i, p ->
                if (i > 0) SettingsDivider()
                val (icon, summary) = when (p) {
                    SettingsPage.APPEARANCE -> CupertinoIcons.Album to
                        (if (amoled) "Fond noir · " else "") + "mini-lecteur " + if (glass) "en verre" else "en barre"
                    SettingsPage.PLAYBACK -> CupertinoIcons.Headphones to
                        "Automix ${on(uiState.automixEnabled)} · paroles en ligne ${on(uiState.onlineLyricsEnabled)}"
                    SettingsPage.LIBRARY -> CupertinoIcons.Library to
                        "Albums : ${uiState.albumLayoutMode.label.lowercase()} · titres : ${uiState.trackLayoutMode.label.lowercase()}"
                    SettingsPage.SOURCES -> CupertinoIcons.PlaylistMusic to "YouTube Music ${on(uiState.ytmEnabled)}"
                    SettingsPage.RECOMMENDATIONS -> CupertinoIcons.AutoAwesome to "Personnalisées : ${on(recoOn)}"
                    SettingsPage.PROFILE -> CupertinoIcons.Person to firstName.ifBlank { "Ton prénom et ta photo" }
                    SettingsPage.ABOUT -> CupertinoIcons.Gear to "Version ${BuildConfig.VERSION_NAME}"
                }
                SettingsRow(
                    title = p.title, summary = summary, icon = icon,
                    iconTint = palette.cols[TILE_TONES[i % TILE_TONES.size]],
                    onClick = { onOpen(p) },
                    trailing = { ChevronTrailing() },
                )
            }
        }
    }
}

@Composable
private fun SettingsPageContent(page: SettingsPage, vm: SettingsViewModel, navController: NavHostController, onBack: () -> Unit) {
    PageScaffold(page.title, onBack) {
        when (page) {
            SettingsPage.APPEARANCE -> AppearancePage(vm)
            SettingsPage.PLAYBACK -> PlaybackPage(vm, navController)
            SettingsPage.LIBRARY -> LibraryPage(vm)
            SettingsPage.SOURCES -> SourcesPage(vm, navController)
            SettingsPage.RECOMMENDATIONS -> RecommendationsPage(navController)
            SettingsPage.PROFILE -> ProfilePage(vm)
            SettingsPage.ABOUT -> AboutPage()
        }
    }
}

// --- the pages ---

@Composable
private fun AppearancePage(vm: SettingsViewModel) {
    val amoled by vm.amoledBackgroundEnabled.collectAsStateWithLifecycle()
    val glass by vm.miniPlayerGlass.collectAsStateWithLifecycle()
    val button by vm.pageSwitchButton.collectAsStateWithLifecycle()
    SettingsGroup(
        title = "Mini-lecteur",
        footer = if (glass) "Une pastille de verre flotte au-dessus du bas de l'écran. Touche-la : le lecteur s'ouvre comme avec la barre."
        else "La barre noire collée au bas de l'écran.",
    ) { MiniPlayerChooser(glass, vm::setMiniPlayerGlass) }
    SettingsGroup(
        title = "Passer d'Accueil à Bibliothèque",
        footer = if (button) "Un bouton à côté du lecteur change de page, sans glissement."
        else "Tu balaies de gauche à droite ; le lecteur est pleine largeur.",
    ) { PageSwitchChooser(button, vm::setPageSwitchButton) }
    SettingsGroup(title = "Fond") {
        SettingsSwitchRow(
            title = "Fond noir AMOLED",
            summary = "Noir pur, sans couleurs",
            detail = "Désactive le fond coloré et utilise un noir pur : économise l'énergie sur les écrans OLED.",
            checked = amoled, onCheckedChange = vm::setAmoledBackgroundEnabled,
        )
    }
}

@Composable
private fun PlaybackPage(vm: SettingsViewModel, navController: NavHostController) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    var showSleepPicker by remember { mutableStateOf(false) }
    if (showSleepPicker) {
        SleepTimerPicker(onPick = { vm.startSleepTimer(it); showSleepPicker = false }, onDismiss = { showSleepPicker = false })
    }
    SettingsGroup(title = "Enchaînement") {
        AutomixDiagram(ui.automixEnabled)
        SettingsDivider()
        SettingsSwitchRow(
            title = "Automix",
            summary = "Enchaîne les titres en mesure",
            detail = "Analyse le tempo, l'énergie et la structure de chaque titre pour enchaîner en mesure, au lieu d'un fondu de longueur fixe.",
            checked = ui.automixEnabled, onCheckedChange = vm::setAutomixEnabled,
        )
    }
    SettingsGroup(title = "Paroles") {
        SettingsSwitchRow(
            title = "Paroles en ligne",
            summary = "Cherchées quand il n'y a pas de fichier .lrc",
            detail = "Cherche des paroles synchronisées quand le titre n'a pas de fichier .lrc, puis les garde en mémoire.",
            checked = ui.onlineLyricsEnabled, onCheckedChange = vm::setOnlineLyricsEnabled,
        )
    }
    SettingsGroup(title = "Écoute") {
        SettingsRow(
            title = "Minuteur de veille",
            summary = ui.sleepMinutesLeft?.let { "Pause dans $it min" } ?: "Mettre la lecture en pause après un délai",
            icon = CupertinoIcons.ClockFilled,
            onClick = { if (ui.sleepMinutesLeft != null) vm.cancelSleepTimer() else showSleepPicker = true },
            trailing = { if (ui.sleepMinutesLeft != null) Text("Annuler", color = settingsAccent(), style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(32f))) else ChevronTrailing() },
        )
        SettingsDivider()
        SettingsRow(
            title = "Égaliseur", summary = "Bandes de fréquences et Bass Boost",
            icon = CupertinoIcons.Sort,
            onClick = { navController.navigate(AuraDestination.Equalizer.route) },
            trailing = { ChevronTrailing() },
        )
    }
    val lastFm = hiltViewModel<com.ikko.aura.viewmodel.LastFmViewModel>().lastFm
    val lastFmUser by lastFm.user.collectAsStateWithLifecycle()
    val lastFmPending by lastFm.pending.collectAsStateWithLifecycle()
    var showLastFm by remember { mutableStateOf(false) }
    SettingsGroup(title = "Last.fm", footer = "Chaque titre écouté à moitié (ou 4 minutes) est envoyé automatiquement. Sans réseau, il part à la prochaine écoute.") {
        SettingsRow(
            title = lastFmUser?.let { "Connecté : $it" } ?: "Se connecter à Last.fm",
            summary = when {
                lastFmUser == null -> "Envoyer tes écoutes sur ton profil"
                lastFmPending > 0 -> "$lastFmPending écoute${if (lastFmPending > 1) "s" else ""} en attente"
                else -> "Scrobbling automatique"
            },
            icon = CupertinoIcons.Radio,
            onClick = { if (lastFmUser == null) showLastFm = true else lastFm.logout() },
            trailing = {
                if (lastFmUser != null) Text("Déconnecter", color = settingsAccent(), style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(32f)))
                else ChevronTrailing()
            },
        )
    }
    if (showLastFm) LastFmDialog(lastFm) { showLastFm = false }
}

@Composable
private fun LibraryPage(vm: SettingsViewModel) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    SettingsGroup(title = "Albums") { AlbumLayoutChooser(ui.albumLayoutMode, vm::setAlbumLayoutMode) }
    SettingsGroup(title = "Titres") { TrackLayoutChooser(ui.trackLayoutMode, vm::setTrackLayoutMode) }
    SettingsGroup(title = "Fichiers") {
        SettingsRow(
            title = "Rescanner la bibliothèque",
            summary = if (ui.isScanning) "Scan en cours…" else "Chercher de nouveaux titres sur l'appareil",
            icon = CupertinoIcons.Refresh,
            onClick = { if (!ui.isScanning) vm.rescanLibrary() },
            trailing = if (ui.isScanning) {
                { CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp) }
            } else null,
        )
    }
}

@Composable
private fun SourcesPage(vm: SettingsViewModel, navController: NavHostController) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val songs by vm.downloadedSongs.collectAsStateWithLifecycle()
    val bytes by vm.downloadedBytes.collectAsStateWithLifecycle()
    val cacheBytes by vm.streamCacheBytes.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshStreamCacheSize() }
    var confirmDownloads by remember { mutableStateOf(false) }
    var confirmCache by remember { mutableStateOf(false) }

    SettingsGroup(title = "En ligne") {
        SettingsSwitchRow(
            title = "YouTube Music",
            summary = "Non officiel · audio seulement, sans compte",
            detail = "Recherche et écoute des titres en flux, audio uniquement, sans compte. Non autorisé par YouTube et susceptible de cesser de fonctionner. Désactivé, plus rien n'est lu ni cherché.",
            checked = ui.ytmEnabled, onCheckedChange = vm::setYtmEnabled,
        )
    }

    val sourcesViewModel: com.ikko.aura.viewmodel.SourcesViewModel = hiltViewModel()
    val configs by sourcesViewModel.configs.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<com.ikko.aura.source.remote.SourceConfigEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    SettingsGroup(title = "Tes serveurs", footer = "Un serveur de musique à toi (Navidrome, Subsonic…), cherché et lu comme le reste.") {
        configs.forEach { config ->
            SettingsRow(
                title = config.name,
                summary = "${com.ikko.aura.source.remote.RemoteKind.valueOf(config.kind).label} · ${config.baseUrl.removePrefix("http://").removePrefix("https://")}",
                onClick = { editing = config },
                trailing = { IosSwitch(config.enabled) { sourcesViewModel.setEnabled(config.id, it) } },
            )
            SettingsDivider()
        }
        SettingsRow(
            title = "Priorité des sources", summary = "Glisse-les pour choisir celle qui passe en premier",
            onClick = { navController.navigate(AuraDestination.SourceOrder.route) }, trailing = { ChevronTrailing() },
        )
        SettingsDivider()
        SettingsRow(
            title = "Ajouter une source", icon = CupertinoIcons.Add,
            onClick = { adding = true },
        )
    }
    if (adding) {
        SourceDialog(
            initial = null, onDismiss = { adding = false }, onTest = sourcesViewModel::test,
            onSave = { kind, name, url, user, password -> sourcesViewModel.add(kind, name, url, user, password); adding = false },
            onDelete = null,
        )
    }
    editing?.let { config ->
        SourceDialog(
            initial = config, onDismiss = { editing = null }, onTest = sourcesViewModel::test,
            onSave = { _, name, url, user, password -> sourcesViewModel.update(config.id, name, url, user, password); editing = null },
            onDelete = { sourcesViewModel.remove(config.id); editing = null },
        )
    }

    // What takes room on the device, and can be given back: at the foot, in red, and asked twice.
    SettingsGroup(title = "Stockage") {
        SettingsRow(
            title = "Supprimer les téléchargements",
            summary = if (songs == 0) "Aucun titre téléchargé"
            else "$songs titre${if (songs > 1) "s" else ""} · ${bytes / (1024 * 1024)} Mo sur l'appareil",
            danger = true, icon = CupertinoIcons.Delete, iconTint = Color(0xFF8E3A36),
            onClick = { if (songs > 0) confirmDownloads = true },
        )
        SettingsDivider()
        SettingsRow(
            title = "Vider le cache des flux",
            summary = "${cacheBytes / (1024 * 1024)} Mo gardés pour réécouter sans réseau",
            detail = "512 Mo au plus. Ce n'est pas un téléchargement : c'est effacé tout seul.",
            danger = true, icon = CupertinoIcons.Delete, iconTint = Color(0xFF8E3A36),
            onClick = { confirmCache = true },
        )
    }
    if (confirmDownloads) {
        AlertDialog(
            onDismissRequest = { confirmDownloads = false },
            title = { Text("Supprimer tous les téléchargements ?") },
            text = { Text("Les fichiers sont effacés de l'appareil. Les titres restent dans la bibliothèque et se lisent en flux.") },
            confirmButton = { TextButton(onClick = { confirmDownloads = false; vm.deleteAllDownloads() }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDownloads = false }) { Text("Annuler") } },
        )
    }
    if (confirmCache) {
        AlertDialog(
            onDismissRequest = { confirmCache = false },
            title = { Text("Vider le cache des flux ?") },
            text = { Text("Les titres écoutés en flux devront être rechargés pour être réécoutés sans réseau.") },
            confirmButton = { TextButton(onClick = { confirmCache = false; vm.clearStreamCache() }) { Text("Vider") } },
            dismissButton = { TextButton(onClick = { confirmCache = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun RecommendationsPage(navController: NavHostController) {
    val vm: com.ikko.aura.viewmodel.RecoSettingsViewModel = hiltViewModel()
    val consent by vm.consent.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val seeds by vm.seeds.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    SettingsGroup(title = "Personnalisation") {
        SettingsSwitchRow(
            title = "Recommandations personnalisées",
            summary = if (consent) "Cherchées sur les sources que tu utilises" else "Faites avec ta bibliothèque seulement",
            detail = "Cherche de nouveaux titres sur les sources que tu utilises (YouTube Music, JioSaavn, tes serveurs) pour " +
                "Découverte, Dans ton style, Mix du jour, Ça bouge, Nouveautés, Chill et Nuit. Des noms d'artistes et de titres tirés " +
                "de ce que tu écoutes sont envoyés à ces sources ; ton profil reste sur l'appareil. Désactivé, rien n'est envoyé et " +
                "ces playlists viennent de ta bibliothèque.",
            checked = consent, onCheckedChange = vm::setConsent,
        )
        if (consent) {
            SettingsDivider()
            SettingsRow(
                title = "Actualiser maintenant",
                summary = if (refreshing) "Mise à jour en cours…" else "Refait les sept playlists sans effacer ce qui a été appris",
                icon = CupertinoIcons.Refresh,
                onClick = { if (!refreshing) vm.refresh() },
            )
        }
    }
    SettingsGroup(title = "Tes goûts") {
        SettingsRow(
            title = "Choisir mes artistes",
            summary = "Une grille d'artistes à toucher : l'app apprend ce que tu aimes",
            icon = CupertinoIcons.Person,
            onClick = { navController.navigate(AuraDestination.TastePicker.route) }, trailing = { ChevronTrailing() },
        )
        SettingsDivider()
        SettingsRow(
            title = "Artistes que tu aimes",
            summary = if (seeds.isEmpty()) "Aucun · aide les recommandations à démarrer" else "${seeds.size} artiste${if (seeds.size > 1) "s" else ""} · photos et retrait",
            onClick = { navController.navigate(AuraDestination.LikedArtists.route) }, trailing = { ChevronTrailing() },
        )
        SettingsDivider()
        SettingsRow(
            title = "Artistes bloqués",
            summary = if (blocked.isEmpty()) "Aucun · bloque un artiste depuis une playlist recommandée"
            else "${blocked.size} artiste${if (blocked.size > 1) "s" else ""}, exclus des sept playlists",
            onClick = { navController.navigate(AuraDestination.BlockedArtists.route) }, trailing = { ChevronTrailing() },
        )
    }
    SettingsGroup(title = "Repartir de zéro") {
        SettingsRow(
            title = "Réinitialiser le profil",
            summary = "Oublie les écoutes et les « je n'aime pas »",
            detail = "Oublie les écoutes enregistrées, les « je n'aime pas » et l'historique des propositions. Les artistes bloqués sont gardés.",
            danger = true, icon = CupertinoIcons.Delete, iconTint = Color(0xFF8E3A36),
            onClick = { confirmReset = true },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Réinitialiser le profil ?") },
            text = { Text("Les écoutes enregistrées, tes « je n'aime pas » et l'historique des propositions sont effacés. Ta bibliothèque, tes favoris, tes playlists et les artistes bloqués ne changent pas.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.reset() }) { Text("Réinitialiser") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ProfilePage(vm: SettingsViewModel) {
    val u = designUnit()
    val context = LocalContext.current
    val imageUri by vm.profileImageUri.collectAsStateWithLifecycle()
    val firstName by vm.profileFirstName.collectAsStateWithLifecycle()
    var edited by remember(firstName) { mutableStateOf(firstName) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        vm.setProfileImageUri(uri.toString())
    }
    SettingsGroup(title = "Toi") {
        Row(
            Modifier.fillMaxWidth().clickable { picker.launch(arrayOf("image/*")) }.padding(horizontal = u * 28f, vertical = u * 26f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(imageUri = imageUri, modifier = Modifier.size(u * 120f), contentDescription = "Photo de profil")
            Column(Modifier.padding(start = u * 28f).weight(1f)) {
                Text(
                    "Photo de profil", color = Color.White,
                    style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(42f), lineHeight = designSp(50f)),
                )
                Text(
                    if (imageUri == null) "Ajouter une photo" else "Toucher pour la changer",
                    color = Color.White.copy(alpha = 0.6f),
                    style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(30f), lineHeight = designSp(38f)),
                )
            }
        }
        SettingsDivider()
        OutlinedTextField(
            value = edited,
            onValueChange = { edited = it; vm.setProfileFirstName(it) },
            label = { Text("Prénom") },
            placeholder = { Text("Ton prénom") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = u * 28f, vertical = u * 22f),
        )
    }
}

@Composable
private fun AboutPage() {
    SettingsGroup(title = "Aura") {
        SettingsRow(title = "Version", trailing = { InfoValue(BuildConfig.VERSION_NAME) })
        SettingsDivider()
        SettingsRow(title = "Appareil", trailing = { InfoValue("iKKO MindOne Pro") })
    }
    // Only the QA variant has something to say here (see QA_INFO in build.gradle.kts): the other builds show nothing.
    if (BuildConfig.IS_QA) {
        val u = designUnit()
        Text(
            BuildConfig.QA_INFO,
            color = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(28f), lineHeight = designSp(36f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = u * 40f, vertical = u * 30f),
        )
    }
}

@Composable
private fun InfoValue(text: String) {
    Text(
        text, color = Color.White.copy(alpha = 0.6f),
        style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(32f), lineHeight = designSp(40f)),
    )
}

/**
 * Fixed durations rather than a picker: choosing a sleep timer is a two-second decision made with the lights off, and the
 * difference between 45 and 47 minutes is not one anybody is making.
 */
@Composable
private fun SleepTimerPicker(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().auraMenuSurface().padding(vertical = 10.dp)) {
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
                    modifier = Modifier.fillMaxWidth().clickable { onPick(minutes) }.padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}
