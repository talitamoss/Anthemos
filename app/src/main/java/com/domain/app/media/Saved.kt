// app/src/main/java/com/domain/app/media/Saved.kt
package com.domain.app.media

import android.content.Context
import com.domain.app.pod.PodSync
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Recordings and sketches (docs/anthemos-2.md, "Record and Sketch"): each is
 * saved on the phone straight away, in Anthemos's own files, and a sealed
 * copy goes to the pod when one is linked. Any that didn't reach the pod go
 * the next time [sync] runs.
 */
class Saved(private val context: Context, private val kind: String, private val ext: String) {
    private val dir = File(context.filesDir, kind).apply { mkdirs() }
    private val prefs = context.getSharedPreferences("anthemos_saved_$kind", Context.MODE_PRIVATE)

    /** Newest first. */
    fun list(): List<File> = dir.listFiles().orEmpty().filter { it.extension == ext }.sortedByDescending { it.name }

    /** A new file, named for now, for something to be written into. */
    fun newFile(): File = File(dir, NAME.format(LocalDateTime.now()) + ".$ext")

    fun save(bytes: ByteArray): File = newFile().apply { writeBytes(bytes) }

    /** Seals into the pod any not there yet; does nothing without a pod. Network: call off the main thread. */
    fun sync() {
        val (client, seal) = PodSync(context).open() ?: return
        for (file in list().filter { it.name !in sent() }) {
            client.put(podPath(seal.name("$kind:${file.name}")), seal.seal(file.readBytes()))
            prefs.edit().putStringSet(SENT, sent() + file.name).apply()
        }
    }

    /** Deletes it here and, if a copy went there, from the pod. Network: call off the main thread. */
    fun delete(file: File) {
        if (file.name in sent()) {
            PodSync(context).open()?.let { (client, seal) -> client.delete(podPath(seal.name("$kind:${file.name}"))) }
            prefs.edit().putStringSet(SENT, sent() - file.name).apply()
        }
        file.delete()
    }

    private fun sent() = prefs.getStringSet(SENT, emptySet())!!
    private fun podPath(name: String) = "anthemos/$kind/v1/$name"

    companion object {
        private const val SENT = "sent"
        private val NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun recordings(context: Context) = Saved(context, "recordings", "m4a")
        fun sketches(context: Context) = Saved(context, "sketches", "png")
    }
}
