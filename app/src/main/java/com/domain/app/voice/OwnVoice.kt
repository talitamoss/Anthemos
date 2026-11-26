// app/src/main/java/com/domain/app/voice/OwnVoice.kt
package com.domain.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.domain.app.pod.PodClient
import com.domain.app.pod.PodSync
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Your own voice for Anthemos (docs/anthemos-2.md, "Your voice"): you read
 * the sentences aloud one by one, and each recording is sealed into your
 * pod under `anthemos/voice/v1/`, ready to be made into a voice.
 */
class OwnVoice(private val context: Context) {
    private val prefs = context.getSharedPreferences("anthemos_own_voice", Context.MODE_PRIVATE)

    /** Out-of-copyright sentences chosen to cover the sounds of English (CMU ARCTIC, set A). */
    val sentences: List<String> by lazy { context.assets.open("voice/sentences.txt").bufferedReader().readLines().filter { it.isNotBlank() } }

    val recorded: Int get() = done().size

    /** The first sentence not yet recorded or skipped, or null when all are. */
    fun next(): Int? {
        val done = done() + skipped()
        return sentences.indices.firstOrNull { it !in done }
    }

    /** Seals the recording of sentence [n] into the pod. */
    fun keep(n: Int, wav: ByteArray) {
        val (client, seal) = PodSync(context).open() ?: throw PodClient.PodError("Connect a pod first")
        client.put(DIR + seal.name("voice:clip:$n"), seal.seal(wav))
        prefs.edit().putStringSet(DONE, done().map { "$it" }.toSet() + "$n").apply()
    }

    fun skip(n: Int) = prefs.edit().putStringSet(SKIPPED, skipped().map { "$it" }.toSet() + "$n").apply()

    private fun done() = prefs.getStringSet(DONE, emptySet())!!.map { it.toInt() }.toSet()
    private fun skipped() = prefs.getStringSet(SKIPPED, emptySet())!!.map { it.toInt() }.toSet()

    companion object {
        const val DIR = "anthemos/voice/v1/"
        private const val DONE = "done"
        private const val SKIPPED = "skipped"
    }
}

/** One recording at a time, mono 16-bit at 22,050 a second, the rate voices are made at. */
class ClipRecorder {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @SuppressLint("MissingPermission") // asked for before recording starts
    fun start() {
        pcm.reset()
        val size = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        running = true
        record.startRecording()
        thread = Thread {
            val buffer = ByteArray(size)
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0) synchronized(pcm) { pcm.write(buffer, 0, n) }
            }
            record.stop()
            record.release()
        }.apply { start() }
    }

    /** Stops, and gives the recording as a WAV file. */
    fun stop(): ByteArray {
        running = false
        thread?.join()
        thread = null
        return synchronized(pcm) { wav(pcm.toByteArray()) }
    }

    companion object {
        const val RATE = 22_050

        /** How loud the loudest moment was, 0 to 1: near 0 means the microphone heard nothing. */
        fun peak(wav: ByteArray): Float {
            val samples = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var max = 0
            while (samples.hasRemaining()) max = maxOf(max, abs(samples.get().toInt()))
            return max / 32768f
        }

        fun seconds(wav: ByteArray) = (wav.size - 44) / 2f / RATE

        private fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
}
