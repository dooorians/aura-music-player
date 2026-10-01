package com.ikko.aura.ui.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.ikko.aura.ui.theme.LocalCoverColors

/**
 * The app's switch. The stock colours here were a white track with a black thumb, which read as a piece of
 * another app among the tinted glass: on, it takes the cover's accent like the rest of the interface (the
 * play button, the page titles), with a white thumb; off, it is the same pale glass as the capsule's rim.
 */
@Composable
fun AuraSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val accent = LocalCoverColors.current.accent
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = accent.copy(alpha = 0.88f),
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White.copy(alpha = 0.88f),
            uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
            uncheckedBorderColor = Color.White.copy(alpha = 0.24f),
        ),
    )
}
