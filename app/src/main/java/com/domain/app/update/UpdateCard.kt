// app/src/main/java/com/domain/app/update/UpdateCard.kt
package com.domain.app.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domain.app.BuildConfig
import kotlinx.coroutines.launch

/**
 * Updates, in Settings: the version on the phone, "Check for updates", and
 * one button to update when a newer build is out. Speak checks quietly on
 * open and marks the Settings button when one is.
 */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()
    val check = { scope.launch { Updater.check(asked = true) }; Unit }

    val (message, action) = when (val s = state) {
        UpdateState.Idle -> "Version ${BuildConfig.VERSION_NAME}" to ("Check for updates" to check)
        UpdateState.Checking -> "Checking…" to null
        UpdateState.UpToDate -> "Up to date: ${BuildConfig.VERSION_NAME}" to ("Check again" to check)
        is UpdateState.Available -> "Update ready: ${s.release.versionName}" to
            ("Update" to { scope.launch { Updater.install(context, s.release) }; Unit })
        is UpdateState.Downloading -> "Downloading ${s.release.versionName}… ${s.percent}%" to null
        UpdateState.Installing -> "Installing…" to null
        is UpdateState.NeedsPermission -> "Allow Anthemos to install updates, then come back and tap Update" to
            ("Allow" to {
                context.startActivity(Updater.permissionSettings(context))
                scope.launch { Updater.check() }
                Unit
            })
        is UpdateState.Failed -> s.message to ("Try again" to check)
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                action?.let { (label, onClick) -> Button(onClick = onClick) { Text(label) } }
            }
            (state as? UpdateState.Downloading)?.let {
                LinearProgressIndicator(
                    progress = { it.percent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}
