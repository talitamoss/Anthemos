// app/src/main/java/com/domain/app/focus/WakeFocus.kt
package com.domain.app.focus

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.domain.app.voice.Focus
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.WakeService
import com.domain.app.voice.command

/** Hey Poly: starting Anthemos by voice (docs/anthemos-2.md, "Hey Poly"). */
object WakeFocus {

    fun focus() = Focus(
        "wake", "Starting Anthemos by saying Hey Poly", listOf(
            command(
                "wake.on", "Listens for Hey Poly, all the time, until turned off",
                listOf("start listening for hey poly", "turn on hey poly"),
                { s -> if (ON.matches(s.command)) VoiceRequest.Wake(s.transcript, true) else null }
            ) { on() },
            command(
                "wake.off", "Stops listening for Hey Poly",
                listOf("stop listening for hey poly", "turn off hey poly"),
                { s -> if (OFF.matches(s.command)) VoiceRequest.Wake(s.transcript, false) else null }
            ) {
                WakeService.set(context, false)
                reply("Stopped listening for Hey Poly")
            }
        )
    )

    private fun Turn.on() {
        WakeService.set(context, true)
        if (Settings.canDrawOverlays(context)) return reply("Listening for Hey Poly. Say it any time.")
        // Opening Speak from the background needs "Display over other apps".
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        reply("Listening for Hey Poly. Allow Display over other apps, so it can open when it hears you.")
    }

    private const val NAME = "(?:hey )?(?:poly|polly|pauly|paulie)"
    private val ON = Regex("^(?:start |turn on |switch on )?listen(?:ing)? for $NAME$|^(?:turn|switch) on $NAME$")
    private val OFF = Regex("^(?:stop|don't|do not) listen(?:ing)? for $NAME$|^(?:turn|switch) off $NAME$")
}
