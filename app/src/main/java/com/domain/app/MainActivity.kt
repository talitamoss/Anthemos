// app/src/main/java/com/domain/app/MainActivity.kt
package com.domain.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.domain.app.kronk.KronkInbox
import com.domain.app.pod.PodInbox
import com.domain.app.ui.settings.sections.PodJoin
import com.domain.app.voice.WakeService
import com.domain.app.prompts.PromptInbox
import com.domain.app.ui.voice.VoiceScreen
import com.domain.app.ui.settings.navigation.SettingsNavigation
import com.domain.app.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Bumped whenever Anthemos is opened to listen (a headset or assistant
    // button), so Speak starts listening straight away.
    private var listenRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) noteListen(intent)
        KronkInbox.receive(intent)
        PodInbox.receive(intent)
        setContent {
            AppTheme {
                MainScreen(listenRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        noteListen(intent)
        KronkInbox.receive(intent)
        PodInbox.receive(intent)
    }

    override fun onResume() {
        super.onResume()
        // Hey Poly can only take the microphone while Anthemos is on screen; after that it keeps it.
        WakeService.refresh(this)
    }

    private fun noteListen(intent: Intent?) {
        if (intent?.action !in LISTEN_ACTIONS) return
        // Heard Hey Poly: open over the lock screen, and wake the screen.
        if (intent?.getBooleanExtra(WakeService.EXTRA_WAKE, false) == true && android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // Opened from a prompt: Speak asks it and listens for the answer.
        PromptInbox.receive(intent)
        // Counted for the whole app, not this screen: Speak remembers the last one it
        // answered, and that must stay below a new request after the screen is rebuilt.
        listenRequest = ++requests
    }

    companion object {
        private var requests = 0
        private val LISTEN_ACTIONS = setOf(
            Intent.ACTION_VOICE_COMMAND,
            Intent.ACTION_ASSIST,
            "com.anthemos.LISTEN"
        )
    }
}

/** One page (`VoiceScreen`), and Settings over it. */
@Composable
fun MainScreen(listenRequest: Int = 0) {
    var showSettings by remember { mutableStateOf(false) }

    // Opened to listen: back to the page.
    LaunchedEffect(listenRequest) {
        if (listenRequest > 0) showSettings = false
    }

    // Back from making a pod with Kronk, on whichever screen is up.
    PodJoin()

    if (showSettings) {
        SettingsNavigation(onNavigateBack = { showSettings = false })
    } else {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { paddingValues ->
            Box(Modifier.fillMaxSize().padding(paddingValues)) {
                VoiceScreen(
                    listenRequest = listenRequest,
                    onNavigateToSettings = { showSettings = true }
                )
            }
        }
    }
}
