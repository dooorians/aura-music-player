package com.ikko.aura.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SquircleCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = AuraShapes.AlbumCover,
    background: Brush = Brush.linearGradient(listOf(AuraColors.SurfaceVariantDark, AuraColors.SurfaceDark)),
    border: BorderStroke? = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .groundFill(background, shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .let { base ->
                when {
                    onClick == null -> base
                    onLongClick == null -> base.clickable(onClick = onClick)
                    else -> base.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                }
            },
        content = content,
    )
}
