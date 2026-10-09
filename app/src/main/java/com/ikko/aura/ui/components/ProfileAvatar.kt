package com.ikko.aura.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.CupertinoIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProfileAvatar(imageUri: String?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, imageUri) {
        value = if (imageUri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                // Decoded at the size it is shown at, not at the photo's: a camera picture decoded whole for a 36 dp circle
                // is tens of megabytes of bitmap and as big a texture, uploaded with Home's first frame.
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, android.net.Uri.parse(imageUri))
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > AVATAR_MAX_PX) {
                        val scale = AVATAR_MAX_PX.toFloat() / longest
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                }
            }.getOrNull()
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(CircleShape),
        )
    } else {
        Icon(
            imageVector = CupertinoIcons.Person,
            contentDescription = contentDescription,
            tint = AuraColors.TextSecondary,
            modifier = modifier.clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .padding(8.dp),
        )
    }
}

/** Largest side the profile picture is decoded at: twice its biggest use (the 36 dp avatar, the Réglages row) is plenty. */
private const val AVATAR_MAX_PX = 256
