// app/src/main/java/com/domain/app/ui/settings/sections/PodSetting.kt
package com.domain.app.ui.settings.sections

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.domain.app.pod.PodEntry
import com.domain.app.ui.settings.SettingsItem
import com.domain.app.pod.PodSync
import com.domain.app.pod.PodWorker
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Your pod (docs/anthemos-2.md, "Pod"): connect with a credential made on
 * the pod's account page, keep the recovery code, sync now, disconnect.
 */
@Composable
fun PodSetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sync = remember { PodSync(context) }
    val dao = remember { EntryPointAccessors.fromApplication(context.applicationContext, PodEntry::class.java).dao() }
    var tick by remember { mutableIntStateOf(0) }
    val status = remember(tick) { sync.status() }
    var open by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }

    SettingsItem(
        icon = Icons.Default.Storage,
        title = "Pod",
        subtitle = when {
            !status.connected -> "Not connected. Tap to copy everything to your pod, sealed on the phone."
            status.problem != null -> "Last sync didn't finish: ${status.problem}"
            status.last != null -> "${status.items} items, sealed. Last sync ${WHEN.format(status.last.atZone(ZoneId.systemDefault()))}."
            else -> "Connected. The first sync is on its way."
        },
        onClick = { open = true }
    )

    code?.let { shown ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Your recovery code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Save it in your password manager. It is the only way to read your pod's copy on a new phone; no one, us included, can recover it.", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(shown, style = MaterialTheme.typography.bodyLarge) }
                }
            },
            confirmButton = { TextButton(onClick = { code = null; tick++ }) { Text("I've saved it") } },
            dismissButton = { TextButton(onClick = { copy(context, shown) }) { Text("Copy") } }
        )
    }

    if (!open) return
    var pod by remember { mutableStateOf(status.pod ?: "https://pod.anthemos.org/tal/") }
    var id by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Pod") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.connected) {
                    Text(message ?: "Copying to ${status.pod}. Each item is sealed on this phone before it goes, so the pod holds only scrambled files.", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(pod, { pod = it }, singleLine = true, label = { Text("Pod address") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(id, { id = it }, singleLine = true, label = { Text("Token id") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(secret, { secret = it }, singleLine = true, label = { Text("Token secret") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(recovery, { recovery = it }, singleLine = true, label = { Text("Recovery code, if the pod has data") }, modifier = Modifier.fillMaxWidth())
                    Text(
                        message ?: "On your pod's account page (pod.anthemos.org/.account/), under Client credentials, create a token for your WebID, then copy its id and secret here.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            if (status.connected) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    message = "Syncing…"
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { sync.sync(dao) } }
                        busy = false
                        tick++
                        message = result.fold({ "$it items in the pod" }, { it.message ?: "That didn't finish" })
                    }
                }) { Text("Sync now") }
            } else {
                TextButton(enabled = !busy && pod.isNotBlank() && id.isNotBlank() && secret.isNotBlank(), onClick = {
                    busy = true
                    message = "Checking…"
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { sync.connect(dao, pod, id, secret, recovery) } }
                        busy = false
                        result.onSuccess {
                            PodWorker.schedule(context)
                            PodWorker.now(context)
                            open = false
                            code = it
                        }.onFailure { message = it.message ?: "That didn't connect" }
                    }
                }) { Text("Connect") }
            }
        },
        dismissButton = {
            if (status.connected) {
                Column {
                    TextButton(onClick = { code = sync.recoveryCode(); open = false }) { Text("Recovery code") }
                    TextButton(onClick = {
                        sync.forget()
                        PodWorker.stop(context)
                        tick++
                        open = false
                    }) { Text("Disconnect") }
                }
            } else {
                TextButton(onClick = { open = false }) { Text("Cancel") }
            }
        }
    )
}

private val WHEN = DateTimeFormatter.ofPattern("d MMM, H:mm")

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Anthemos recovery code", text))
}
