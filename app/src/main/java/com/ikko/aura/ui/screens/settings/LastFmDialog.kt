package com.ikko.aura.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ikko.aura.lastfm.LastFm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Connects a Last.fm account the way the site wants it: the browser opens on Last.fm, the account is approved there (the
 * password is typed on Last.fm's own page, never in the app), and the app notices by itself once it is.
 */
@Composable
fun LastFmDialog(lastFm: LastFm, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(lastFm.savedKey) }
    var secret by remember { mutableStateOf(lastFm.savedSecret) }
    var token by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val plain = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

    // Once the browser is open: asked again every two seconds whether the account has been approved, for ten minutes.
    LaunchedEffect(token) {
        val t = token ?: return@LaunchedEffect
        repeat(300) {
            val answer = lastFm.finishWebLogin(key, secret, t)
            when {
                answer.isFailure -> { status = "Échec : ${answer.exceptionOrNull()?.message}"; token = null; return@LaunchedEffect }
                answer.getOrNull() == true -> { onDismiss(); return@LaunchedEffect }
            }
            delay(2_000)
        }
        status = "Délai dépassé, recommence."
        token = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Se connecter à Last.fm") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (lastFm.hasBuiltInKey) "Tu te connectes sur le site de Last.fm, puis tu reviens ici."
                    else "Tu te connectes sur le site de Last.fm, puis tu reviens ici. Il faut une clé d'API gratuite : last.fm/api/account/create (la clé et le « shared secret » s'y trouvent).",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!lastFm.hasBuiltInKey) OutlinedTextField(key, { key = it.trim(); status = null }, label = { Text("Clé d'API") }, singleLine = true, enabled = token == null, keyboardOptions = plain, modifier = Modifier.fillMaxWidth())
                if (!lastFm.hasBuiltInKey) OutlinedTextField(secret, { secret = it.trim(); status = null }, label = { Text("Secret partagé") }, singleLine = true, enabled = token == null, keyboardOptions = plain, modifier = Modifier.fillMaxWidth())
                if (token != null) Text("En attente de ton accord sur Last.fm…", style = MaterialTheme.typography.bodySmall)
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && key.isNotBlank() && secret.isNotBlank(),
                onClick = {
                    working = true
                    status = null
                    scope.launch {
                        lastFm.startWebLogin(key, secret)
                            .onSuccess { (t, url) ->
                                token = t
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure { status = "Aucun navigateur trouvé."; token = null }
                            }
                            .onFailure { status = "Échec : ${it.message}" }
                        working = false
                    }
                },
            ) { Text(if (token == null) "Ouvrir Last.fm" else "Rouvrir Last.fm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
