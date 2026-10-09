package com.ikko.aura.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikko.aura.data.home.WidgetKind
import com.ikko.aura.data.home.WidgetSize
import com.ikko.aura.ui.components.AuraButton
import com.ikko.aura.ui.components.AuraIconButton
import com.ikko.aura.ui.components.AuraMenu
import com.ikko.aura.ui.components.ButtonTone
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.PickerOption
import com.ikko.aura.viewmodel.WidgetItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeWidgetEditSheet(
    item: WidgetItem,
    pickerOptions: List<PickerOption>,
    onDismiss: () -> Unit,
    onResize: (WidgetSize) -> Unit,
    onShift: (Int) -> Unit,
    onReplaceContent: (WidgetKind, String?) -> Unit,
    onHide: () -> Unit,
    onRemove: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showContentPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AuraMenu.tint().copy(alpha = 0.98f),
        contentColor = AuraColors.TextPrimary,
        shape = AuraMenu.SheetShape,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.35f)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Personnaliser le widget",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AuraColors.TextPrimary,
                    )
                    Text(
                        text = item.content.title.ifBlank { item.widget.kind.title },
                        fontSize = 13.sp,
                        color = AuraColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AuraIconButton(onClick = onDismiss, size = 32.dp) {
                    Icon(CupertinoIcons.Close, contentDescription = "Fermer", modifier = Modifier.size(14.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            // Section: Format / Taille
            SheetSectionTitle(title = "FORMAT DU WIDGET")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WidgetSize.entries.forEach { size ->
                    SizeOptionTile(
                        size = size,
                        selected = size == item.widget.size,
                        onClick = { onResize(size) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Section: Position
            SheetSectionTitle(title = "POSITION")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AuraButton(
                    onClick = { onShift(-1) },
                    tone = ButtonTone.Neutral,
                    modifier = Modifier.weight(1f),
                    minHeight = 40.dp,
                ) {
                    Icon(
                        CupertinoIcons.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp).rotate(180f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Monter", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                AuraButton(
                    onClick = { onShift(1) },
                    tone = ButtonTone.Neutral,
                    modifier = Modifier.weight(1f),
                    minHeight = 40.dp,
                ) {
                    Icon(
                        CupertinoIcons.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Descendre", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(20.dp))

            // Section: Contenu
            SheetSectionTitle(title = "CONTENU")
            Spacer(Modifier.height(8.dp))
            AuraButton(
                onClick = { showContentPicker = !showContentPicker },
                tone = ButtonTone.Neutral,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 44.dp,
            ) {
                Icon(CupertinoIcons.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (showContentPicker) "Fermer la sélection" else "Changer le contenu…",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (showContentPicker) CupertinoIcons.ChevronDown.let { it } else CupertinoIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }

            if (showContentPicker) {
                Spacer(Modifier.height(10.dp))
                ContentPickerList(
                    pickerOptions = pickerOptions,
                    onSelect = { option ->
                        onReplaceContent(option.kind, option.arg)
                        showContentPicker = false
                    },
                )
            }

            Spacer(Modifier.height(24.dp))

            // Section: Actions
            SheetSectionTitle(title = "ACTIONS")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AuraButton(
                    onClick = {
                        onHide()
                        onDismiss()
                    },
                    tone = ButtonTone.Neutral,
                    modifier = Modifier.weight(1f),
                    minHeight = 42.dp,
                ) {
                    Icon(CupertinoIcons.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Masquer", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                AuraButton(
                    onClick = {
                        onRemove()
                        onDismiss()
                    },
                    tone = ButtonTone.Neutral,
                    modifier = Modifier.weight(1f),
                    minHeight = 42.dp,
                ) {
                    Icon(
                        CupertinoIcons.Delete,
                        contentDescription = null,
                        tint = Color(0xFFFF453A),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Supprimer", color = Color(0xFFFF453A), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeGlobalSettingsSheet(
    items: List<WidgetItem>,
    pickerOptions: List<PickerOption>,
    onDismiss: () -> Unit,
    onAdd: (WidgetKind, String?) -> Unit,
    onAddGif: (android.net.Uri) -> Unit,
    onShow: (String) -> Unit,
    onReset: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hiddenWidgets = remember(items) { items.filter { it.widget.hidden } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AuraMenu.tint().copy(alpha = 0.98f),
        contentColor = AuraColors.TextPrimary,
        shape = AuraMenu.SheetShape,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.35f)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Personnaliser l'accueil",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = AuraColors.TextPrimary,
                )
                AuraIconButton(onClick = onDismiss, size = 32.dp) {
                    Icon(CupertinoIcons.Close, contentDescription = "Fermer", modifier = Modifier.size(14.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            // Hidden widgets section
            if (hiddenWidgets.isNotEmpty()) {
                SheetSectionTitle(title = "WIDGETS MASQUÉS")
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    hiddenWidgets.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(AuraShapes.SquircleSmall)
                                .background(Color.White.copy(alpha = 0.07f))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.content.title.ifBlank { item.widget.kind.title },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AuraColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Format : ${item.widget.size.label}",
                                    fontSize = 11.sp,
                                    color = AuraColors.TextTertiary,
                                )
                            }
                            AuraButton(
                                onClick = { onShow(item.widget.id) },
                                tone = ButtonTone.Primary,
                                minHeight = 32.dp,
                            ) {
                                Text("Afficher", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            // Add new widget section
            SheetSectionTitle(title = "AJOUTER UN WIDGET")
            Spacer(Modifier.height(8.dp))
            val gifPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
            ) { uri ->
                if (uri != null) {
                    onAddGif(uri)
                    onDismiss()
                }
            }
            AuraButton(
                onClick = {
                    gifPicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.SingleMimeType("image/gif"),
                        ),
                    )
                },
                tone = ButtonTone.Neutral,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 44.dp,
            ) {
                Icon(CupertinoIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ajouter un GIF…", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(12.dp))
            ContentPickerList(
                pickerOptions = pickerOptions,
                onSelect = { option ->
                    onAdd(option.kind, option.arg)
                    onDismiss()
                },
            )

            Spacer(Modifier.height(24.dp))

            // Reset layout section
            SheetSectionTitle(title = "RÉINITIALISATION")
            Spacer(Modifier.height(8.dp))
            AuraButton(
                onClick = {
                    onReset()
                    onDismiss()
                },
                tone = ButtonTone.Neutral,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 42.dp,
            ) {
                Icon(CupertinoIcons.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text("Rétablir la disposition d'origine", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SheetSectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = AuraColors.TextTertiary,
    )
}

@Composable
private fun SizeOptionTile(
    size: WidgetSize,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val background = if (selected) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.06f)
    val border = if (selected) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f)

    Column(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Icon graphic representation of proportions
        Box(
            modifier = Modifier
                .height(22.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val (w, h) = when (size) {
                WidgetSize.COMPACT -> 18.dp to 9.dp
                WidgetSize.MEDIUM -> 18.dp to 18.dp
                WidgetSize.WIDE -> 30.dp to 9.dp
                WidgetSize.HERO -> 30.dp to 22.dp
            }
            Box(
                Modifier
                    .size(width = w, height = h)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (selected) Color.White else Color.White.copy(alpha = 0.45f)),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = size.label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color.White else AuraColors.TextSecondary,
            maxLines = 1,
        )
        Text(
            text = "${size.cols}x${size.rows}",
            fontSize = 10.sp,
            color = if (selected) Color.White.copy(alpha = 0.8f) else AuraColors.TextTertiary,
        )
    }
}

@Composable
private fun ContentPickerList(
    pickerOptions: List<PickerOption>,
    onSelect: (PickerOption) -> Unit,
) {
    val grouped = remember(pickerOptions) { pickerOptions.groupBy { it.section } }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        grouped.forEach { (section, options) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = section,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraColors.AccentPink,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AuraShapes.SquircleSmall)
                            .background(Color.White.copy(alpha = 0.05f))
                            .clickable { onSelect(option) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = option.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AuraColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!option.subtitle.isNullOrBlank()) {
                                Text(
                                    text = option.subtitle,
                                    fontSize = 11.sp,
                                    color = AuraColors.TextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Icon(
                            CupertinoIcons.ChevronRight,
                            contentDescription = null,
                            tint = AuraColors.TextTertiary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}
