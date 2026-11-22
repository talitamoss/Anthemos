// app/src/main/java/com/domain/app/ui/voice/VoiceScreen.kt
package com.domain.app.ui.voice

import android.Manifest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import com.domain.app.reflect.Ahead
import com.domain.app.reflect.Reflection
import android.content.Context
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.domain.app.voice.Entry
import com.domain.app.update.UpdateState
import com.domain.app.update.Updater
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PERMISSIONS = listOfNotNull(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.CALL_PHONE,
    // Prompts about the day arrive as notifications.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
).toTypedArray()

/**
 * The one page of Anthemos 2.0: what you've recorded, looked at
 * (reflections), today's entries, what was heard and said back, and the mic
 * at the bottom. Black, white and glaucous, as on anthemos.org.
 *
 * Each new [listenRequest] (above 0) starts listening straight away, for
 * when Anthemos is opened by a headset or assistant button.
 */
@Composable
fun VoiceScreen(
    listenRequest: Int = 0,
    onNavigateToSettings: () -> Unit = {},
    viewModel: VoiceViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val today by viewModel.today.collectAsState()

    val micGranted = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) viewModel.listen()
    }
    val talk = {
        if (micGranted()) viewModel.listen() else permissions.launch(PERMISSIONS)
    }

    // Each request once: coming back to Speak (from Settings, say) must not
    // start listening again for a request it already answered.
    LaunchedEffect(listenRequest) {
        if (listenRequest > viewModel.listenAnswered) {
            viewModel.listenAnswered = listenRequest
            talk()
        }
    }

    // A place, the trail or live sharing needs location: ask, then carry on.
    val locationAsk = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.locationAnswered(result[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    LaunchedEffect(state.askLocation) {
        if (state.askLocation) {
            locationAsk.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // Checks quietly on open; the update itself is in Settings.
    val update by Updater.state.collectAsState()
    val updateReady = update is UpdateState.Available || update is UpdateState.NeedsPermission
    LaunchedEffect(Unit) { Updater.check() }

    var typing by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val prefs = remember { context.getSharedPreferences("anthemos_prompts", Context.MODE_PRIVATE) }
    var senseDeclined by remember { mutableStateOf(prefs.getBoolean("sense_declined", false)) }
    var senseAsked by remember { mutableStateOf(prefs.getBoolean("sense_asked", false)) }
    // Back from Settings: check whether usage access was turned on.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSense()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val reflections by viewModel.reflections.collectAsState()
    val ahead by viewModel.ahead.collectAsState()
    val listening = state.phase == VoicePhase.LISTENING

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Anthemos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // Hey Poly and updates live in Settings; a dot here when an update is ready.
            IconButton(onClick = onNavigateToSettings) {
                Box {
                    Icon(Icons.Outlined.Settings, contentDescription = if (updateReady) "Settings, update ready" else "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (updateReady) Box(Modifier.size(6.dp).align(Alignment.TopEnd).background(MaterialTheme.colorScheme.primary, CircleShape))
                }
            }
        }

        // Screen time and estimated sleep, once, until allowed or declined.
        if (state.senseOff && !senseDeclined) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, MaterialTheme.colorScheme.outline).padding(12.dp)) {
                Text(
                    if (senseAsked) {
                        // Android blocks this switch for apps not installed from a store.
                        "Switch greyed out? In App info, tap \u22ee then Allow restricted settings. Then tap Allow again."
                    } else {
                        "Read screen time and sleep? It stays on the phone."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (senseAsked) OutlinedButton(onClick = { viewModel.appInfo() }) { Text("App info") }
                    Button(onClick = {
                        senseAsked = true
                        prefs.edit().putBoolean("sense_asked", true).apply()
                        viewModel.allowSense()
                    }) { Text("Allow") }
                    TextButton(onClick = {
                        senseDeclined = true
                        prefs.edit().putBoolean("sense_declined", true).apply()
                    }) { Text("Not now") }
                }
            }
        }

        // What's coming, what you've recorded, looked at, then today's entries.
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
            if (ahead.weather != null || ahead.events.isNotEmpty()) {
                item(key = "ahead") { AheadBlock(ahead) }
            }
            items(reflections, key = { it.id }) { ReflectionRow(it) }
            if (today.isNotEmpty()) {
                item(key = "today") {
                    Text(
                        "Today",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp)
                    )
                }
                items(today, key = { it.id }) { entry -> EntryRow(entry) }
            }
            if (reflections.isEmpty() && today.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Say or type what happened. It is kept on this phone, and what you record shows here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp)
                    )
                }
            }
        }

        // What was heard and said back, just above the mic.
        val heard = state.heard.ifEmpty { if (listening) "Listening\u2026" else "" }
        if (heard.isNotEmpty()) {
            Text(heard, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        if (state.reply.isNotEmpty()) {
            Text(
                state.reply,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
        // The answers to a question waiting: yes or no, which one, try again.
        val choices = state.waiting?.choices.orEmpty()
        if (choices.isNotEmpty() || state.canUndo || state.handoff != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                choices.forEach { choice ->
                    if (choice.primary) {
                        Button(onClick = { viewModel.choose(choice) }) { Text(choice.label) }
                    } else {
                        OutlinedButton(onClick = { viewModel.choose(choice) }) { Text(choice.label) }
                    }
                }
                if (state.canUndo) TextButton(onClick = { viewModel.undo() }) { Text("Undo") }
                if (state.handoff != null) OutlinedButton(onClick = { viewModel.handOff() }) { Text("Also add to Proton") }
            }
        }

        // Typing instead of talking: the same path, answered on screen only.
        val send = {
            viewModel.typed(typing)
            typing = ""
            keyboard?.hide()
        }
        OutlinedTextField(
            value = typing,
            onValueChange = { typing = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            placeholder = { Text("Or type", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            shape = RectangleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = {
                IconButton(onClick = { send() }, enabled = typing.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        )

        // The mic, at the bottom: a white ring, glaucous while it listens.
        Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            if (listening) {
                val pulse = rememberInfiniteTransition(label = "listening")
                val scale by pulse.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
                    label = "ring"
                )
                Box(Modifier.size(76.dp).scale(scale).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape))
            }
            IconButton(
                onClick = { if (listening) viewModel.stopListening() else talk() },
                modifier = Modifier.size(76.dp)
                    .border(1.dp, if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, CircleShape)
                    .semantics { contentDescription = if (listening) "Stop listening" else "Talk" },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background,
                    contentColor = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(32.dp))
            }
        }
    }
}

/** The weather, then the next few events: a time, quiet, and what. */
@Composable
private fun AheadBlock(ahead: Ahead) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Text("Coming up", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        ahead.weather?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
        }
        ahead.events.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(
                    e.time,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    e.title + (e.place?.let { " \u00b7 $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        HorizontalDivider(Modifier.padding(top = 14.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
    }
}

/** One reflection: a quiet title, the number, a note, and a week of thin bars. */
@Composable
private fun ReflectionRow(reflection: Reflection) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Text(reflection.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(reflection.value, style = MaterialTheme.typography.headlineMedium)
                reflection.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (reflection.days.any { it != null }) Bars(reflection.days)
        }
        HorizontalDivider(Modifier.padding(top = 14.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
    }
}

/** The week, oldest first: a bar for each day with something, a dot for each without. */
@Composable
private fun Bars(days: List<Float?>) {
    val top = days.filterNotNull().maxOrNull()?.takeIf { it > 0f } ?: 1f
    val bar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(width = 84.dp, height = 32.dp)) {
        val step = size.width / days.size
        val w = step * 0.45f
        days.forEachIndexed { i, v ->
            val x = i * step + (step - w) / 2
            if (v == null || v <= 0f) {
                drawRect(empty, topLeft = Offset(x, size.height - 2f), size = Size(w, 2f))
            } else {
                val h = (v / top).coerceIn(0.08f, 1f) * size.height
                drawRect(bar, topLeft = Offset(x, size.height - h), size = Size(w, h))
            }
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun EntryRow(entry: Entry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            TIME.format(entry.time.atZone(ZoneId.systemDefault())),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(summary(entry) ?: entry.words, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** What was found in an entry, in one line; null when nothing was. */
internal fun summary(entry: Entry): String? {
    if (entry.items.isEmpty()) return null
    return entry.items.joinToString(", ") { item ->
        when {
            item.kind == "call" -> "Called ${item.label}"
            item.kind == "place" -> entry.words
            item.minutes != null -> "${item.label}, ${item.minutes} min"
            item.ml != null && item.label == "water" -> "water, ${item.ml} ml"
            item.count > 1 -> "${item.count} ${item.label}"
            else -> item.label
        }
    }.replaceFirstChar { it.uppercase() }
}
