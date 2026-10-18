package com.ikko.aura.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** No glass: the glyph alone, with a soft drop-shadow (a blurred dark copy a hair below) to hold it over any ground. */
@Composable
fun ShadowedGlyph(
    imageVector: ImageVector,
    contentDescription: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Box(modifier.size(size)) {
        Icon(
            imageVector, null, tint = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier.size(size).offset(y = 2.dp).blur(4.dp),
        )
        Icon(imageVector, contentDescription, tint = tint, modifier = Modifier.size(size))
    }
}
