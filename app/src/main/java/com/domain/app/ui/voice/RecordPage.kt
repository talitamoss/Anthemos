// app/src/main/java/com/domain/app/ui/voice/RecordPage.kt
package com.domain.app.ui.voice

import android.Manifest
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.domain.app.media.Saved
import com.domain.app.voice.WakeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Record (docs/anthemos-2.md, "Record and Sketch"): one big button to record
 * sound as an audio file, and what you've recorded below it, to play or delete.
 */
@Composable
fun RecordPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { Saved.recordings(context) }
    var tick by remember { mutableIntStateOf(0) }
    val files = remember(tick) { saved.list() }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var started by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val recording = recorder != null

    fun sync() = scope.launch { withContext(Dispatchers.IO) { runCatching { saved.sync() } } }

    fun stopPlaying() {
        player?.release()
        player = null
        playing = null
    }

    fun record() {
        stopPlaying()
        message = null
        val f = saved.newFile()
        WakeService.appListening = true // Hey Poly lets go of the microphone
        val r = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(128_000)
            setOutputFile(f.path)
        }
        runCatching { r.prepare(); r.start() }.onFailure {
            r.release()
            f.delete()
            WakeService.appListening = false
            message = "Couldn't start recording: ${it.message ?: "the microphone is busy"}"
            return
        }
        recorder = r
        file = f
        started = System.currentTimeMillis()
        elapsed = 0L
    }

    fun stop() {
        val r = recorder ?: return
        val f = file
        val kept = runCatching { r.stop() }.isSuccess && System.currentTimeMillis() - started >= 1000
        r.release()
        recorder = null
        file = null
        WakeService.appListening = false
        if (!kept) {
            f?.delete()
            message = "That was too short to keep."
        }
        tick++
        if (kept) sync()
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) record() else message = "Recording needs the microphone."
    }

    LaunchedEffect(recording) {
        while (recording) {
            elapsed = System.currentTimeMillis() - started
            delay(250)
        }
    }
    // Any that didn't reach the pod last time.
    LaunchedEffect(Unit) { sync() }
    DisposableEffect(Unit) {
        onDispose {
            if (recorder != null) stop()
            stopPlaying()
        }
    }

    Dialog(
        onDismissRequest = { if (!recording) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Record", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, enabled = !recording) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }

                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = { if (recording) stop() else if (WakeService.allowed(context)) record() else mic.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier.size(96.dp)
                            .border(1.dp, if (recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, CircleShape)
                            .semantics { contentDescription = if (recording) "Stop recording" else "Record" },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background,
                            contentColor = if (recording) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(40.dp))
                    }
                    Text(
                        if (recording) length(elapsed) else "Tap to record",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                }

                if (files.isEmpty()) {
                    Text("What you record is kept on this phone, and in your pod if you've linked one.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(files, key = { it.name }) { f ->
                        val seconds = remember(f) { duration(f) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(WHEN.format(Instant.ofEpochMilli(f.lastModified()).atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.bodyLarge)
                                Text(length(seconds), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(enabled = !recording, onClick = {
                                if (playing == f) stopPlaying() else {
                                    stopPlaying()
                                    player = MediaPlayer().apply {
                                        setDataSource(f.path)
                                        setOnCompletionListener { stopPlaying() }
                                        prepare()
                                        start()
                                    }
                                    playing = f
                                }
                            }) {
                                Icon(if (playing == f) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = if (playing == f) "Stop" else "Play")
                            }
                            IconButton(enabled = !recording, onClick = { deleting = f }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        deleting?.let { f ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                text = { Text("Delete this recording, from the phone and your pod?") },
                confirmButton = {
                    TextButton(onClick = {
                        if (playing == f) stopPlaying()
                        deleting = null
                        scope.launch {
                            val result = runCatching { withContext(Dispatchers.IO) { saved.delete(f) } }
                            result.onFailure { message = "Couldn't delete it from the pod: ${it.message ?: "try again"}" }
                            tick++
                        }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }
            )
        }
    }
}

private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

/** Milliseconds as m:ss. */
private fun length(ms: Long): String = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }

private fun duration(file: File): Long = runCatching {
    MediaMetadataRetriever().run {
        try {
            setDataSource(file.path)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            release()
        }
    }
}.getOrDefault(0L)
