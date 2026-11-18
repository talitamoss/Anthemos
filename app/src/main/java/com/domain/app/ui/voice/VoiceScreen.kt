// app/src/main/java/com/domain/app/ui/voice/VoiceScreen.kt
package com.domain.app.ui.voice

import android.Manifest
import android.content.Context
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
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
import com.domain.app.update.UpdateBanner
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
 * Speak: the home of Anthemos 2.0. One button to talk, what was heard and
 * said back, Undo for what was just kept, and today's entries.
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

    LaunchedEffect(listenRequest) {
        if (listenRequest > 0) talk()
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

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Anthemos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }

        UpdateBanner(Modifier.padding(top = 8.dp))

        // Screen time and estimated sleep, once, until allowed or declined.
        if (state.senseOff && !senseDeclined) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
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
        }

        Spacer(Modifier.height(16.dp))

        val heard = state.heard.ifEmpty { if (state.phase == VoicePhase.LISTENING) "Listening\u2026" else "" }
        if (heard.isNotEmpty()) {
            Text(
                heard,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (state.reply.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                state.reply,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (state.canUndo) {
            TextButton(onClick = { viewModel.undo() }) { Text("Undo") }
        }

        Spacer(Modifier.height(16.dp))

        val listening = state.phase == VoicePhase.LISTENING
        Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
            // A ring that breathes while the microphone is open.
            if (listening) {
                val pulse = rememberInfiniteTransition(label = "listening")
                val scale by pulse.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.45f,
                    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                    label = "ring"
                )
                Box(
                    Modifier.size(120.dp).scale(scale)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.25f), CircleShape)
                )
            }
            FilledIconButton(
                onClick = { if (listening) viewModel.stopListening() else talk() },
                modifier = Modifier.size(120.dp).semantics {
                    contentDescription = if (listening) "Stop listening" else "Talk"
                },
                shape = CircleShape,
                colors = if (listening) {
                    IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    IconButtonDefaults.filledIconButtonColors()
                }
            ) {
                Icon(
                    if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp)
                )
            }
        }

        if (state.pendingCall != null || state.pendingEvent != null || state.pendingShare != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.confirm(true) }) {
                    Text(
                        when {
                            state.pendingShare != null -> "Yes, share"
                            state.pendingCall != null -> "Yes, call"
                            else -> "Yes, add"
                        }
                    )
                }
                OutlinedButton(onClick = { viewModel.confirm(false) }) { Text("No") }
            }
        }
        if (state.unsure != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.retry() }) { Text("Try again") }
                OutlinedButton(onClick = { viewModel.keepUnsure() }) {
                    Text(if (state.unsure?.items.isNullOrEmpty()) "Keep as note" else "Yes, log it")
                }
            }
        }
        state.choosing?.let { choose ->
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choose.options.forEach { entry ->
                    OutlinedButton(onClick = { viewModel.choose(entry) }) {
                        Text(TIME.format(entry.time.atZone(ZoneId.systemDefault())))
                    }
                }
            }
        }
        if (state.handoff != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { viewModel.handOff() }) { Text("Also add to Proton") }
        }

        // Typing instead of talking: the same path, answered on screen only.
        Spacer(Modifier.height(12.dp))
        val send = {
            viewModel.typed(typing)
            typing = ""
            keyboard?.hide()
        }
        OutlinedTextField(
            value = typing,
            onValueChange = { typing = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Or type") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = {
                IconButton(onClick = { send() }, enabled = typing.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        )

        Spacer(Modifier.height(20.dp))
        if (today.isNotEmpty()) {
            Text(
                "Today",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(today, key = { it.id }) { entry -> EntryRow(entry) }
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
