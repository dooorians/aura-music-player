package com.ikko.aura.ui.playlist.ai

import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikko.aura.ai.playlist.AiStage
import com.ikko.aura.ai.playlist.IntentOrigin
import com.ikko.aura.ai.playlist.Relaxation
import com.ikko.aura.ai.slm.SlmModelState
import com.ikko.aura.data.model.Track
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.AuraButton
import com.ikko.aura.ui.components.ButtonTone
import com.ikko.aura.ui.components.TrackListItem
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.rememberPlayerAccent
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.PlayerViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/*
 * The playlist assistant's sheet. Every measure is one the app already uses, in design px (designUnit, 1080 wide):
 *  - the panel, scrim and entrance are the search's sources sheet (SearchScreen.SourcesSheet): #17171E, 52 px top
 *    corners, the scrim at 62 % in 300 ms, the panel up in 440 ms decelerating, its lines rising 16 px 45 ms apart from
 *    180 ms; the light edge on top is the menus' (MediaContextSheet: 0.8 dp of white at 12 %);
 *  - the handle and the drag that closes it are the queue's (QueueSheet: 36 × 4 dp, a flick of 800 px/s);
 *  - the field is the search field open (112 px pill, #272731, 44 px Bold text, #7F7F89 hint, 80 px glyph boxes), the
 *    chips the search's filter chips (76 px pills, white 10 %, lit in the accent at 22 % when they are what is asked);
 *  - the songs are TrackListItem rows and their covers AlbumArt; the two actions are AuraButtons (the play tile's pale
 *    glass for the main one, the dark glass for the other), as on the taste picker's bar.
 * Nothing animates by recomposing: the panel's travel, the scrim, the drag and the indicator are read in graphicsLayer
 * or drawBehind, and the indicator draws solid shapes only (no shader is made per frame).
 */

private val PanelColor = Color(0xFF17171E)
private val FieldColor = Color(0xFF272731)
private val Hint = Color(0xFF7F7F89)
private val Muted = Color(0xFF8B8B95)
private val ChipInk = Color(0xFFE6E6EC)
private val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** Fast enough that a flick means "close" wherever the sheet was let go, in px/s (the queue sheet's). */
private const val FLICK = 800f

/** The inspirations under the field: each composes at once. */
private val INSPIRATIONS = listOf("Session Focus", "Night Drive", "Workout 135+ BPM", "Chill acoustique")

@Composable
fun AiPlaylistSheet(
    playerViewModel: PlayerViewModel,
    onDismiss: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    viewModel: AiPlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val model by viewModel.modelState.collectAsStateWithLifecycle()
    val engine by viewModel.engineAvailable.collectAsStateWithLifecycle()
    val u = designUnit()
    val accent = rememberPlayerAccent()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val unitPx = with(density) { u.toPx() }

    val clock = remember { Animatable(0f) }
    val panelP = remember { Animatable(0f) }
    val scrimP = remember { Animatable(0f) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    var panelHeightPx by remember { mutableFloatStateOf(1f) }
    var closing by remember { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnOpenPlaylist by rememberUpdatedState(onOpenPlaylist)

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { clock.animateTo(900f, tween(900, easing = LinearEasing)) }
            launch { scrimP.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
            launch { panelP.animateTo(1f, tween(440, easing = EaseOut)) }
        }
    }
    fun close(then: () -> Unit = {}) {
        if (closing) return
        closing = true
        focus.clearFocus()
        viewModel.cancel()
        scope.launch {
            coroutineScope {
                launch { scrimP.animateTo(0f, tween(260, delayMillis = 40, easing = FastOutSlowInEasing)) }
                launch { panelP.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }
            }
            currentOnDismiss()
            then()
        }
    }
    fun t(delay: Float, dur: Float, ease: Easing = LinearEasing) = ease.transform(((clock.value - delay) / dur).coerceIn(0f, 1f))
    fun Modifier.rise(index: Int) = graphicsLayer {
        val p = t(180f + index * 45f, 320f, EaseOut)
        alpha = p
        translationY = 16f * unitPx * (1f - p)
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setWindowAnimations(0)
            window?.setDimAmount(0f)
            @Suppress("DEPRECATION")
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        Box(Modifier.fillMaxSize()) {
            // What lies behind darkens as the sheet comes up, and clears as it is dragged down; a tap on it closes.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrimP.value * (1f - (dragPx / panelHeightPx).coerceIn(0f, 1f)) }
                    .background(Color.Black.copy(alpha = 0.62f))
                    .pointerInput(Unit) { detectTapGestures { close() } },
            )
            val maxPanel = LocalConfiguration.current.screenHeightDp.dp * 0.9f
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxPanel)
                        .onSizeChanged { panelHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                        .graphicsLayer { translationY = size.height * (1f - panelP.value) + dragPx }
                        .clip(RoundedCornerShape(topStart = u * 52f, topEnd = u * 52f))
                        .background(PanelColor)
                        .drawBehind { drawRect(Color.White.copy(alpha = 0.12f), size = Size(size.width, 0.8.dp.toPx())) }
                        .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }
                        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                        .padding(start = u * 40f, end = u * 40f, bottom = u * 40f)
                        .semantics { paneTitle = "Playlist IA" },
                ) {
                    AiPlaylistPanelContent(
                        state = state,
                        model = model,
                        engine = engine,
                        modelName = viewModel.modelName,
                        modelSizeMb = viewModel.modelSizeMb,
                        accent = accent,
                        handleModifier = Modifier.draggable(
                            state = rememberDraggableState { d -> dragPx = (dragPx + d).coerceAtLeast(0f) },
                            orientation = Orientation.Vertical,
                            onDragStopped = { v ->
                                if (v > FLICK || dragPx > panelHeightPx * 0.3f) {
                                    close()
                                } else {
                                    scope.launch { animate(dragPx, 0f, animationSpec = spring(1f, Spring.StiffnessMediumLow)) { x, _ -> dragPx = x } }
                                }
                            },
                        ),
                        rise = { index -> Modifier.rise(index) },
                        actions = remember(viewModel, playerViewModel) {
                            AiPanelActions(
                                onPrompt = viewModel::onPromptChange,
                                onSubmit = { focus.clearFocus(); viewModel.compose() },
                                onInspiration = { label -> focus.clearFocus(); viewModel.compose(label) },
                                onDownload = viewModel::downloadModel,
                                onCancelDownload = viewModel::cancelDownload,
                                onPlayFrom = { index ->
                                    playerViewModel.playQueue(viewModel.state.value.tracks, index, context = LISTENING_CONTEXT)
                                    close()
                                },
                                onSave = {
                                    val saved = viewModel.state.value.savedPlaylistId
                                    if (saved != null) close { currentOnOpenPlaylist(saved) } else viewModel.save()
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

/** What the panel's controls do; one object, made once, so the panel's parameters stay stable. */
internal class AiPanelActions(
    val onPrompt: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onInspiration: (String) -> Unit,
    val onDownload: () -> Unit,
    val onCancelDownload: () -> Unit,
    val onPlayFrom: (Int) -> Unit,
    val onSave: () -> Unit,
)

/**
 * The sheet's contents without the sheet: no view model, no dialog, no animation of its own ([rise] gives each block its
 * entrance), so it can be shown in a preview or a screenshot test exactly as it is drawn.
 */
@Composable
internal fun ColumnScope.AiPlaylistPanelContent(
    state: AiPlaylistUiState,
    model: SlmModelState,
    engine: Boolean?,
    modelName: String,
    modelSizeMb: Int,
    accent: Color,
    actions: AiPanelActions,
    handleModifier: Modifier = Modifier,
    rise: (Int) -> Modifier = { Modifier },
) {
    val u = designUnit()
    // Handle and title: dragging them moves the sheet, and far enough (or fast enough) closes it.
    Column(handleModifier) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = u * 28f), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(AuraColors.TextTertiary))
        }
        Column(rise(0)) {
            Text(
                "Playlist IA", color = Color.White, fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold,
                fontSize = designSp(44f), lineHeight = designSp(52f),
            )
            Text(
                "Décris une ambiance : elle est composée avec ta bibliothèque, sur ton téléphone.", color = Muted,
                fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(30f), lineHeight = designSp(38f),
            )
        }
    }
    Spacer(Modifier.height(u * 28f))
    PromptField(
        value = state.prompt,
        onValue = actions.onPrompt,
        onSubmit = actions.onSubmit,
        accent = accent,
        modifier = rise(1),
    )
    Spacer(Modifier.height(u * 24f))
    Row(
        Modifier.fillMaxWidth().then(rise(2)).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(u * 18f),
    ) {
        INSPIRATIONS.forEach { label ->
            InspirationChip(label, selected = state.request == label && state.phase != AiPhase.Idle, accent = accent) { actions.onInspiration(label) }
        }
    }
    Spacer(Modifier.height(u * 32f))
    Column(rise(3)) {
        when (state.phase) {
            AiPhase.Idle -> EnginePanel(
                engine = engine, model = model, name = modelName, sizeMb = modelSizeMb, accent = accent,
                onDownload = actions.onDownload, onCancel = actions.onCancelDownload,
            )
            AiPhase.Thinking -> ThinkingIndicator(state.stage, accent)
            AiPhase.NoMatch -> Note("Rien dans ta bibliothèque ne répond à « ${state.request} ». Essaie une autre ambiance.", state.tags)
            AiPhase.LibraryEmpty -> Note("Ta bibliothèque est vide : ajoute de la musique, puis reviens ici.", emptyList())
            AiPhase.Ready -> ResultPanel(
                state = state,
                accent = accent,
                onPlayFrom = actions.onPlayFrom,
                onSave = actions.onSave,
            )
        }
    }
}

/** What the player records as the queue's origin (see PlayerViewModel.playQueue). */
private const val LISTENING_CONTEXT = "ai-playlist"

/** The search field, open: a pale pill, the assistant's glyph where the magnifier is, a cross to clear. */
@Composable
private fun PromptField(value: String, onValue: (String) -> Unit, onSubmit: () -> Unit, accent: Color, modifier: Modifier = Modifier) {
    val u = designUnit()
    Box(modifier.fillMaxWidth().height(u * 112f).clip(CircleShape).background(FieldColor)) {
        Box(Modifier.align(Alignment.CenterStart).padding(start = u * 24f).size(u * 80f), contentAlignment = Alignment.Center) {
            Icon(CupertinoIcons.AutoAwesome, null, tint = Hint, modifier = Modifier.size(u * 52f))
        }
        Box(Modifier.align(Alignment.CenterStart).padding(start = u * 124f, end = u * 100f)) {
            val style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(44f), lineHeight = designSp(52f), color = Color.White)
            if (value.isEmpty()) {
                Text("Une ambiance, un tempo…", style = style.copy(fontWeight = FontWeight.SemiBold, color = Hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.fillMaxWidth(),
                textStyle = style,
                singleLine = true,
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = u * 24f).size(u * 80f).clip(CircleShape).clickable { onValue("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(CupertinoIcons.Close, "Effacer", tint = Color.White, modifier = Modifier.size(u * 46f))
            }
        }
    }
}

/** The search's filter chip: lit in the accent when it is the request being shown. */
@Composable
private fun InspirationChip(text: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val u = designUnit()
    val bg by animateColorAsState(if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f), tween(200), label = "ai-chip-bg")
    val fg by animateColorAsState(if (selected) accent else ChipInk, tween(200), label = "ai-chip-fg")
    Box(
        Modifier.height(u * 76f).clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = u * 34f),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(32f), lineHeight = designSp(40f), maxLines = 1)
    }
}

/** What will read the request: the local model (to download, downloading, ready) or, without it, the rules. */
@Composable
private fun EnginePanel(
    engine: Boolean?,
    model: SlmModelState,
    name: String,
    sizeMb: Int,
    accent: Color,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val u = designUnit()
    val title: String
    val detail: String
    when {
        engine == false -> {
            title = "Lecture directe"
            detail = "Le moteur local n'est pas disponible sur cet appareil : ta demande est lue mot à mot."
        }
        model is SlmModelState.Installed -> {
            title = "Modèle local · $name"
            detail = "Prêt. Tout reste sur ton téléphone ; il est déchargé de la mémoire dès la réponse donnée."
        }
        model is SlmModelState.Downloading -> {
            title = "Modèle local · $name"
            detail = if (model.verifying) "Vérification du fichier…" else "Téléchargement… ${((model.fraction ?: 0f) * 100).toInt()} %"
        }
        model is SlmModelState.Failed -> {
            title = "Modèle local · $name"
            detail = "${model.reason}. En attendant, ta demande est lue mot à mot."
        }
        else -> {
            title = "Modèle local · $name"
            detail = "$sizeMb Mo, téléchargé une fois. Sans lui, ta demande est lue mot à mot."
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = u * 8f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 26f)) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = designSp(36f), lineHeight = designSp(44f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = Muted, fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(30f), lineHeight = designSp(38f))
            }
            if (engine == true) {
                when (model) {
                    SlmModelState.Missing, is SlmModelState.Failed -> InspirationChip(if (model is SlmModelState.Failed) "Réessayer" else "Télécharger", selected = true, accent = accent, onClick = onDownload)
                    is SlmModelState.Downloading -> if (!model.verifying) {
                        Box(Modifier.size(u * 80f).clip(CircleShape).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
                            Icon(CupertinoIcons.Close, "Annuler le téléchargement", tint = Color.White, modifier = Modifier.size(u * 46f))
                        }
                    }
                    SlmModelState.Installed -> Unit
                }
            }
        }
        if (model is SlmModelState.Downloading) {
            Spacer(Modifier.height(u * 20f))
            // The taste picker's gauge.
            val fraction = model.fraction ?: 0f
            Box(Modifier.fillMaxWidth().height(u * 10f).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(u * 10f).clip(CircleShape).background(accent))
            }
        }
    }
}

/**
 * The inference indicator: a bead of the accent sweeping a faint track, and the step in words. Drawn, not composed: the
 * transition's value is read in drawBehind only, so a frame of it is one draw of two rounded rectangles.
 */
@Composable
private fun ThinkingIndicator(stage: AiStage, accent: Color) {
    val u = designUnit()
    val transition = rememberInfiniteTransition(label = "ai-thinking")
    val sweep = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)), label = "ai-sweep")
    val track = Color.White.copy(alpha = 0.10f)
    Column(Modifier.fillMaxWidth().padding(vertical = u * 12f)) {
        AnimatedContent(
            targetState = stage,
            transitionSpec = { fadeIn(tween(180, delayMillis = 80)) togetherWith fadeOut(tween(90)) },
            label = "ai-stage",
        ) { s ->
            Text(
                when (s) {
                    AiStage.READING -> "Lecture de ta demande…"
                    AiStage.SELECTING -> "Choix et enchaînement des titres…"
                },
                color = Color.White, fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(34f), lineHeight = designSp(42f),
            )
        }
        Spacer(Modifier.height(u * 22f))
        Box(
            Modifier
                .fillMaxWidth()
                .height(u * 10f)
                .drawBehind {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(track, cornerRadius = r)
                    val bead = size.width * 0.28f
                    val x = (size.width + bead) * sweep.value - bead
                    val left = x.coerceAtLeast(0f)
                    val right = (x + bead).coerceAtMost(size.width)
                    if (right > left) drawRoundRect(accent, topLeft = Offset(left, 0f), size = Size(right - left, size.height), cornerRadius = r)
                },
        )
        Spacer(Modifier.height(u * 18f))
        Text(
            "Calculé sur ton téléphone, sans interrompre la musique.", color = Muted,
            fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(28f), lineHeight = designSp(36f),
        )
    }
}

@Composable
private fun Note(text: String, tags: List<String>) {
    val u = designUnit()
    Column(Modifier.fillMaxWidth().padding(vertical = u * 12f)) {
        if (tags.isNotEmpty()) {
            Tags(tags)
            Spacer(Modifier.height(u * 20f))
        }
        Text(text, color = Muted, fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = designSp(32f), lineHeight = designSp(42f))
    }
}

/** What was understood, as quiet pills: information, not buttons. */
@Composable
private fun Tags(tags: List<String>) {
    val u = designUnit()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(u * 14f)) {
        tags.forEach { tag ->
            Box(Modifier.height(u * 60f).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = u * 24f), contentAlignment = Alignment.Center) {
                Text(tag, color = Muted, fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(28f), lineHeight = designSp(34f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ColumnScope.ResultPanel(
    state: AiPlaylistUiState,
    accent: Color,
    onPlayFrom: (Int) -> Unit,
    onSave: () -> Unit,
) {
    val u = designUnit()
    Tags(state.tags)
    Spacer(Modifier.height(u * 24f))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CoverMosaic(state.tracks, u * 150f)
        Spacer(Modifier.width(u * 30f))
        Column(Modifier.weight(1f)) {
            Text(
                IntentSummary.count(state.tracks.size, state.totalDurationMs), color = Color.White, fontFamily = AuraFontFamily,
                fontWeight = FontWeight.ExtraBold, fontSize = designSp(40f), lineHeight = designSp(48f), maxLines = 1,
            )
            val origin = if (state.origin == IntentOrigin.MODEL) "Composée par le modèle local" else "Composée mot à mot"
            val relaxed = when (state.relaxation) {
                Relaxation.NONE -> null
                Relaxation.WIDER_TEMPO -> "tempo élargi"
                Relaxation.ANY_GENRE -> "genres élargis"
                Relaxation.ANY_TEMPO -> "tempo libre"
            }
            Text(
                if (relaxed != null) "$origin · $relaxed" else origin, color = Muted, fontFamily = AuraFontFamily,
                fontWeight = FontWeight.SemiBold, fontSize = designSp(30f), lineHeight = designSp(38f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Spacer(Modifier.height(u * 16f))
    BoxWithConstraints(Modifier.weight(1f, fill = false)) {
        LazyColumn(Modifier.heightIn(max = maxHeight), contentPadding = PaddingValues(vertical = u * 8f)) {
            itemsIndexed(state.tracks, key = { _, t -> t.id }) { index, track ->
                TrackListItem(track = track, onClick = { onPlayFrom(index) })
            }
        }
    }
    Spacer(Modifier.height(u * 24f))
    // One above the other, full width: side by side, "Enregistrer en playlist" does not fit a phone's half width.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(u * 20f)) {
        AuraButton(onClick = { onPlayFrom(0) }, tone = ButtonTone.Primary, minHeight = 48.dp, modifier = Modifier.fillMaxWidth()) {
            Icon(CupertinoIcons.Play, null, modifier = Modifier.size(u * 40f))
            Spacer(Modifier.width(u * 14f))
            Text("Lancer la lecture", fontWeight = FontWeight.Bold, fontFamily = AuraFontFamily, fontSize = designSp(34f), maxLines = 1)
        }
        AuraButton(
            onClick = onSave, tone = ButtonTone.Neutral, minHeight = 48.dp,
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = if (state.saving) 0.5f else 1f },
        ) {
            val saved = state.savedPlaylistId != null
            Icon(if (saved) CupertinoIcons.Check else CupertinoIcons.PlaylistAdd, null, modifier = Modifier.size(u * 40f), tint = if (saved) accent else Color.White)
            Spacer(Modifier.width(u * 14f))
            Text(if (saved) "Ouvrir la playlist" else "Enregistrer en playlist", fontWeight = FontWeight.Bold, fontFamily = AuraFontFamily, fontSize = designSp(34f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Four covers of the playlist's first albums, two by two, as a playlist's cover is made. */
@Composable
private fun CoverMosaic(tracks: List<Track>, side: Dp) {
    val u = designUnit()
    val albums = remember(tracks) { tracks.distinctBy { it.albumId }.take(4) }
    val cell = side / 2
    val shape = RoundedCornerShape(u * 24f)
    Box(Modifier.size(side).clip(shape).background(Color.White.copy(alpha = 0.06f))) {
        if (albums.size < 4) {
            albums.firstOrNull()?.let { t ->
                AlbumArt(uri = t.artworkUri, contentDescription = t.album, modifier = Modifier.size(side), shape = shape, targetSizeDp = side, cacheKey = t.albumId.toString())
            }
        } else {
            Column {
                for (row in 0..1) Row {
                    for (col in 0..1) {
                        val t = albums[row * 2 + col]
                        AlbumArt(uri = t.artworkUri, contentDescription = t.album, modifier = Modifier.size(cell), shape = RoundedCornerShape(0.dp), targetSizeDp = cell, cacheKey = t.albumId.toString())
                    }
                }
            }
        }
    }
}
