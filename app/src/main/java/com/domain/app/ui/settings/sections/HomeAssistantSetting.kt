// app/src/main/java/com/domain/app/ui/settings/sections/HomeAssistantSetting.kt
package com.domain.app.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.domain.app.home.HomeAssistant
import com.domain.app.ui.settings.SettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connecting Home Assistant (docs/anthemos-2.md, "Home"): its address, as
 * used at home or over the VPN, and a long-lived token made in its profile
 * page, under Security. Checked before it's kept.
 */
@Composable
fun HomeAssistantSetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val home = remember { HomeAssistant(context) }
    var connectedTo by remember { mutableStateOf(home.address.takeIf { home.linked }) }
    var open by remember { mutableStateOf(false) }

    SettingsItem(
        icon = Icons.Default.Home,
        title = "Home Assistant",
        subtitle = connectedTo?.let { "Connected: $it" } ?: "Not connected. Tap to add, then say \"turn on the lights\".",
        onClick = { open = true }
    )

    if (!open) return
    var address by remember { mutableStateOf(connectedTo.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Home Assistant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, singleLine = true,
                    label = { Text("Address") }, placeholder = { Text("192.168.1.10:8123") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, singleLine = true,
                    label = { Text("Long-lived token") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    message ?: "Make the token in Home Assistant: your profile, Security, Long-lived access tokens.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && address.isNotBlank() && token.isNotBlank(),
                onClick = {
                    busy = true
                    message = "Checking…"
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { home.link(address, token) } }
                        busy = false
                        result.onSuccess {
                            connectedTo = home.address
                            open = false
                        }.onFailure { message = it.message ?: "That didn't connect" }
                    }
                }
            ) { Text("Connect") }
        },
        dismissButton = {
            if (connectedTo != null) {
                TextButton(onClick = {
                    home.forget()
                    connectedTo = null
                    open = false
                }) { Text("Disconnect") }
            } else {
                TextButton(onClick = { open = false }) { Text("Cancel") }
            }
        }
    )
}
