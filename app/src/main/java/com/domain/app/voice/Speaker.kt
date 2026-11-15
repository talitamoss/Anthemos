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

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.getDefault()
            pending.toList().forEach { (text, then) -> say(text, then) }
        }
        pending.clear()
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

    /** Says [text]; [then] runs once it has been spoken (on a TTS thread). */
    fun say(text: String, then: (() -> Unit)? = null) {
        if (!ready) {
            pending += text to then
            return
        }
        val id = UUID.randomUUID().toString()
        then?.let { done[id] = it }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun shutdown() = tts.shutdown()
}
