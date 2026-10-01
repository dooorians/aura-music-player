package com.ikko.aura.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.LocalCoverColors

/**
 * The one surface every menu, dialog and sheet sits on. The mini player and the tab capsule are a dark
 * glass tinted a little by the cover; the menus used to be flat greys of their own, each slightly
 * different (a dropdown, two dialogs, a sheet), with no edge to separate them from the page. These use the
 * same tint, and the same lit rim that runs round the capsule: bright where light would catch the top,
 * almost gone at the bottom.
 *
 * Not live glass: a menu is on screen for a couple of seconds, and a blur recomputed behind it every
 * frame is a cost the players' glass already pays for the two objects that are always there.
 */
object AuraMenu {
    private val BaseTint = Color(0xFF080C1C)
    private const val COVER_SHARE = 0.18f
    private const val OPACITY = 0.97f

    val Shape: Shape = AuraShapes.SquircleMedium
    val SheetShape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    @Composable
    fun tint(): Color {
        val accent = LocalCoverColors.current.accent
        return remember(accent) { Color(ColorUtils.blendARGB(BaseTint.toArgb(), accent.toArgb(), COVER_SHARE)) }
    }

    /** The body of the surface: the tint, a touch darker towards the bottom. */
    @Composable
    fun fill(): Brush {
        val t = tint()
        return Brush.verticalGradient(listOf(t.copy(alpha = OPACITY), lerp(t, Color.Black, 0.25f).copy(alpha = OPACITY)))
    }

    val rim: Brush
        get() = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.07f)))

    val rimStroke: BorderStroke get() = BorderStroke(1.dp, rim)
}

/** [AuraMenu]'s surface as a modifier, for the dialogs that draw their own column. */
@Composable
fun Modifier.auraMenuSurface(shape: Shape = AuraMenu.Shape): Modifier =
    this.clip(shape).background(AuraMenu.fill()).border(1.dp, AuraMenu.rim, shape)
