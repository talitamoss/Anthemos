// app/src/main/java/com/domain/app/voice/Listener.kt
package com.domain.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Ears: speech to text, on the phone where it can be (docs/anthemos-2.md).
 * On Android 12+ with on-device recognition (Pixel 8), nothing leaves the
 * phone; otherwise the system recogniser is asked to prefer offline.
 *
 * Must be used from the main thread.
 */
class Listener(private val context: Context) {

    interface Callbacks {
        fun onListening()
        fun onPartial(text: String)
        fun onHeard(text: String)
        fun onError(message: String)
        fun onLevel(rms: Float) = Unit
    }

    private var recognizer: SpeechRecognizer? = null

    val onDevice: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun start(callbacks: Callbacks) {
        stop()
        val r = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = callbacks.onListening()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = callbacks.onLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let(callbacks::onPartial)
            }

            override fun onResults(results: Bundle?) {
                val text = first(results)
                if (text.isNullOrBlank()) callbacks.onError("I didn't catch that") else callbacks.onHeard(text)
            }

            override fun onError(error: Int) = callbacks.onError(describe(error))
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.run {
            cancel()
            destroy()
        }
        recognizer = null
    }

    private fun first(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need the microphone to listen"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs the network on this phone"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Still busy, try again"
        SpeechRecognizer.ERROR_AUDIO -> "The microphone isn't available"
        else -> "Listening stopped ($error)"
    }
}
