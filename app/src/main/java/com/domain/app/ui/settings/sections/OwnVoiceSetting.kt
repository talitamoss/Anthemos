// app/src/main/java/com/domain/app/ui/settings/sections/OwnVoiceSetting.kt
package com.domain.app.ui.settings.sections

import android.Manifest
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domain.app.pod.PodSync
import com.domain.app.ui.settings.SettingsItem
import com.domain.app.voice.ClipRecorder
import com.domain.app.voice.OwnVoice
import com.domain.app.voice.WakeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Your voice (docs/anthemos-2.md, "Your voice"): read the sentences aloud,
 * one at a time, as many at a sitting as you like; each is sealed into your pod.
 */
@Composable
fun OwnVoiceSetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember { OwnVoice(context) }
    val recorder = remember { ClipRecorder() }
    val pod = remember { PodSync(context).connected }
    var tick by remember { mutableIntStateOf(0) }
    val recorded = remember(tick) { voice.recorded }
    val total = voice.sentences.size
    var open by remember { mutableStateOf(false) }
    var n by remember { mutableStateOf<Int?>(null) }
    var recording by remember { mutableStateOf(false) }
    var clip by remember { mutableStateOf<ByteArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPlaying() {
        player?.release()
        player = null
    }

    fun record() {
        stopPlaying()
        message = null
        clip = null
        WakeService.appListening = true // Hey Poly lets go of the microphone
        recorder.start()
        recording = true
    }

    fun stop() {
        val wav = recorder.stop()
        WakeService.appListening = false
        recording = false
        message = when {
            ClipRecorder.peak(wav) < 0.02f -> "That was almost silent. Try again, a little closer to the phone."
            ClipRecorder.seconds(wav) < 1f -> "That was very short. Try again."
            else -> null
        }
        clip = wav.takeIf { message == null }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) record() else message = "Recording needs the microphone."
    }

    DisposableEffect(Unit) {
        onDispose {
            if (recording) { recorder.stop(); WakeService.appListening = false }
            stopPlaying()
        }
    }

    SettingsItem(
        icon = Icons.Default.RecordVoiceOver,
        title = "Your voice",
        subtitle = when {
            !pod -> "Connect your pod first. Then read sentences aloud to make Anthemos speak in your voice."
            recorded == 0 -> "Read about an hour of sentences aloud, at your own pace, to make Anthemos speak in your voice."
            else -> "%,d of %,d sentences recorded.".format(recorded, total)
        },
        onClick = { if (pod) { open = true; n = voice.next(); clip = null; message = null } }
    )

    if (!open) return
    val sentence = n
    AlertDialog(
        onDismissRequest = { if (!recording && !busy) { stopPlaying(); open = false } },
        title = { Text("Your voice") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(progress = { recorded / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("%,d of %,d".format(recorded, total), style = MaterialTheme.typography.bodySmall)
                if (sentence == null) {
                    Text("That's every sentence. Thank you.")
                } else {
                    if (recorded == 0 && clip == null && !recording) Text(
                        "Somewhere quiet, phone a hand's width away, your usual speaking voice. Read each sentence as you'd say it. Stop whenever you like; it picks up where you left off.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(voice.sentences[sentence], style = MaterialTheme.typography.titleMedium)
                    if (recording) Text("Recording… tap Stop when you've finished the sentence.", style = MaterialTheme.typography.bodySmall)
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            when {
                sentence == null -> Unit
                recording -> TextButton(onClick = { stop() }) { Text("Stop") }
                clip != null -> TextButton(enabled = !busy, onClick = {
                    val wav = clip ?: return@TextButton
                    stopPlaying()
                    busy = true
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { voice.keep(sentence, wav) } }
                        busy = false
                        result.onSuccess { clip = null; tick++; n = voice.next() }
                            .onFailure { message = "Couldn't save it to the pod: ${it.message ?: "try again"}" }
                    }
                }) { Text(if (busy) "Saving…" else "Keep") }
                else -> TextButton(onClick = {
                    if (WakeService.allowed(context)) record() else mic.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Record") }
            }
        },
        dismissButton = {
            Row {
                if (sentence != null && clip != null && !busy) {
                    TextButton(onClick = {
                        stopPlaying()
                        val file = File(context.cacheDir, "voice-clip.wav").apply { writeBytes(clip!!) }
                        player = MediaPlayer().apply { setDataSource(file.path); prepare(); start() }
                    }) { Text("Play") }
                    TextButton(onClick = { record() }) { Text("Again") }
                }
                if (sentence != null && clip == null && !recording) {
                    TextButton(onClick = { voice.skip(sentence); n = voice.next(); message = null }) { Text("Skip") }
                }
                if (!recording && !busy) TextButton(onClick = { stopPlaying(); open = false }) { Text("Close") }
            }
        }
    )
}
