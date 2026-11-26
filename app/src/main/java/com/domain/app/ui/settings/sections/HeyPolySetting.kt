// app/src/main/java/com/domain/app/ui/settings/sections/HeyPolySetting.kt
package com.domain.app.ui.settings.sections

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.domain.app.ui.settings.SettingsItemWithSwitch
import com.domain.app.voice.WakeService

/**
 * The Hey Poly switch: listening for one phrase, all the time
 * (docs/anthemos-2.md, "Hey Poly"). Turning it on asks for the microphone,
 * then for "Display over other apps", which lets it open Speak when it hears you.
 */
@Composable
fun HeyPolySetting() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(WakeService.on(context)) }

    fun set(value: Boolean) {
        WakeService.set(context, value)
        on = value
        if (value && !Settings.canDrawOverlays(context)) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) set(true)
    }

    SettingsItemWithSwitch(
        icon = Icons.Default.Mic,
        title = "Hey Poly",
        subtitle = when {
            !on -> "Off. Turn on to start Speak by saying \"Hey Poly\"."
            !Settings.canDrawOverlays(context) -> "On. Allow Display over other apps so it can open Speak."
            else -> listOfNotNull(
                WakeService.status ?: "Open Anthemos to start it",
                WakeService.lastHeard?.let { "Last heard: $it" }
            ).joinToString("\n")
        },
        checked = on,
        onCheckedChange = { value ->
            if (value && !WakeService.allowed(context)) mic.launch(Manifest.permission.RECORD_AUDIO) else set(value)
        }
    )
}
