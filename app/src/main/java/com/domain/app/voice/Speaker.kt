// app/src/main/java/com/domain/app/voice/Speaker.kt
package com.domain.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/** Voice back: short spoken confirmations through Android text-to-speech. */
class Speaker(context: Context) {
    private var ready = false
    private val pending = mutableListOf<Pair<String, (() -> Unit)?>>()
    private val done = mutableMapOf<String, () -> Unit>()

    /** No speech engine on the phone (GrapheneOS ships none): replies are shown, not spoken. */
    private var silent = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        silent = !ready
        val queued = pending.toList()
        pending.clear()
        if (ready) tts.language = Locale.getDefault()
        queued.forEach { (text, then) -> say(text, then) }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { done.remove(it)?.invoke() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { done.remove(it)?.invoke() }
            }
        })
    }

    /** Says [text]; [then] runs once it has been spoken (on a TTS thread), or at once if it can't be. */
    fun say(text: String, then: (() -> Unit)? = null) {
        if (silent) {
            then?.invoke()
            return
        }
        if (!ready) {
            pending += text to then
            return
        }
        val id = UUID.randomUUID().toString()
        then?.let { done[id] = it }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) done.remove(id)?.invoke()
    }

    /** False when the phone has no speech engine to talk with. */
    val canSpeak: Boolean get() = !silent

    fun shutdown() = tts.shutdown()
}
