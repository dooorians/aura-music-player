package com.ikko.aura.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SquircleCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = AuraShapes.SquircleLarge,
    background: Brush = Brush.linearGradient(listOf(AuraColors.SurfaceVariantDark, AuraColors.SurfaceDark)),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .groundFill(background, shape)
            .let { base ->
                when {
                    onClick == null -> base
                    // combinedClickable only where a long press is actually handled: it costs a
                    // gesture detector that has to wait out the long-press timeout before it can
                    // report a tap, and a card with nothing to show has no reason to pay that.
                    onLongClick == null -> base.clickable(onClick = onClick)
                    else -> base.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                }
            },
        content = content,
    )
}
