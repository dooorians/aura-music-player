package com.ikko.aura.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes

@Composable
fun HiResBadge(
    isHiRes: Boolean,
    hasExternalDac: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isHiRes && !hasExternalDac) return

    val label = when {
        isHiRes && hasExternalDac -> "Hi-Res · DAC"
        isHiRes -> "Hi-Res"
        else -> "DAC"
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = AuraColors.TextPrimary,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(AuraShapes.Pill)
            .background(AuraColors.GlassFill)
            .border(1.dp, AuraColors.GlassBorder, AuraShapes.Pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
