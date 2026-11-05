package com.ikko.aura.ui.screens.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ikko.aura.ui.components.AuraIconButton
import com.ikko.aura.ui.components.AuraMenu
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.CardSlot

/**
 * Where the listener arranges the "Pour toi" cards: each one's place (up, down) and whether it shows. Hidden cards stay
 * in the list, greyed, so they can be brought back; their weekly playlists stay as they are.
 */
@Composable
fun HomeOrganizer(
    slots: List<CardSlot>,
    onMove: (id: String, delta: Int) -> Unit,
    onHidden: (id: String, hidden: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(AuraMenu.tint())
                .padding(vertical = 14.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Organiser",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AuraColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                AuraIconButton(onClick = onDismiss, size = 40.dp) {
                    Icon(CupertinoIcons.Check, contentDescription = "Terminé", modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().height((slots.size * 64).coerceAtMost(420).dp)) {
                items(slots, key = { it.id }) { slot ->
                    val index = slots.indexOf(slot)
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            slot.heading,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (slot.hidden) AuraColors.TextTertiary else AuraColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Mini(CupertinoIcons.ChevronDown, "Monter", enabled = index > 0, rotate = true) { onMove(slot.id, -1) }
                        Mini(CupertinoIcons.ChevronDown, "Descendre", enabled = index < slots.lastIndex) { onMove(slot.id, 1) }
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = if (slot.hidden) 0.06f else 0.16f))
                                .clickable { onHidden(slot.id, !slot.hidden) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                if (slot.hidden) "Afficher" else "Masquer",
                                style = MaterialTheme.typography.labelLarge,
                                color = AuraColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Mini(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, rotate: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = AuraColors.TextPrimary.copy(alpha = if (enabled) 1f else 0.25f),
            modifier = Modifier.size(20.dp).then(if (rotate) Modifier.graphicsRotate() else Modifier),
        )
    }
}

private fun Modifier.graphicsRotate(): Modifier = this.then(Modifier.rotate(180f))
