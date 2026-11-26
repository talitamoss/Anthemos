// app/src/main/java/com/domain/app/voice/Assistant.kt
package com.domain.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.domain.app.MainActivity

/**
 * Anthemos as the phone's digital assistant (docs/anthemos-2.md, "Opened to
 * listen"). Chosen under Default apps, it is what holding the power button
 * (or the assist gesture) opens: Speak, listening, over the lock screen if
 * need be. Nothing is shown of its own; each session opens Speak and goes.
 */
class AssistantService : VoiceInteractionService() {
    // Held from the lock screen on phones that ask the assistant directly.
    override fun onLaunchVoiceAssistFromKeyguard() {
        startActivity(Assistant.listen(this))
    }
}

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}

private class AssistantSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Assistant.listen(context)
        if (Build.VERSION.SDK_INT >= 29) startAssistantActivity(intent) else context.startActivity(intent)
        hide()
    }
}

/**
 * Android lists an assistant only if it names a recogniser. Anthemos hears
 * through Vosk inside Speak (`Listener`), not for other apps, so this one
 * declines every request.
 */
class AssistantRecognition : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}

object Assistant {
    /** Opens Speak listening, over the lock screen, as Hey Poly does. */
    fun listen(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(WakeService.LISTEN)
            .putExtra(WakeService.EXTRA_WAKE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Whether Anthemos is the phone's digital assistant (Android 10+ can tell). */
    fun chosen(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(android.app.role.RoleManager::class.java)
                ?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
        } else false
}
