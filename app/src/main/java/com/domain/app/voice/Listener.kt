// app/src/main/java/com/domain/app/voice/Listener.kt
package com.domain.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.Executors

/**
 * Ears: speech to text, always on the phone (docs/anthemos-2.md).
 *
 * Uses Vosk with a small English model shipped inside the app, so it works
 * without Google's speech service (GrapheneOS has none) and nothing leaves
 * the phone. The model is copied out of the APK on first use.
 *
 * Must be used from the main thread; callbacks arrive on the main thread.
 */
class Listener(private val context: Context) {

    interface Callbacks {
        fun onListening()
        fun onPartial(text: String)
        fun onHeard(text: String)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var loading = false
    private var loadFailed = false
    private var waiting: Callbacks? = null
    private var session: Session? = null

    /** Copies and loads the model in the background, so the first tap is quick. */
    fun prepare() {
        if (model != null || loading) return
        loading = true
        loadFailed = false
        worker.execute {
            val loaded = runCatching { Model(unpack().absolutePath) }
            main.post {
                loading = false
                loaded.onSuccess { model = it }.onFailure { loadFailed = true }
                waiting?.let { callbacks ->
                    waiting = null
                    if (model != null) start(callbacks) else callbacks.onError("The speech model couldn't load")
                }
            }
        }
    }

    fun start(callbacks: Callbacks) {
        cancel()
        val m = model
        if (m == null) {
            waiting = callbacks
            if (loadFailed || !loading) prepare()
            return
        }
        session = try {
            Session(m, callbacks).also { it.begin() }
        } catch (e: Exception) {
            callbacks.onError("The microphone isn't available")
            null
        }
    }

    /** Stops listening and makes sense of what was said so far. */
    fun finish() {
        waiting = null
        session?.finish()
    }

    /** Stops listening and drops what was said. */
    fun cancel() {
        waiting = null
        session?.release()
        session = null
    }

    fun shutdown() {
        cancel()
        worker.shutdown()
        model?.close()
        model = null
    }

    /**
     * One turn of listening. Vosk reports each stretch of speech when it hears
     * a short pause; the turn goes on through those, like talking to a
     * person, and ends after a longer quiet ([QUIET_MS]) or a tap on Stop.
     * Stale callbacks from an old turn are ignored.
     */
    private inner class Session(model: Model, private val callbacks: Callbacks) : RecognitionListener {
        private val recognizer = Recognizer(model, SAMPLE_RATE)
        private val service = SpeechService(recognizer, SAMPLE_RATE)
        private var done = false
        private val said = mutableListOf<String>()
        private val quiet = Runnable { finish() }
        // Nothing said at all: don't hold the microphone open for a minute.
        private val silent = Runnable { finish() }

        fun begin() {
            service.startListening(this, TIMEOUT_MS)
            main.postDelayed(silent, SILENT_MS)
            callbacks.onListening()
        }

        fun finish() {
            main.removeCallbacks(quiet)
            main.removeCallbacks(silent)
            if (!done) service.stop() // onFinalResult follows
        }

        fun release() {
            done = true
            main.removeCallbacks(quiet)
            main.removeCallbacks(silent)
            service.cancel()
            service.shutdown()
            recognizer.close()
            if (session === this) session = null
        }

        override fun onPartialResult(hypothesis: String?) {
            if (done) return
            val partial = text(hypothesis, "partial") ?: return
            // Talking again: not the end yet.
            main.removeCallbacks(silent)
            main.removeCallbacks(quiet)
            callbacks.onPartial((said + partial).joinToString(" "))
        }

        // Vosk calls this at each short pause: keep it, and wait to see if more comes.
        override fun onResult(hypothesis: String?) {
            if (done) return
            text(hypothesis, "text")?.let { said += it }
            if (said.isNotEmpty()) {
                callbacks.onPartial(said.joinToString(" "))
                main.removeCallbacks(quiet)
                main.postDelayed(quiet, QUIET_MS)
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            if (done) return
            text(hypothesis, "text")?.let { said += it }
            if (said.isNotEmpty()) heard(said.joinToString(" ")) else fail("I didn't catch that")
        }

        override fun onError(exception: Exception?) {
            if (!done) fail("The microphone isn't available")
        }

        override fun onTimeout() {
            if (done) return
            if (said.isNotEmpty()) heard(said.joinToString(" ")) else fail("I didn't catch that")
        }

        private fun heard(text: String) {
            release()
            callbacks.onHeard(text)
        }

        private fun fail(message: String) {
            release()
            callbacks.onError(message)
        }
    }

    /** Copies the model out of the APK once; a marker says the copy finished. */
    private fun unpack(): File {
        val dir = File(context.filesDir, MODEL_NAME)
        val marker = File(dir, ".ready")
        if (marker.exists()) return dir
        dir.deleteRecursively()
        copyAsset(MODEL_ASSET, dir)
        marker.writeText(MODEL_NAME)
        return dir
    }

    private fun copyAsset(path: String, to: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            context.assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
        } else {
            to.mkdirs()
            children.forEach { copyAsset("$path/$it", File(to, it)) }
        }
    }

    private fun text(json: String?, key: String): String? =
        json?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val SAMPLE_RATE = 16000f
        /** The longest one turn can run. */
        private const val TIMEOUT_MS = 60_000
        /** Quiet after speech that ends the turn, on top of Vosk's own short pause. */
        private const val QUIET_MS = 1_800L
        private const val SILENT_MS = 8_000L
        /** Fetched at build time by the fetchVoskModel task in app/build.gradle.kts. */
        private const val MODEL_ASSET = "model-en-us"
        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    }
}
