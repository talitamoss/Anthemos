// app/src/main/java/com/domain/app/ui/settings/sections/ConnectionsSettings.kt
package com.domain.app.ui.settings.sections

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.domain.app.calendar.LinkedCalendar
import com.domain.app.kronk.Kronk
import com.domain.app.place.LocationService
import com.domain.app.place.LocationState
import com.domain.app.sense.PhoneSense
import com.domain.app.ui.settings.SettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything Anthemos connects to, in one place (docs/anthemos-2.md,
 * "Connections"): each says whether it's connected, and a tap connects,
 * disconnects or opens the phone's setting. What needs nothing (music,
 * the Clock, the weather) says so.
 */
@Composable
fun ConnectionsSettings() {
    // Back from the browser or the phone's settings: read everything again.
    var resumed by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumed++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    KronkConnection(resumed)
    Line()
    HomeAssistantSetting()
    Line()
    ProtonCalendarConnection()
    Line()
    PhoneAccess(resumed)
    Line()
    Works(Icons.Default.MusicNote, "Music", "Whichever music app plays. Nothing to connect: say play, pause, skip, or play an artist.")
    Line()
    Works(Icons.Default.Alarm, "Clock", "Timers and alarms go to the phone's Clock app, so they ring as usual.")
    Line()
    Works(Icons.Default.Cloud, "Weather", "Open-Meteo: free, no account. Your position is sent rounded to about a kilometre.")
}

@Composable
private fun Line() = Divider(modifier = Modifier.padding(horizontal = 16.dp))

@Composable
private fun Works(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) =
    SettingsItem(icon = icon, title = title, subtitle = subtitle, onClick = {})

/** Kronk: sign in in the browser, which comes back to Anthemos and links this phone. */
@Composable
private fun KronkConnection(resumed: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kronk = remember { Kronk(context) }
    var linked by remember { mutableStateOf(kronk.linked) }
    var account by remember { mutableStateOf(kronk.account) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }

    // The link finishes a moment after coming back from the browser.
    LaunchedEffect(resumed) {
        repeat(10) {
            linked = kronk.linked
            account = kronk.account
            if (linked) note = null
            delay(1000)
        }
    }

    val host = Uri.parse(kronk.server).host
    SettingsItem(
        icon = Icons.Default.Hub,
        title = "Kronk",
        subtitle = note ?: if (linked) "Connected as ${account ?: "you"} on $host. ${mates(kronk.keptMates)}Live location shares from here."
        else "Not connected. Tap to sign in to $host.",
        onClick = {
            if (linked) {
                confirm = true
                return@SettingsItem
            }
            note = "Opening $host…"
            scope.launch {
                val page = runCatching { withContext(Dispatchers.IO) { kronk.signInPage() } }
                page.onSuccess {
                    note = "Sign in in the browser, then come back"
                    context.startActivity(Intent(Intent.ACTION_VIEW, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { note = it.message ?: "I couldn't reach Kronk" }
            }
        }
    )

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Disconnect Kronk?") },
            text = { Text("This phone is unlinked on Kronk, and any live sharing stops.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) { kronk.disconnect() }
                        LocationState(context).shares = emptyList()
                        LocationService.refresh(context)
                        linked = false
                        account = null
                    }
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

private fun mates(count: Int) = when (count) {
    0 -> ""
    1 -> "1 Mate known. "
    else -> "$count Mates known. "
}

/** Proton Calendar, read through its "Share with anyone" link. */
@Composable
private fun ProtonCalendarConnection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val calendar = remember { LinkedCalendar(context) }
    var linked by remember { mutableStateOf(calendar.linked) }
    var open by remember { mutableStateOf(false) }

    SettingsItem(
        icon = Icons.Default.CalendarMonth,
        title = "Proton Calendar",
        subtitle = if (linked) "Connected. Ask what's on; adding opens Proton filled in." else "Not connected. Tap to paste its share link.",
        onClick = { open = true }
    )

    if (!open) return
    var link by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Proton Calendar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!linked) {
                    OutlinedTextField(
                        value = link, onValueChange = { link = it }, singleLine = true,
                        label = { Text("Share link") }, modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    message ?: if (linked) "Anthemos reads your Proton calendar through its link." else
                        "In Proton Calendar on the web: Settings, Calendars, your calendar, Share with anyone, then copy a full view link.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            if (linked) {
                TextButton(onClick = {
                    calendar.forget()
                    linked = false
                    open = false
                }) { Text("Disconnect") }
            } else {
                TextButton(enabled = !busy && link.isNotBlank(), onClick = {
                    val url = LinkedCalendar.find(link)
                    if (url == null) {
                        message = "That isn't a calendar link"
                        return@TextButton
                    }
                    busy = true
                    message = "Checking…"
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { calendar.link(url) } }
                        busy = false
                        result.onSuccess {
                            linked = true
                            open = false
                        }.onFailure { message = it.message ?: "That didn't connect" }
                    }
                }) { Text("Connect") }
            }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(if (linked) "Close" else "Cancel") } }
    )
}

/** What the phone lets Anthemos see: screen time and location, each in the phone's own settings. */
@Composable
private fun PhoneAccess(resumed: Int) {
    val context = LocalContext.current
    val sense = remember { PhoneSense(context) }
    var screen by remember { mutableStateOf(sense.allowed()) }
    var location by remember { mutableStateOf(LocationService.allowed(context)) }
    LaunchedEffect(resumed) {
        screen = sense.allowed()
        location = LocationService.allowed(context)
    }
    SettingsItem(
        icon = Icons.Default.PhoneAndroid,
        title = "Screen time and sleep guess",
        subtitle = if (screen) "Allowed" else "Not allowed. Tap to turn on usage access for Anthemos.",
        onClick = { context.startActivity(sense.settings()) }
    )
    Line()
    SettingsItem(
        icon = Icons.Default.LocationOn,
        title = "Location",
        subtitle = if (location) "Allowed: places, the trail, weather here, live sharing" else "Not allowed. Tap to allow it in App info, Permissions.",
        onClick = {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    )
}
