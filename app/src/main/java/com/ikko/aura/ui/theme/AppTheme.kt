package com.ikko.aura.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The app's one look for glass and backgrounds: flat tinted glass with an edge (no blur, lens, saturation, highlight, shadow
 * or backdrop recording) and an aurora and cover wash held still. It was the "battery saver" setting; it is now the
 * permanent design, so this is always true and nothing provides another value.
 */
val LocalAuraBatterySaver = staticCompositionLocalOf { true }

/**
 * The theme the background is lit with when a page has colours of its own (the album page, which is
 * lit with its own record rather than the one playing). Null means the playing track's.
 */
val LocalPageTheme = staticCompositionLocalOf<androidx.compose.runtime.MutableState<com.ikko.aura.ui.components.AlbumTheme?>> {
    mutableStateOf(null)
}

/** Opens the search over whichever page is showing. Provided by the root layout. */
val LocalSearchOpener = staticCompositionLocalOf<() -> Unit> { {} }


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
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AuraDarkColorScheme,
        typography = AuraTypography,
        shapes = AuraMaterialShapes,
        content = content,
    )
}
