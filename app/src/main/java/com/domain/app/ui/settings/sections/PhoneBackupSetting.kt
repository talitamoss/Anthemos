// app/src/main/java/com/domain/app/ui/settings/sections/PhoneBackupSetting.kt
package com.domain.app.ui.settings.sections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import com.domain.app.backup.Pack
import com.domain.app.backup.PhoneBackup
import com.domain.app.backup.TextingApp
import com.domain.app.pod.PodSync
import com.domain.app.ui.settings.SettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    // Asked for a result: Android only takes the request from an app waiting on one.
    // Anthemos may restart meanwhile, so TextsRestore, not this, carries on.
    val texting = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        TextsRestoreSignal.check.value++
    }

    /**
     * Contacts and calls now; texts are left waiting for TextsRestore, which puts
     * them back once Anthemos is the texting app, whichever screen it comes back to.
     */
    fun restore() {
        busy = true
        scope.launch {
            val now = chosen.filter { it != Pack.TEXTS }
            val result = runCatching { withContext(Dispatchers.IO) { backup.restore(now, progress = { message = "Putting back $it…" }) } }
            busy = false
            tick++
            message = result.fold({ added ->
                added.entries.joinToString(", ", "Added ", ".") { "${it.value} ${PhoneBackup.NAMES[it.key]}" }.takeIf { added.isNotEmpty() }
            }, { it.message ?: "That didn't finish" })
            if (result.isSuccess && Pack.TEXTS in chosen) {
                backup.textsWaiting = true
                if (backup.isTextingApp()) TextsRestoreSignal.check.value++
                else {
                    open = false
                    restoring = false
                    texting.launch(TextingApp.ask(context))
                }
            }
        }
    }

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

    if (!open) return

    fun backUpNow() {
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
    }

    fun lookAtPod() {
        restoring = true
        message = "Looking at the pod…"
        busy = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { backup.look() } }
            busy = false
            tick++
            message = result.fold({ if (it == null) "Nothing is backed up on the pod yet." else null }, { it.message ?: "Couldn't reach the pod" })
        }
    }

    // One column of full-width actions, then Close or Back: the dialog's own button row
    // had three or four buttons stacked unevenly beside another.
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
                    Button(enabled = !busy && chosen.isNotEmpty(), onClick = { allowed { restore() } }, modifier = Modifier.fillMaxWidth()) { Text("Restore") }
                } else {
                    Text(
                        message ?: if (status.on) "Once a day, what's new among your contacts, texts and calls is added to your pod, sealed on this phone first. Deleting something here doesn't delete it there."
                        else "Keeps your contacts, texts and call log on your pod, sealed on this phone first, and adds what's new once a day. Restore puts them back on a new or reset phone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (status.on) {
                        Button(enabled = !busy, onClick = { backUpNow() }, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
                    } else {
                        Button(onClick = {
                            allowed {
                                backup.turn(true)
                                tick++
                                message = "On. The first backup starts in a moment. Anything not allowed is left out."
                                com.domain.app.backup.BackupWorker.now(context)
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Turn on") }
                    }
                    OutlinedButton(enabled = !busy, onClick = { lookAtPod() }, modifier = Modifier.fillMaxWidth()) { Text("Restore to this phone") }
                    if (status.on) OutlinedButton(enabled = !busy, onClick = { backup.turn(false); tick++; open = false }, modifier = Modifier.fillMaxWidth()) { Text("Turn off") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { if (restoring) { restoring = false; message = null } else open = false }) {
                Text(if (restoring) "Back" else "Close")
            }
        }
    )
}

/** A nudge to look for waiting texts now, without waiting to come back to the app. */
object TextsRestoreSignal {
    val check = kotlinx.coroutines.flow.MutableStateFlow(0)
}

/**
 * Puts back texts left waiting by Restore to this phone, once Anthemos is the
 * texting app. On the main screen, not Settings: Android restarts Anthemos when
 * it becomes the texting app, so it comes back to wherever it opens (2.40).
 * Then sends you to choose your usual texting app again.
 */
@Composable
fun TextsRestore() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = remember { PhoneBackup(context) }
    var resumed by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumed++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val nudged by TextsRestoreSignal.check.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    LaunchedEffect(resumed, nudged) {
        if (busy || !backup.textsWaiting) return@LaunchedEffect
        // Android can say yes before it reports Anthemos as the texting app, so after
        // an answer, look again for a minute (2.42: it had looked once, too soon).
        if (!backup.isTextingApp()) {
            if (nudged == 0) return@LaunchedEffect
            message = "Waiting for Android to make Anthemos the texting app…"
            var waited = 0
            while (!backup.isTextingApp() && waited < 60) { delay(1000); waited++ }
            if (!backup.isTextingApp()) {
                backup.textsWaiting = false
                message = "Android didn't make Anthemos the texting app, so the texts weren't put back. Try Restore again, and choose Anthemos when Android asks."
                return@LaunchedEffect
            }
        }
        busy = true
        message = "Putting your texts back…"
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { backup.restore(listOf(Pack.TEXTS), done = { d, t -> progress = d to t }) } }
            progress = null
            backup.textsWaiting = false
            busy = false
            message = result.fold(
                { "Added ${it[Pack.TEXTS] ?: 0} texts. Now choose your usual texting app again, so new texts go to it." },
                { "Texts didn't come back: ${it.message ?: "something went wrong"}. Choose your usual texting app again, then try Restore once more." }
            )
        }
    }

    val shown = message ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Your texts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(shown, style = MaterialTheme.typography.bodySmall)
                progress?.let { (d, t) ->
                    LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, modifier = Modifier.fillMaxWidth())
                    Text("%,d of %,d".format(d, t), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (busy) Unit
            else if (backup.isTextingApp()) TextButton(onClick = {
                message = null
                runCatching { context.startActivity(TextingApp.handBack(context)) }
            }) { Text("Choose texting app") }
            else TextButton(onClick = { message = null }) { Text("OK") }
        }
    )
}

private val WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm")
