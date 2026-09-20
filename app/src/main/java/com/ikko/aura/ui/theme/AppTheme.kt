package com.ikko.aura.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class AuraColorMode { MESH_DYNAMIC, MONOCHROME }

val LocalAuraColorMode = compositionLocalOf { AuraColorMode.MESH_DYNAMIC }
val LocalAuraBatterySaver = staticCompositionLocalOf { false }

/**
 * The dominant colors of whatever track is currently playing (or the default palette when
 * nothing is). Every screen's background derives from this — the app-wide visual identity
 * follows the now-playing artwork everywhere, not just on the Now Playing screen.
 */
val LocalNowPlayingMesh = compositionLocalOf { AuraColors.DefaultMesh }

/**
 * Height the floating player pill currently occupies at the bottom of the screen, or zero when
 * nothing is playing. The pill is an overlay drawn on top of whichever screen is showing, so
 * without reserving this much space every scrollable screen ends with its last row hidden
 * underneath it. Provided by MainLayout from the pill's measured height, and consumed by
 * [com.ikko.aura.ui.components.GradientScreenScaffold], which every screen wraps its content in.
 */
val LocalPlayerPillHeight = compositionLocalOf { 0.dp }

private val AuraDarkColorScheme = darkColorScheme(
    primary = AuraColors.AccentPink,
    secondary = AuraColors.AccentPurple,
    tertiary = AuraColors.AccentOrange,
    background = AuraColors.Black,
    surface = AuraColors.Black,
    surfaceVariant = AuraColors.SurfaceVariantDark,
    onBackground = AuraColors.TextPrimary,
    onSurface = AuraColors.TextPrimary,
    outline = AuraColors.OutlineDark,
)

@Composable
fun AuraTheme(
    colorMode: AuraColorMode = AuraColorMode.MESH_DYNAMIC,
    batterySaver: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalAuraColorMode provides colorMode,
        LocalAuraBatterySaver provides batterySaver,
    ) {
        MaterialTheme(
            colorScheme = AuraDarkColorScheme,
            typography = AuraTypography,
            shapes = AuraMaterialShapes,
            content = content,
        )
    }
}
