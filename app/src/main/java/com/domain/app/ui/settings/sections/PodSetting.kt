// app/src/main/java/com/domain/app/ui/settings/sections/PodSetting.kt
package com.domain.app.ui.settings.sections

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.domain.app.pod.PodInbox
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

    code?.let { RecoveryCodeDialog(it) { code = null; tick++ } }

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
                    Text("New here? Make a pod by signing in with Kronk. Only your Kronk username is shared.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        open = false
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PodSync.SIGNUP + "start")))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Create with Kronk") }
                    Text("Or connect a pod you have:", style = MaterialTheme.typography.bodySmall)
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

/** Shown once at connecting, and again from Settings. */
@Composable
private fun RecoveryCodeDialog(shown: String, onDone: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Your recovery code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Save it in your password manager. It is the only way to read your pod's copy on a new phone; no one, us included, can recover it.", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(shown, style = MaterialTheme.typography.bodyLarge) }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("I've saved it") } },
        dismissButton = { TextButton(onClick = { copy(context, shown) }) { Text("Copy") } }
    )
}

/**
 * Back from making a pod with Kronk (`PodInbox`): trades the pickup code for
 * the pod's credential and connects. A pod made before, from another phone,
 * asks for its recovery code first.
 */
@Composable
fun PodJoin() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickup by PodInbox.code.collectAsState()
    var joined by remember { mutableStateOf<PodSync.Joined?>(null) }
    var needCode by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    val sync = remember { PodSync(context) }
    val dao = remember { EntryPointAccessors.fromApplication(context.applicationContext, PodEntry::class.java).dao() }

    fun connect(j: PodSync.Joined) {
        busy = true
        message = "Connecting to your pod…"
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { sync.connect(dao, j.pod, j.id, j.secret, recovery.takeIf { needCode }) } }
            busy = false
            result.onSuccess {
                PodWorker.schedule(context)
                PodWorker.now(context)
                joined = null
                code = it
            }.onFailure {
                if (!needCode && it.message?.contains("recovery code") == true) {
                    needCode = true
                    message = "Your pod already has Anthemos data from before. Enter its recovery code to bring it here."
                } else {
                    message = it.message ?: "That didn't connect"
                }
            }
        }
    }

    LaunchedEffect(pickup) {
        val c = pickup ?: return@LaunchedEffect
        PodInbox.code.value = null
        needCode = false
        recovery = ""
        message = "Setting up your pod…"
        busy = true
        val result = runCatching { withContext(Dispatchers.IO) { sync.pickup(c) } }
        busy = false
        result.onSuccess { joined = it; connect(it) }.onFailure { message = it.message; joined = null }
    }

    code?.let { RecoveryCodeDialog(it) { code = null } }
    val j = joined
    if (j == null && message == null) return
    if (j == null && !busy) {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Pod") },
            text = { Text(message.orEmpty()) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
        return
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(j?.let { "Pod for @${it.username}" } ?: "Pod") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message.orEmpty(), style = MaterialTheme.typography.bodySmall)
                if (needCode) {
                    OutlinedTextField(recovery, { recovery = it }, singleLine = true, label = { Text("Recovery code") }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (needCode && j != null) TextButton(enabled = !busy && recovery.isNotBlank(), onClick = { connect(j) }) { Text("Connect") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { joined = null; message = null }) { Text("Cancel") }
        }
    )
}

private val WHEN = DateTimeFormatter.ofPattern("d MMM, H:mm")

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Anthemos recovery code", text))
}
