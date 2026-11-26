// app/src/main/java/com/domain/app/voice/SpeechModel.kt
package com.domain.app.voice

import android.content.Context
import org.vosk.Model
import java.io.File

/**
 * The Vosk speech model, loaded once and shared by Speak's [Listener] and
 * Hey Poly ([WakeService]), so the phone holds one copy in memory.
 */
object SpeechModel {
    @Volatile
    var loaded: Model? = null
        private set

    /** Loads the model if needed. Slow the first time: call off the main thread. */
    @Synchronized
    fun get(context: Context): Model = loaded ?: Model(unpack(context.applicationContext).absolutePath).also { loaded = it }

    /** Copies the model out of the APK once; a marker says the copy finished. */
    private fun unpack(context: Context): File {
        val dir = File(context.filesDir, MODEL_NAME)
        val marker = File(dir, ".ready")
        if (marker.exists()) return dir
        dir.deleteRecursively()
        copyAsset(context, MODEL_ASSET, dir)
        marker.writeText(MODEL_NAME)
        return dir
    }

    private fun copyAsset(context: Context, path: String, to: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            context.assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
        } else {
            to.mkdirs()
            children.forEach { copyAsset(context, "$path/$it", File(to, it)) }
        }
    }

    /** Fetched at build time by the fetchVoskModel task in app/build.gradle.kts. */
    private const val MODEL_ASSET = "model-en-us"
    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
}
