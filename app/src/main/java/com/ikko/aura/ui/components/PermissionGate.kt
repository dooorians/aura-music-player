package com.ikko.aura.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.PhosphorIcons

private val audioPermission: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var isGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        isGranted = granted
    }

    // Best-effort, non-blocking: without this the MediaSessionService notification (lock-screen
    // controls, notification-shade play/pause) is silently suppressed by the OS on API 33+, even
    // though the playback code itself works fine. Requested once right after library access is
    // granted, rather than gating content on it — a music player should still play without it.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(isGranted) {
        if (isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationPermission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(context, notificationPermission) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(notificationPermission)
            }
        }
    }

    if (isGranted) {
        content()
    } else {
        PermissionRationale(onRequestClick = { launcher.launch(audioPermission) })
    }
}

@Composable
private fun PermissionRationale(onRequestClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AuraColors.Black)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = PhosphorIcons.Library,
                contentDescription = null,
                tint = AuraColors.AccentPink,
                modifier = Modifier.height(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Accès à ta musique",
                style = MaterialTheme.typography.headlineMedium,
                color = AuraColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Aura a besoin d'accéder à tes fichiers audio locaux pour scanner ta bibliothèque.",
                style = MaterialTheme.typography.bodyMedium,
                color = AuraColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onRequestClick,
                shape = AuraShapes.Pill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AuraColors.AccentPink,
                    contentColor = AuraColors.TextPrimary,
                ),
            ) {
                Text("Autoriser l'accès")
            }
        }
    }
}
