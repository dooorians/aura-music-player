package com.ikko.aura.ui.theme

import androidx.compose.ui.graphics.Color

object AuraColors {
    val Black = Color(0xFF000000)
    val SurfaceDark = Color(0xFF0A0A0C)
    val SurfaceVariantDark = Color(0xFF1C1C22)
    val OutlineDark = Color(0x33FFFFFF)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xB3FFFFFF)
    val TextTertiary = Color(0x73FFFFFF)

    val AccentPink = Color(0xFFB8356F)
    val AccentPurple = Color(0xFF5B2E8C)
    val AccentOrange = Color(0xFFB35A1A)

    val GlassFill = Color(0x1FFFFFFF)
    val GlassBorder = Color(0x3DFFFFFF)
    val GlassScrim = Color(0x66000000)
    val PillScrim = Color(0xEC000000)

    val MeshNowPlaying = listOf(
        Color(0xFF2A1740),
        Color(0xFF7A2050),
        Color(0xFF1A0E28),
        Color(0xFF44205E),
    )
    val DefaultMesh = MeshNowPlaying

    /** The tint at the top of the screen before any cover has been read. */
    val DefaultPrimary = Color(0xFF2A2F5E)
}
