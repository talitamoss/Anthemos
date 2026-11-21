// app/src/main/java/com/domain/app/focus/HomeFocus.kt
package com.domain.app.focus

import com.domain.app.home.HomeAssistant
import com.domain.app.voice.Focus
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home: the house, through Home Assistant's own Assist (docs/anthemos-2.md,
 * "Home"). Anthemos only decides the words are for the house; Assist knows
 * the devices and rooms, does it, and says what it did. Nothing is asked first.
 */
object HomeFocus {

    fun focus() = Focus(
        "home", "The house, through Home Assistant", listOf(
            command(
                "home.connect", "Says where Home Assistant is connected",
                listOf("connect home assistant"),
                { s -> if (CONNECT.matches(s.command)) VoiceRequest.Home(s.transcript) else null }
            ) {
                reply(
                    if (home.linked) "Home Assistant is connected. Change it in Settings."
                    else "Add Home Assistant in Settings: its address, and a token from your profile there, under Security."
                )
            },
            command(
                "home.do", "Lights, heating, locks, blinds and asking about them",
                listOf("turn off the lounge lights", "set the heating to 20", "is the back door locked", "lock the front door"),
                { s -> if (HomeAssistant.isHomeCommand(s.command)) VoiceRequest.Home(s.transcript) else null }
            ) { request -> say(request.transcript) }
        )
    )

    /** Hands the words to Assist and says its answer. */
    suspend fun Turn.say(words: String) {
        if (!home.linked) return reply("Connect Home Assistant in Settings first")
        val answer = try {
            withContext(Dispatchers.IO) { home.ask(words) }
        } catch (e: Exception) {
            return reply(e.message ?: "I can't reach Home Assistant")
        }
        reply(answer)
    }

    /** A question nothing else answered: Home Assistant's answer, if it has one. */
    suspend fun Turn.tryHome(words: String): Boolean {
        if (!home.linked) return false
        val answer = runCatching { withContext(Dispatchers.IO) { home.ask(words) } }.getOrNull() ?: return false
        if (!home.understood(answer)) return false
        reply(answer)
        return true
    }

    private val CONNECT = Regex("^(?:connect|link|set up|add)(?: to)? (?:my )?home ?assistant$")
}
