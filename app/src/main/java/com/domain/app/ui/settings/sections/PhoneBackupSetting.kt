// app/src/main/java/com/domain/app/ui/settings/sections/PhoneBackupSetting.kt
package com.domain.app.ui.settings.sections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domain.app.backup.Pack
import com.domain.app.backup.PhoneBackup
import com.domain.app.backup.TextingApp
import com.domain.app.pod.PodSync
import com.domain.app.ui.settings.SettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Phone backup (docs/anthemos-2.md, "Phone backup"): contacts, texts and
 * calls kept on the pod daily, and put back on this phone when asked.
 */
@Composable
fun PhoneBackupSetting(resumed: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = remember { PhoneBackup(context) }
    var tick by remember { mutableIntStateOf(0) }
    val status = remember(tick, resumed) { TextingApp.tidy(context); backup.status() }
    val pod = remember(tick, resumed) { PodSync(context).connected }
    var open by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var handBack by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf(*Pack.KINDS.toTypedArray()) }
    var next by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        next?.invoke()
        next = null
    }
    fun allowed(then: () -> Unit) {
        next = then
        permissions.launch(PhoneBackup.PERMISSIONS)
    }

    fun restore() {
        busy = true
        scope.launch {
            val kinds = chosen.filter { it != Pack.TEXTS || backup.isTextingApp() }
            val result = runCatching { withContext(Dispatchers.IO) { backup.restore(kinds) { message = "Putting back $it…" } } }
            busy = false
            tick++
            message = result.fold({ added ->
                val said = added.entries.joinToString(", ") { "${it.value} ${PhoneBackup.NAMES[it.key]}" }
                val skipped = if (Pack.TEXTS in chosen && Pack.TEXTS !in kinds) " Texts were left: Anthemos wasn't made the texting app." else ""
                "Added $said.$skipped"
            }, { it.message ?: "That didn't finish" })
            if (Pack.TEXTS in kinds) handBack = true
        }
    }

    val texting = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { restore() }

    SettingsItem(
        icon = Icons.Default.Backup,
        title = "Phone backup",
        subtitle = when {
            !pod -> "Connect your pod first. Then contacts, texts and calls can be kept there, sealed."
            !status.on -> "Off. Tap to keep your contacts, texts and call log on your pod, and put them back on a new phone."
            status.problem != null -> "Last backup didn't finish: ${status.problem}"
            status.last != null && status.index != null -> Pack.KINDS.joinToString(", ") { "${status.index.total(it)} ${PhoneBackup.NAMES[it]}" } +
                ". Last backup ${WHEN.format(status.last.atZone(ZoneId.systemDefault()))}."
            else -> "On. The first backup is on its way."
        },
        onClick = { if (pod) { open = true; message = null } }
    )

    if (handBack) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Texts are back") },
            text = { Text("Now choose your usual texting app again, so new texts go to it.", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    handBack = false
                    runCatching { context.startActivity(TextingApp.handBack(context)) }
                }) { Text("Choose texting app") }
            }
        )
        return
    }

    if (!open) return
    AlertDialog(
        onDismissRequest = { if (!busy) { open = false; restoring = false } },
        title = { Text(if (restoring) "Restore to this phone" else "Phone backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (restoring) {
                    val counts = status.index
                    Text("Adds what this phone is missing; nothing here is changed or removed.", style = MaterialTheme.typography.bodySmall)
                    for (kind in Pack.KINDS) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(kind in chosen, { if (it) chosen += kind else chosen -= kind }, enabled = !busy)
                            Text("${counts?.total(kind) ?: "?"} ${PhoneBackup.NAMES[kind]}")
                        }
                    }
                    if (Pack.TEXTS in chosen && !backup.isTextingApp()) {
                        Text("For texts, Android will ask to make Anthemos the texting app for a moment. You'll choose yours again after.", style = MaterialTheme.typography.bodySmall)
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                } else {
                    Text(
                        message ?: if (status.on) "Once a day, what's new among your contacts, texts and calls is added to your pod, sealed on this phone first. Deleting something here doesn't delete it there."
                        else "Keeps your contacts, texts and call log on your pod, sealed on this phone first, and adds what's new once a day. Restore puts them back on a new or reset phone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            when {
                restoring -> TextButton(enabled = !busy && chosen.isNotEmpty(), onClick = {
                    allowed {
                        if (Pack.TEXTS in chosen && !backup.isTextingApp()) texting.launch(TextingApp.ask(context)) else restore()
                    }
                }) { Text("Restore") }
                status.on -> TextButton(enabled = !busy, onClick = {
                    busy = true
                    message = "Backing up…"
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { backup.backup() } }
                        busy = false
                        tick++
                        message = result.fold(
                            { index -> "On your pod: " + Pack.KINDS.joinToString(", ") { "${index.total(it)} ${PhoneBackup.NAMES[it]}" } + "." },
                            { it.message ?: "That didn't finish" }
                        )
                    }
                }) { Text("Back up now") }
                else -> TextButton(onClick = {
                    allowed {
                        backup.turn(true)
                        tick++
                        message = "On. The first backup starts in a moment. Anything not allowed is left out."
                        com.domain.app.backup.BackupWorker.now(context)
                    }
                }) { Text("Turn on") }
            }
        },
        dismissButton = {
            Column {
                if (!restoring) {
                    TextButton(enabled = !busy, onClick = {
                        restoring = true
                        message = "Looking at the pod…"
                        busy = true
                        scope.launch {
                            val result = runCatching { withContext(Dispatchers.IO) { backup.look() } }
                            busy = false
                            tick++
                            message = result.fold({ if (it == null) "Nothing is backed up on the pod yet." else null }, { it.message ?: "Couldn't reach the pod" })
                        }
                    }) { Text("Restore to this phone") }
                    if (status.on) TextButton(enabled = !busy, onClick = { backup.turn(false); tick++; open = false }) { Text("Turn off") }
                }
                TextButton(enabled = !busy, onClick = { if (restoring) { restoring = false; message = null } else open = false }) {
                    Text(if (restoring) "Back" else "Close")
                }
            }
        }
    )
}

private val WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm")
