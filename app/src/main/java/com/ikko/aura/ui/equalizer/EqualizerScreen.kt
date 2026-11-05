package com.ikko.aura.ui.equalizer

import com.ikko.aura.ui.screens.settings.settingsPageBody
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.core.audio.dsp.IsoBands
import com.ikko.aura.ui.components.AuraButton
import com.ikko.aura.ui.components.GlassMenu
import com.ikko.aura.ui.components.GlassMenuItem
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.glassMenuAnchor
import com.ikko.aura.ui.components.rememberGlassMenuState
import com.ikko.aura.ui.screens.settings.ChevronTrailing
import com.ikko.aura.ui.screens.settings.SettingsDivider
import com.ikko.aura.ui.screens.settings.SettingsGroup
import com.ikko.aura.ui.screens.settings.SettingsHeader
import com.ikko.aura.ui.screens.settings.SettingsRow
import com.ikko.aura.ui.screens.settings.SettingsSwitchRow
import com.ikko.aura.ui.screens.settings.settingsAccent
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import java.util.Locale

/**
 * The equaliser: the app's own 32-bit DSP (see [com.ikko.aura.core.audio.dsp.DspEngine]), not the system's.
 *
 * Laid out as a settings page — the same glass groups, switches and accent — in three parts: the switches that
 * decide what runs (the equaliser itself, the preset, loudness normalisation), the curve and its ten bands, and the
 * finer settings (preamp, target loudness, the profile tied to a Bluetooth device, saved profiles).
 */
@Composable
fun EqualizerScreen(
    navController: NavHostController,
    viewModel: EqualizerViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val accent = settingsAccent()
    val u = designUnit()
    val presetMenu = rememberGlassMenuState()
    var dialog by rememberSaveable { mutableStateOf<EqDialog?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().settingsPageBody().verticalScroll(rememberScrollState())) {
            SettingsHeader("Égaliseur", onBack = { navController.popBackStack() })

            SettingsGroup {
                SettingsSwitchRow(
                    title = "Égaliseur",
                    summary = if (ui.enabled) "Traitement 32 bits flottant actif" else "Contourné : le son passe tel quel",
                    detail = "Égaliseur, normalisation et limiteur tournent dans l'application, après le décodage : ils " +
                        "s'appliquent à toutes les sorties, Bluetooth compris. Éteint, le signal n'est pas touché — le " +
                        "passage de l'un à l'autre se fait en fondu, sans coupure.",
                    checked = ui.enabled,
                    onCheckedChange = viewModel::setEnabled,
                )
                SettingsDivider()
                SettingsRow(
                    title = "Préréglage",
                    summary = ui.profileName ?: "Personnalisé",
                    onClick = presetMenu::open,
                    trailing = {
                        Box(Modifier.glassMenuAnchor(presetMenu).graphicsLayer { alpha = presetMenu.buttonAlpha }) {
                            ChevronTrailing()
                        }
                    },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Normalisation du volume (R128)",
                    summary = "Chaque titre ramené à ${formatLufs(ui.targetLufs)}",
                    detail = "Lit le gain ReplayGain ou R128 du fichier quand il en a un ; sinon mesure la loudness " +
                        "intégrée EBU R128 pendant l'écoute, et la suit lentement : pas d'effet de pompage.",
                    checked = ui.normalizationEnabled,
                    onCheckedChange = viewModel::setNormalization,
                )
            }

            SettingsGroup(title = "Réponse en fréquence") {
                Column(Modifier.padding(horizontal = u * 28f, vertical = u * 24f)) {
                    ui.parametric?.let { filters ->
                        Text(
                            "AutoEq · ${filters.size} filtres paramétriques",
                            color = accent,
                            style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(28f)),
                            modifier = Modifier.padding(bottom = u * 12f),
                        )
                    }
                    FrequencyResponseGraph(
                        bands = viewModel.bands,
                        parametric = ui.parametric,
                        active = ui.enabled,
                        accent = accent,
                        modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(u * 20f)),
                    )
                }
            }

            SettingsGroup(
                title = "Bandes",
                footer = "Glisser pour régler, toucher deux fois pour remettre à 0 dB." +
                    if (ui.parametric != null) " Toucher une bande remplace le profil AutoEq par les 10 bandes." else "",
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = u * 12f, vertical = u * 30f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (band in 0 until IsoBands.COUNT) {
                        BandSlider(
                            label = IsoBands.LABELS[band],
                            gain = viewModel.bands.gains[band],
                            accent = accent,
                            active = ui.enabled && ui.parametric == null,
                            onDragStart = { viewModel.onBandDragStart(band) },
                            onChange = { viewModel.onBandChange(band, it) },
                            onDragEnd = { viewModel.onBandDragEnd(band) },
                            onReset = { viewModel.resetBand(band) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = u * 28f, end = u * 28f, bottom = u * 28f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AuraButton(onClick = viewModel::resetFlat, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        Icon(CupertinoIcons.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        ButtonLabel("À plat")
                    }
                    AuraButton(onClick = { dialog = EqDialog.Save }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        ButtonLabel("Enregistrer")
                    }
                    AuraButton(onClick = { dialog = EqDialog.AutoEq }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        ButtonLabel("AutoEq")
                    }
                }
            }

            SettingsGroup(title = "Préampli et limiteur", footer = "Un limiteur doux (tanh) garde toujours le signal sous 0 dBFS.") {
                SettingsSwitchRow(
                    title = "Préampli automatique",
                    summary = if (ui.autoPreamp) "Compense la courbe : ${formatDb(ui.headroomDb)}" else "Aucune compensation",
                    checked = ui.autoPreamp,
                    onCheckedChange = viewModel::setAutoPreamp,
                )
                SettingsDivider()
                LabelledSlider(
                    title = "Préampli manuel",
                    value = ui.preampDb,
                    valueLabel = formatDb(ui.preampDb),
                    range = -12f..12f,
                    steps = 47,
                    onChange = viewModel::setPreamp,
                )
                if (ui.normalizationEnabled) {
                    SettingsDivider()
                    LabelledSlider(
                        title = "Loudness cible",
                        value = ui.targetLufs,
                        valueLabel = formatLufs(ui.targetLufs),
                        range = -23f..-8f,
                        steps = 14,
                        onChange = viewModel::setTargetLufs,
                    )
                }
            }

            ui.bluetoothDevice?.let { device ->
                val saved = ui.profileId != null
                SettingsGroup(title = "Appareil Bluetooth") {
                    SettingsSwitchRow(
                        title = "Lier à « $device »",
                        summary = when {
                            ui.deviceProfileId != null -> "${profileName(ui, ui.deviceProfileId)} s'appliquera à chaque connexion"
                            saved -> "Appliquer « ${ui.profileName} » à chaque connexion"
                            else -> "Enregistrez d'abord ce réglage comme profil"
                        },
                        checked = ui.deviceProfileId != null,
                        onCheckedChange = { if (saved || !it) viewModel.setDeviceAssociation(it) },
                    )
                }
            }

            if (ui.customProfiles.isNotEmpty()) {
                SettingsGroup(title = "Profils enregistrés") {
                    ui.customProfiles.forEachIndexed { i, profile ->
                        if (i > 0) SettingsDivider()
                        SettingsRow(
                            title = profile.name,
                            summary = if (profile.isParametric) "AutoEq · ${profile.parametric!!.size} filtres" else "10 bandes",
                            onClick = { viewModel.applyProfile(profile) },
                            trailing = {
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).clickable { viewModel.deleteProfile(profile.id) },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(CupertinoIcons.Delete, "Supprimer ${profile.name}", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp)) }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(LocalPlayerPillHeight.current + u * 60f))
        }

        GlassMenu(presetMenu, width = 250.dp) {
            (ui.builtInProfiles + ui.customProfiles).forEach { profile ->
                GlassMenuItem(
                    label = profile.name,
                    selected = profile.id == ui.profileId,
                    onClick = {
                        viewModel.applyProfile(profile)
                        presetMenu.close()
                    },
                )
            }
        }

        when (dialog) {
            EqDialog.Save -> EqTextDialog(
                title = "Enregistrer le profil",
                namePlaceholder = "Nom du profil",
                initialName = ui.profileName.takeIf { ui.profileId?.startsWith("custom:") == true }.orEmpty(),
                onDismiss = { dialog = null },
                onConfirm = { name, _ ->
                    viewModel.saveAs(name)
                    true
                },
            )
            EqDialog.AutoEq -> EqTextDialog(
                title = "Importer AutoEq",
                namePlaceholder = "Nom (ex. le modèle du casque)",
                bodyPlaceholder = "Coller ici le contenu de ParametricEQ.txt\n\nPreamp: -6.2 dB\nFilter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70\n…",
                onDismiss = { dialog = null },
                onConfirm = { name, body -> viewModel.importAutoEq(name, body) },
            )
            null -> Unit
        }
    }
}

private enum class EqDialog { Save, AutoEq }

private fun profileName(ui: EqualizerUiState, id: String?): String =
    (ui.builtInProfiles + ui.customProfiles).firstOrNull { it.id == id }?.name ?: "Ce profil"

private fun formatDb(db: Float): String =
    if (kotlin.math.abs(db) < 0.05f) "0 dB" else String.format(Locale.FRANCE, "%+.1f dB", db).replace('-', '−')

private fun formatLufs(lufs: Float): String = String.format(Locale.FRANCE, "%.0f LUFS", lufs).replace('-', '−')

@Composable
private fun ButtonLabel(text: String) {
    Text(text, style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp), maxLines = 1)
}

/** A settings row holding a horizontal slider, its value on the right of the title. */
@Composable
private fun LabelledSlider(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    val u = designUnit()
    val accent = settingsAccent()
    Column(Modifier.fillMaxWidth().padding(horizontal = u * 28f, vertical = u * 22f)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = Color.White,
                style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(42f), lineHeight = designSp(50f)),
                modifier = Modifier.weight(1f),
            )
            Text(
                valueLabel,
                color = Color.White.copy(alpha = 0.6f),
                style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(32f)),
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.16f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

private val DialogCardBg = Color(0xFF19191C)
private val DialogFieldBg = Color(0xFF26262C)
private val DialogButtonBg = Color(0xFF2C2C31)

/**
 * The app's dialog (as for a new playlist): a dark card, a close and a confirm button either side of the title, and
 * a name field — with a large text field under it when [bodyPlaceholder] is given. [onConfirm] returns false when
 * what was typed cannot be used, and the dialog stays open saying so.
 */
@Composable
private fun EqTextDialog(
    title: String,
    namePlaceholder: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, body: String) -> Boolean,
    initialName: String = "",
    bodyPlaceholder: String? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var body by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val canConfirm = if (bodyPlaceholder != null) body.isNotBlank() else name.isNotBlank()
    val fieldStyle = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .imePadding()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(350.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(DialogCardBg)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { }
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    RoundIcon(onClick = onDismiss) { Icon(CupertinoIcons.Close, "Fermer", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    Text(title, color = Color.White, style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp))
                    RoundIcon(onClick = {
                        if (!canConfirm) return@RoundIcon
                        if (onConfirm(name, body)) onDismiss() else error = true
                    }) {
                        Icon(CupertinoIcons.Check, "Valider", tint = if (canConfirm) settingsAccent() else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
                    }
                }
                DialogField(name, { name = it }, namePlaceholder, fieldStyle, singleLine = true)
                if (bodyPlaceholder != null) {
                    DialogField(body, { body = it; error = false }, bodyPlaceholder, fieldStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), singleLine = false)
                }
                if (error) {
                    Text(
                        "Aucun filtre AutoEq reconnu dans ce texte.",
                        color = Color(0xFFFF7A70),
                        style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogField(value: String, onChange: (String) -> Unit, placeholder: String, style: TextStyle, singleLine: Boolean) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(settingsAccent()),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (singleLine) Modifier else Modifier.heightIn(min = 160.dp, max = 280.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(DialogFieldBg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = Color.White.copy(alpha = 0.35f)))
                inner()
            }
        },
    )
}

@Composable
private fun RoundIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(DialogButtonBg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
