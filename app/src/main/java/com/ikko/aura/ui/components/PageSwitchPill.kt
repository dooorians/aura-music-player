package com.ikko.aura.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
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
    val pressed = remember { Animatable(1f) }
    val shape = RoundedCornerShape(16.dp)
    LiquidGlassPanel(
        shape = shape,
        tint = Color(0xFF080C1C),
        tintAlpha = 0.4f,
        blurRadiusDp = 16f,
        dropShadow = false,
        edge = false,
        lensScale = 0f,
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(56.dp)
            .height(64.dp)
            // The same life as the mini player beside it: stretched by the pages' speed, pressed in under the finger.
            .graphicsLayer {
                val w = if (motion.reduceMotion) 0f
                else min(abs(motion.velocity) / PageMotionSpec.PLAYER_STRETCH_FULL_AT_PAGES_S, 1f)
                scaleX = pressed.value * (1f + PageMotionSpec.PLAYER_STRETCH_X * w)
                scaleY = pressed.value * (1f - PageMotionSpec.PLAYER_STRETCH_Y * w)
            }
            .clip(shape)
            // As on the mini player: cleared first, so the glass is drawn over the bare window and not over the page.
            .drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) }
            .sheen(
                position = { val p = motion.p; 1.3f - 1.7f * (p - kotlin.math.floor(p)).coerceIn(-0.2f, 1.2f) },
                strength = { if (motion.reduceMotion || !motion.inTransit) 0f else 1f },
            )
            .pointerInput(onHome) {
                detectTapGestures(
                    onPress = {
                        scope.launch { pressed.animateTo(0.94f, spring(dampingRatio = 0.82f, stiffness = 160f)) }
                        tryAwaitRelease()
                        scope.launch { pressed.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 135f)) }
                    },
                    onTap = { motion.goTo(scope, if (onHome) 1 else 0) },
                )
            },
    ) {
        // The lit rim of the mini player's glass.
        Spacer(Modifier.matchParentSize().clip(shape).pillEdge(shape))
        Icon(
            imageVector = if (onHome) CupertinoIcons.Library else CupertinoIcons.House,
            contentDescription = if (onHome) "Bibliothèque" else "Accueil",
            tint = Color.White,
            modifier = Modifier.size(24.dp).align(Alignment.Center),
        )
    }
}
