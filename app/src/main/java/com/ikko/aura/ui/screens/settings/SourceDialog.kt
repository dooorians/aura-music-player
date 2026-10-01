package com.ikko.aura.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.ikko.aura.source.remote.RemoteKind
import com.ikko.aura.source.remote.SourceConfigEntity
import kotlinx.coroutines.launch

/**
 * Adds a source, or edits one ([initial]). The password is only ever sent to the server being added, to check it.
 * Left empty when editing, it keeps the one already stored.
 */
@Composable
fun SourceDialog(
    initial: SourceConfigEntity?,
    onDismiss: () -> Unit,
    onTest: suspend (RemoteKind, String, String, String) -> String?,
    onSave: (RemoteKind, String, String, String, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val kind = RemoteKind.SUBSONIC
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canSave = url.isNotBlank() && user.isNotBlank() && (initial != null || password.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Ajouter une source" else "Modifier la source") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(kind.label, style = MaterialTheme.typography.titleSmall)
                Text(kind.hint, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Nom (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it; status = null }, label = { Text("Adresse du serveur") },
                    placeholder = { Text("http://192.168.1.20:4533") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    user, { user = it; status = null }, label = { Text("Utilisateur") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    password, { password = it; status = null },
                    label = { Text(if (initial == null) "Mot de passe" else "Mot de passe (vide : inchangé)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = !testing && url.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
                        onClick = {
                            testing = true
                            status = "Test en cours…"
                            scope.launch {
                                val error = onTest(kind, url, user, password)
                                status = if (error == null) "Connexion réussie" else "Échec : $error"
                                testing = false
                            }
                        },
                    ) { Text("Tester") }
                    if (onDelete != null) TextButton(onClick = onDelete) { Text("Supprimer la source") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onSave(kind, name, url, user, password) }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
