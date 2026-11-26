// app/src/main/java/com/domain/app/ui/settings/sections/AssistantSetting.kt
package com.domain.app.ui.settings.sections

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.domain.app.ui.settings.SettingsItem
import com.domain.app.voice.Assistant

/**
 * Hold power to listen (docs/anthemos-2.md, "Opened to listen"): whether
 * Anthemos is the phone's digital assistant, and a tap to the phone's
 * choice of it. Android lets no app choose itself as the assistant.
 */
@Composable
fun AssistantSetting() {
    val context = LocalContext.current
    // Back from the phone's settings: ask again.
    var resumed by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumed++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val chosen = remember(resumed) { Assistant.chosen(context) }

    SettingsItem(
        icon = Icons.Default.PowerSettingsNew,
        title = "Hold power to listen",
        subtitle = if (chosen) {
            "On. Hold the power button to start Speak. If it opens the power menu, set the power button to Digital assistant in the phone's settings."
        } else {
            "Choose Anthemos as the Digital assistant app, then hold the power button to start Speak. Or add the Listen tile to Quick Settings."
        },
        onClick = {
            // The assistant's own page; a phone without one has it under Default apps.
            for (page in listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) {
                try {
                    context.startActivity(Intent(page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    break
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
    )
}
