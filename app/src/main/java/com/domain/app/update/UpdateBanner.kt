// app/src/main/java/com/domain/app/update/UpdateBanner.kt
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Shows when a newer build is out, with one button to update. Checks on open. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()

    LaunchedEffect(Unit) { Updater.check() }

    val (message, action) = when (val s = state) {
        UpdateState.Idle -> return
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
        is UpdateState.Failed -> s.message to ("Try again" to { scope.launch { Updater.check() }; Unit })
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
