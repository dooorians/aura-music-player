package com.ikko.aura.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object AuraShapes {
    val SquircleSmall = RoundedCornerShape(16.dp)
    val SquircleMedium = RoundedCornerShape(24.dp)
    val SquircleLarge = RoundedCornerShape(28.dp)
    val SquircleXLarge = RoundedCornerShape(32.dp)
    val Pill = RoundedCornerShape(50)

    /**
     * The floating player pill's silhouette — softer than a stadium, which at that height reads
     * as a lozenge, and squared off enough to give a label usable width. Shared with the search
     * bar, which is the same object arriving from the other edge of the screen.
     */
    val PlayerPill = RoundedCornerShape(15.6.dp)
}

val AuraMaterialShapes = Shapes(
    extraSmall = AuraShapes.SquircleSmall,
    small = AuraShapes.SquircleSmall,
    medium = AuraShapes.SquircleMedium,
    large = AuraShapes.SquircleLarge,
    extraLarge = AuraShapes.SquircleXLarge,
)
