// app/src/main/java/com/domain/app/voice/WakeService.kt
package com.domain.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.domain.app.MainActivity
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.sqrt

/**
 * Hey Poly (docs/anthemos-2.md, "Hey Poly"): listens for one phrase, all
 * the time, and opens Speak listening when it hears it. Nothing is kept or
 * sent; audio is only checked for the phrase, on the phone.
 *
 * Kept light on battery:
 * - each fifth of a second of sound is measured first, and only something
 *   louder than the room's own noise goes to the recogniser;
 * - the recogniser knows only the phrase, nothing else;
 * - the microphone is let go of during calls, on battery saver, below 15%,
 *   and while Speak is listening itself.
 */
class WakeService : Service() {

    @Volatile private var running = false
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!on(this) || !allowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        if (thread?.isAlive != true) {
            running = true
            thread = Thread(::loop, "hey-poly").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        super.onDestroy()
    }

    // ── Listening ─────────────────────────────────────────────────────

    private fun loop() {
        val model = runCatching { SpeechModel.get(this) }.getOrNull() ?: return stopSelf()
        val recognizer = Recognizer(model, RATE.toFloat(), WakePhrase.GRAMMAR).apply { setWords(true) }
        val gate = SoundGate()
        try {
            while (running) {
                if (shouldPause()) {
                    Thread.sleep(PAUSE_CHECK_MS)
                    continue
                }
                if (listenUntilHeard(recognizer, gate)) heard()
            }
        } catch (_: InterruptedException) {
            // stopped
        } finally {
            recognizer.close()
        }
    }

    /**
     * Opens the microphone and listens until the phrase is heard (true), or
     * until it should let go (false). The microphone is closed either way.
     */
    @SuppressLint("MissingPermission") // allowed() is checked before the service runs
    private fun listenUntilHeard(recognizer: Recognizer, gate: SoundGate): Boolean {
        val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), CHUNK * 2 * 4)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Thread.sleep(PAUSE_CHECK_MS)
            return false
        }
        val chunk = ShortArray(CHUNK)
        var before: ShortArray? = null
        var checked = System.currentTimeMillis()
        try {
            record.startRecording()
            while (running) {
                val n = record.read(chunk, 0, CHUNK)
                if (n <= 0) continue
                val now = System.currentTimeMillis()
                if (now - checked > PAUSE_CHECK_MS) {
                    checked = now
                    if (shouldPause()) return false
                }
                when (gate.take(chunk, n)) {
                    SoundGate.Quiet -> before = chunk.copyOf(n)
                    SoundGate.Start -> {
                        // The start of "hey" may be in the chunk before the sound rose.
                        before?.let { recognizer.acceptWaveForm(it, it.size) }
                        if (feed(recognizer, chunk, n)) return true
                    }
                    SoundGate.Sound -> if (feed(recognizer, chunk, n)) return true
                    SoundGate.End -> {
                        val heard = WakePhrase.heard(words(recognizer.finalResult))
                        recognizer.reset()
                        if (heard) return true
                    }
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        return false
    }

    /**
     * Only a finished stretch of speech counts, never a partial guess: the
     * recogniser's partials often read "hey poly" mid-sentence and then
     * change their mind, which is how it used to open by itself.
     */
    private fun feed(recognizer: Recognizer, chunk: ShortArray, n: Int): Boolean {
        if (!recognizer.acceptWaveForm(chunk, n)) return false
        if (!WakePhrase.heard(words(recognizer.result))) return false
        recognizer.reset()
        return true
    }

    /** The words of a result, each with how sure the recogniser was. */
    private fun words(json: String): List<Pair<String, Double>> = runCatching {
        val list = JSONObject(json).optJSONArray("result") ?: return emptyList()
        (0 until list.length()).map { i -> list.getJSONObject(i).let { it.optString("word") to it.optDouble("conf", 0.0) } }
    }.getOrDefault(emptyList())

    /** A short tone, then Speak opens listening (over the lock screen if need be). */
    private fun heard() {
        appListening = true // until Speak's own turn takes over and ends
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).startTone(ToneGenerator.TONE_PROP_BEEP, 150) }
        val open = Intent(this, MainActivity::class.java)
            .setAction(LISTEN)
            .putExtra(EXTRA_WAKE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Opening an app from the background needs "Display over other apps".
        if (Settings.canDrawOverlays(this)) {
            startActivity(open)
        } else {
            val tap = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            getSystemService(NotificationManager::class.java).notify(
                HEARD_ID,
                NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Heard Hey Poly").setContentText("Tap to talk")
                    .setContentIntent(tap).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
            )
            appListening = false
        }
        // Give Speak a moment to take the microphone; if it never does, carry on.
        val waitUntil = System.currentTimeMillis() + TAKEOVER_MS
        Thread.sleep(1_000)
        while (running && appListening && System.currentTimeMillis() < waitUntil) Thread.sleep(500)
        if (System.currentTimeMillis() >= waitUntil) appListening = false
    }

    /** Let go of the microphone: Speak has it, a call, battery saver, or low battery. */
    private fun shouldPause(): Boolean {
        if (appListening) return true
        val audio = getSystemService(AudioManager::class.java)
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) return true
        if (getSystemService(PowerManager::class.java).isPowerSaveMode) return true
        val battery = getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 0 until LOW_BATTERY && !battery.isCharging
    }

    private fun notification(): Notification {
        channel(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for Hey Poly")
            .setContentText("Only for that phrase, on this phone")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        const val LISTEN = "com.anthemos.LISTEN"
        const val EXTRA_WAKE = "wake"
        private const val RATE = 16_000
        /** A fifth of a second of sound at a time: fewer wake-ups than small reads. */
        private const val CHUNK = RATE / 5
        private const val PAUSE_CHECK_MS = 5_000L
        private const val TAKEOVER_MS = 90_000L
        private const val LOW_BATTERY = 15
        private const val CHANNEL = "hey_poly"
        private const val NOTIFICATION_ID = 7302
        private const val HEARD_ID = 7303

        /** Speak is listening itself: Hey Poly lets go of the microphone. */
        @Volatile var appListening = false

        private fun prefs(context: Context) = context.getSharedPreferences("anthemos_wake", Context.MODE_PRIVATE)

        fun on(context: Context) = prefs(context).getBoolean("on", false)

        fun allowed(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Turns Hey Poly on or off, and starts or stops it to match. */
        fun set(context: Context, on: Boolean) {
            prefs(context).edit().putBoolean("on", on).apply()
            refresh(context)
        }

        /** Starts it if it's on: call while Anthemos is on screen (Android allows the microphone then). */
        fun refresh(context: Context) {
            val intent = Intent(context, WakeService::class.java)
            if (on(context) && allowed(context)) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
        }

        internal fun channel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Hey Poly", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Anthemos listens for Hey Poly"
                }
            )
        }
    }
}

/**
 * Tells sound from the room's own noise, cheaply: the loudness of each
 * chunk against a slowly learned floor. Sound continues until a short quiet
 * ([HANG] chunks) after the last loud one.
 */
class SoundGate {
    sealed interface Step
    object Quiet : Step
    object Start : Step
    object Sound : Step
    object End : Step

    private var floor = 200.0
    private var hang = 0

    fun take(chunk: ShortArray, n: Int): Step {
        var sum = 0.0
        for (i in 0 until n) sum += chunk[i].toDouble() * chunk[i]
        val level = sqrt(sum / n)
        val loud = level > maxOf(floor * 2.5, MIN_LEVEL)
        // The floor drifts down fast and up slowly, so speech doesn't raise it.
        floor = if (level < floor) floor * 0.8 + level * 0.2 else floor * 0.98 + level * 0.02
        val wasSound = hang > 0
        if (loud) {
            hang = HANG
            return if (wasSound) Sound else Start
        }
        if (!wasSound) return Quiet
        hang -= 1
        return if (hang == 0) End else Sound
    }

    companion object {
        private const val HANG = 6 // 1.2 seconds
        private const val MIN_LEVEL = 300.0
    }
}

/** After a restart Android won't let Anthemos open the microphone by itself: one tap does it. */
class WakeOnBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !WakeService.on(context)) return
        WakeService.channel(context)
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(NotificationManager::class.java).notify(
            7304,
            NotificationCompat.Builder(context, "hey_poly").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Tap to keep listening for Hey Poly")
                .setContentText("Android needs a tap after a restart")
                .setContentIntent(open).setAutoCancel(true).build()
        )
    }
}

/**
 * When Hey Poly counts as said (docs/anthemos-2.md, "Hey Poly"). The
 * recogniser only knows a few words, so it fits whatever it hears to the
 * nearest of them. To keep that from opening Speak:
 * - the words around the phrase are in its list too ([DECOYS]), so ordinary
 *   speech lands on them rather than on the phrase;
 * - the phrase must be all that was said, not part of a sentence;
 * - and the recogniser must be sure of both words ([MIN_CONF]).
 */
object WakePhrase {
    val PHRASES = listOf("hey poly", "hey polly", "hey pauly", "hey paulie")

    /** Sounds near the phrase, and common short things said, for speech to land on. */
    private val DECOYS = listOf(
        "hey", "hi", "hello", "okay", "oh", "yeah", "no", "the", "a", "and", "i", "it", "is", "so",
        "pay", "play", "hay", "holy", "holly", "police", "policy", "pole", "pulley", "party",
        "polo", "paul", "pearly", "hey paul", "hey babe", "hey buddy", "hey baby", "hey you"
    )

    val GRAMMAR = (PHRASES + DECOYS + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }

    /** How sure of each word the recogniser must be, from 0 to 1. */
    const val MIN_CONF = 0.8

    fun heard(words: List<Pair<String, Double>>): Boolean {
        if (words.size != 2) return false
        val said = words.joinToString(" ") { it.first }
        return said in PHRASES && words.all { it.second >= MIN_CONF }
    }
}
