package com.ikko.aura.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ikko.aura.ui.theme.CupertinoIcons

/**
 * A small glass pill beside the mini player that flips between Home and Bibliothèque: it shows where a tap goes
 * (the house on the library, the list on Home). Same glass and height as the mini player so the two read as a pair.
 */
@Composable
fun PageSwitchPill(modifier: Modifier = Modifier) {
    val motion = LocalPageMotion.current ?: return
    val scope = rememberCoroutineScope()
    val onHome = motion.p < 0.5f
    LiquidGlassPanel(
        shape = RoundedCornerShape(16.dp),
        tint = Color(0xFF080C1C),
        tintAlpha = 0.4f,
        blurRadiusDp = 16f,
        dropShadow = false,
        edge = false,
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(56.dp)
            .height(64.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                motion.goTo(scope, if (onHome) 1 else 0)
            },
    ) {
        Icon(
            imageVector = if (onHome) CupertinoIcons.Library else CupertinoIcons.House,
            contentDescription = if (onHome) "Bibliothèque" else "Accueil",
            tint = Color.White,
            modifier = Modifier.size(24.dp).align(Alignment.Center),
        )
    }
}
