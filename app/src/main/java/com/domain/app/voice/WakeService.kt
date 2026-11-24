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
 *
 * And it says what it's doing ([status], [lastHeard], shown in Settings), and
 * doesn't sit there looking on while hearing nothing: Android's silence
 * ([SilenceWatch]), a Speak turn that never ended, or a crash each end in
 * a notification or a restart rather than a quiet stop.
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
        if (status?.startsWith("Stopped") != true) status = "Stopped. Open Anthemos to start it again."
        running = false
        thread?.interrupt()
        super.onDestroy()
    }

    // ── Listening ─────────────────────────────────────────────────────

    private fun loop() {
        status = "Starting"
        val model = runCatching { SpeechModel.get(this) }.getOrNull()
        if (model == null) {
            status = "Stopped: the speech model wouldn't load"
            return stopSelf()
        }
        val recognizer = Recognizer(model, RATE.toFloat(), WakePhrase.GRAMMAR).apply { setWords(true) }
        val gate = SoundGate()
        try {
            while (running) {
                val pause = pauseReason()
                if (pause != null) {
                    status = pause
                    Thread.sleep(PAUSE_CHECK_MS)
                    continue
                }
                when (listenUntilHeard(recognizer, gate)) {
                    Outcome.Heard -> heard()
                    Outcome.Silenced -> {
                        status = "Stopped: Android gave it only silence"
                        askForTap(this, "Tap to keep listening for Hey Poly", "Android stopped the microphone. If its switch is off, turn it on.")
                        running = false
                        stopSelf()
                    }
                    Outcome.LetGo -> {}
                }
            }
        } catch (_: InterruptedException) {
            // stopped
        } catch (e: Throwable) {
            // Not a quiet stop behind a notification that says it's listening.
            status = "Stopped: ${e.javaClass.simpleName}. Open Anthemos to start it again."
            stopSelf()
        } finally {
            recognizer.close()
        }
    }

    private enum class Outcome { Heard, LetGo, Silenced }

    /**
     * Opens the microphone and listens until the phrase is heard, until it
     * should let go, or until Android has given only silence for a while.
     * The microphone is closed either way.
     */
    @SuppressLint("MissingPermission") // allowed() is checked before the service runs
    private fun listenUntilHeard(recognizer: Recognizer, gate: SoundGate): Outcome {
        val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), CHUNK * 2 * 4)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            status = "Waiting: another app has the microphone"
            Thread.sleep(PAUSE_CHECK_MS)
            return Outcome.LetGo
        }
        val chunk = ShortArray(CHUNK)
        var before: ShortArray? = null
        var checked = System.currentTimeMillis()
        val silence = SilenceWatch()
        try {
            record.startRecording()
            status = "Listening for \"Hey Poly\""
            while (running) {
                val n = record.read(chunk, 0, CHUNK)
                if (n <= 0) continue
                val now = System.currentTimeMillis()
                if (silence.take(chunk, n, now)) return Outcome.Silenced
                if (now - checked > PAUSE_CHECK_MS) {
                    checked = now
                    if (pauseReason() != null) return Outcome.LetGo
                }
                when (gate.take(chunk, n)) {
                    SoundGate.Quiet -> before = chunk.copyOf(n)
                    SoundGate.Start -> {
                        // The start of "hey" may be in the chunk before the sound rose.
                        before?.let { recognizer.acceptWaveForm(it, it.size) }
                        if (feed(recognizer, chunk, n)) return Outcome.Heard
                    }
                    SoundGate.Sound -> if (feed(recognizer, chunk, n)) return Outcome.Heard
                    SoundGate.End -> {
                        val heard = WakePhrase.heard(note(words(recognizer.finalResult)))
                        recognizer.reset()
                        if (heard) return Outcome.Heard
                    }
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        return Outcome.LetGo
    }

    /**
     * Only a finished stretch of speech counts, never a partial guess: the
     * recogniser's partials often read "hey poly" mid-sentence and then
     * change their mind, which is how it used to open by itself.
     */
    private fun feed(recognizer: Recognizer, chunk: ShortArray, n: Int): Boolean {
        if (!recognizer.acceptWaveForm(chunk, n)) return false
        if (!WakePhrase.heard(note(words(recognizer.result)))) return false
        recognizer.reset()
        return true
    }

    /** The words of a result, each with how sure the recogniser was. */
    private fun words(json: String): List<Pair<String, Double>> = runCatching {
        val list = JSONObject(json).optJSONArray("result") ?: return emptyList()
        (0 until list.length()).map { i -> list.getJSONObject(i).let { it.optString("word") to it.optDouble("conf", 0.0) } }
    }.getOrDefault(emptyList())

    /** Keeps what was last heard, and how sure, for Settings: shows whether it hears at all. */
    private fun note(words: List<Pair<String, Double>>): List<Pair<String, Double>> {
        val said = words.filter { it.first != "[unk]" }
        if (said.isNotEmpty()) {
            lastHeard = said.joinToString(" ") { "${it.first} ${(it.second * 100).toInt()}%" }
        }
        return words
    }

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

    /** Why to let go of the microphone (Speak has it, a call, battery saver, low battery), or null. */
    private fun pauseReason(): String? {
        if (appListening) return "Paused: Speak is listening"
        val audio = getSystemService(AudioManager::class.java)
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) return "Paused: a call"
        if (getSystemService(PowerManager::class.java).isPowerSaveMode) return "Paused: battery saver is on"
        val battery = getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0 until LOW_BATTERY && !battery.isCharging) "Paused: battery below $LOW_BATTERY%" else null
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
        private const val TAP_ID = 7304
        /** Longer than any Speak turn (60 s) or the wait for one ([TAKEOVER_MS]). */
        private const val SPEAK_LIMIT_MS = 120_000L

        @Volatile private var listeningSince = 0L

        /**
         * Speak is listening itself: Hey Poly lets go of the microphone. A
         * turn that never said it ended (Anthemos closed mid-sentence) stops
         * counting after [SPEAK_LIMIT_MS], so Hey Poly never waits for good.
         */
        var appListening: Boolean
            get() = listeningSince != 0L && System.currentTimeMillis() - listeningSince < SPEAK_LIMIT_MS
            set(value) { listeningSince = if (value) System.currentTimeMillis() else 0L }

        /** What it's doing now, for Settings. */
        @Volatile var status: String? = null
            private set

        /** The last words it heard and how sure it was of each, for Settings. */
        @Volatile var lastHeard: String? = null
            private set

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
            if (on(context) && allowed(context)) {
                context.getSystemService(NotificationManager::class.java).cancel(TAP_ID)
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
                status = null
            }
        }

        /** Android won't let Anthemos take the microphone by itself: a notification asks for one tap. */
        internal fun askForTap(context: Context, title: String, text: String) {
            channel(context)
            val open = PendingIntent.getActivity(
                context, 3, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            context.getSystemService(NotificationManager::class.java).notify(
                TAP_ID,
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle(title).setContentText(text)
                    .setContentIntent(open).setAutoCancel(true).build()
            )
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
        WakeService.askForTap(context, "Tap to keep listening for Hey Poly", "Android needs a tap after a restart")
    }
}

/**
 * Notices when Android hands over nothing but zeros. A real microphone is
 * never exactly silent; zeros mean Android has muted Anthemos (it was
 * started again from the background, or the phone's microphone switch is
 * off), and listening on would hear nothing while looking on.
 */
class SilenceWatch(private val limitMs: Long = LIMIT_MS) {
    private var since = -1L

    /** True once only zeros have come for [limitMs]. */
    fun take(chunk: ShortArray, n: Int, now: Long): Boolean {
        var zeros = true
        for (i in 0 until n) if (chunk[i].toInt() != 0) { zeros = false; break }
        if (!zeros) {
            since = -1L
            return false
        }
        if (since < 0) since = now
        return now - since >= limitMs
    }

    companion object {
        const val LIMIT_MS = 20_000L
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
