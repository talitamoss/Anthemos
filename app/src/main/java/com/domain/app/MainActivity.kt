// app/src/main/java/com/domain/app/MainActivity.kt
package com.domain.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.domain.app.kronk.KronkInbox
import com.domain.app.voice.WakeService
import com.domain.app.prompts.PromptInbox
import com.domain.app.ui.components.navigation.AnthemosBottomBar
import com.domain.app.ui.dashboard.DashboardScreen
import com.domain.app.ui.voice.VoiceScreen
import com.domain.app.ui.reflect.ReflectScreen
import com.domain.app.ui.settings.navigation.SettingsNavigation
import com.domain.app.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Bumped whenever Anthemos is opened to listen (a headset or assistant
    // button), so Speak starts listening straight away.
    private var listenRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) noteListen(intent)
        KronkInbox.receive(intent)
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
        listenRequest += 1
    }

    companion object {
        private val LISTEN_ACTIONS = setOf(
            Intent.ACTION_VOICE_COMMAND,
            Intent.ACTION_ASSIST,
            "com.anthemos.LISTEN"
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(listenRequest: Int = 0) {
    // Pager state for swipe navigation (3 screens: Collect, Speak, Reflect)
    // Start on Speak (page 1) as home screen
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }

    // Opened to listen: show Speak.
    LaunchedEffect(listenRequest) {
        if (listenRequest > 0) {
            showSettings = false
            pagerState.scrollToPage(1)
        }
    }

    if (showSettings) {
        // Settings navigation remains the same
        SettingsNavigation(
            onNavigateBack = {
                showSettings = false
            }
        )
    } else {
        Scaffold(
            bottomBar = {
                AnthemosBottomBar(
                    currentPage = pagerState.currentPage,
                    onPageSelected = { page ->
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(page)
                        }
                    }
                )
            }
        ) { paddingValues ->
            // HorizontalPager for swipe navigation
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) { page ->
                when (page) {
                    0 -> DashboardScreen(
                        onNavigateToPlugin = { plugin ->
                            // TODO: Navigate to plugin detail screen
                        },
                        onNavigateToSettings = {
                            showSettings = true
                        }
                    )
                    1 -> VoiceScreen(
                        listenRequest = listenRequest,
                        onNavigateToSettings = {
                            showSettings = true
                        }
                    )
                    2 -> ReflectScreen(
                        onNavigateToSettings = {
                            showSettings = true
                        }
                    )
                }
            }
        }
    }
}
