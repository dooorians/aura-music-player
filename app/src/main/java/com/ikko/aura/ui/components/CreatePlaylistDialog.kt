package com.ikko.aura.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding

private val DialogAccent = Color(0xFFFF375F)
private val DialogCardBg = Color(0xFF19191C)
private val DialogCoverBg = Color(0xFF26262C)
private val DialogButtonBg = Color(0xFF2C2C31)

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, artworkUri: String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var selectedArtworkUri by remember { mutableStateOf<String?>(null) }
    var artworkBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val focusRequester = remember { FocusRequester() }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val savedUri = withContext(Dispatchers.IO) {
                runCatching {
                    val coversDir = File(context.filesDir, "playlist_covers").apply { mkdirs() }
                    val targetFile = File(coversDir, "custom_${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val bmp = runCatching {
                        val src = android.graphics.ImageDecoder.createSource(targetFile)
                        android.graphics.ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                            decoder.isMutableRequired = true
                        }
                    }.getOrNull()
                    Pair(Uri.fromFile(targetFile).toString(), bmp)
                }.getOrNull()
            }
            if (savedUri != null) {
                selectedArtworkUri = savedUri.first
                artworkBitmap = savedUri.second
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .imePadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(350.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(DialogCardBg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* stop propagation */ },
                    )
                    .padding(horizontal = 22.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Header: Close button, Centered title, Check button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(DialogButtonBg)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            CupertinoIcons.Close,
                            contentDescription = "Fermer",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Text(
                        text = "Nouvelle playlist",
                        color = Color.White,
                        style = TextStyle(
                            fontFamily = AuraFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                    )

                    val canConfirm = name.isNotBlank()
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(DialogButtonBg)
                            .clickable(enabled = canConfirm) {
                                if (canConfirm) {
                                    onCreate(name.trim(), selectedArtworkUri)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            CupertinoIcons.Check,
                            contentDescription = "Confirmer",
                            tint = if (canConfirm) Color.White else Color.White.copy(alpha = 0.25f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Center Square Artwork
                val coverShape = RoundedCornerShape(24.dp)
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .clip(coverShape)
                        .background(DialogCoverBg)
                        .clickable { photoPicker.launch(arrayOf("image/*")) },
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = artworkBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Pochette de la playlist",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        // Pink circle button with photo icon
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(DialogAccent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                CupertinoIcons.Photo,
                                contentDescription = "Choisir une photo",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(30.dp))

                // Centered text input
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(
                        fontFamily = AuraFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(DialogAccent),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (name.isNotBlank()) {
                                onCreate(name.trim(), selectedArtworkUri)
                            }
                        },
                    ),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (name.isEmpty()) {
                                Text(
                                    text = "Titre de la playlist",
                                    color = Color.White.copy(alpha = 0.32f),
                                    style = TextStyle(
                                        fontFamily = AuraFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp,
                                        textAlign = TextAlign.Center,
                                    ),
                                )
                            }
                            innerTextField()
                        }
                    },
                )

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
