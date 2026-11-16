// app/src/main/java/com/domain/app/ui/voice/VoiceScreen.kt
package com.domain.app.ui.voice

import android.Manifest
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
import androidx.compose.material3.HorizontalDivider
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
 * said back, and today's entries with undo.
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

        Spacer(Modifier.height(24.dp))

        Text(
            text = state.heard.ifEmpty {
                if (state.phase == VoicePhase.LISTENING) "Listening…" else "Tap and say what happened, ask a question, or say who to call"
            },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
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

        Spacer(Modifier.height(24.dp))

        val listening = state.phase == VoicePhase.LISTENING
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
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

        if (state.pendingCall != null) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.confirm(true) }) { Text("Yes, call") }
                OutlinedButton(onClick = { viewModel.confirm(false) }) { Text("No") }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Today", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (today.isNotEmpty()) {
                TextButton(onClick = { viewModel.undo() }) { Text("Undo last") }
            }
        }
        HorizontalDivider()

        if (today.isEmpty()) {
            Text(
                "Nothing yet today.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
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
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            TIME.format(entry.time.atZone(ZoneId.systemDefault())),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(Modifier.weight(1f)) {
            val found = summary(entry)
            Text(found ?: entry.words, style = MaterialTheme.typography.bodyLarge)
            if (found != null && entry.items.none { it.kind == "call" }) {
                Text(
                    "\u201c${entry.words}\u201d",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** What was found in an entry, in one line; null when nothing was. */
internal fun summary(entry: Entry): String? {
    if (entry.items.isEmpty()) return null
    return entry.items.joinToString(", ") { item ->
        when {
            item.kind == "call" -> "Called ${item.label}"
            item.minutes != null -> "${item.label}, ${item.minutes} min"
            item.ml != null && item.label == "water" -> "water, ${item.ml} ml"
            item.count > 1 -> "${item.count} ${item.label}"
            else -> item.label
        }
    }.replaceFirstChar { it.uppercase() }
}
