// app/src/main/java/com/domain/app/prompts/Prompter.kt
package com.domain.app.prompts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.domain.app.MainActivity
import com.domain.app.core.data.DataRepository
import com.domain.app.calendar.Event
import com.domain.app.sense.PhoneAnswers
import com.domain.app.sense.PhoneSense
import com.domain.app.voice.Entry
import com.domain.app.voice.Item
import java.time.format.DateTimeFormatter
import java.util.Locale
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** What has been asked and answered, kept on the phone. */
class PromptLog(context: Context) : PromptHistory {
    private val prefs = context.getSharedPreferences("anthemos_prompts", Context.MODE_PRIVATE)

    override fun asks(id: String): List<Ask> =
        prefs.getString("asks_$id", "").orEmpty().split(",").filter { it.isNotBlank() }.mapNotNull { part ->
            val (time, answered) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            Ask(Instant.ofEpochMilli(time.toLongOrNull() ?: return@mapNotNull null), answered == "1")
        }

    override fun stopped(id: String) = prefs.getBoolean("stopped_$id", false)

    fun asked(id: String, at: Instant = Instant.now()) = write(id, asks(id) + Ask(at, false))

    fun answered(id: String) {
        val asks = asks(id)
        if (asks.isEmpty()) return
        write(id, asks.dropLast(1) + asks.last().copy(answered = true))
    }

    fun stop(id: String) = prefs.edit().putBoolean("stopped_$id", true).apply()

    private fun write(id: String, asks: List<Ask>) {
        val kept = asks.takeLast(12).joinToString(",") { "${it.time.toEpochMilli()}:${if (it.answered) 1 else 0}" }
        prefs.edit().putString("asks_$id", kept).apply()
    }
}

/** A prompt tapped in a notification, waiting for Speak to pick it up. */
object PromptInbox {
    val waiting = MutableStateFlow<Prompt?>(null)
    /** Words to act on as if said: a reminder like "Call Dad". */
    val say = MutableStateFlow<String?>(null)

    fun take(): Prompt? = waiting.value.also { waiting.value = null }
    fun takeSay(): String? = say.value.also { say.value = null }

    /** Reads a tapped notification's prompt or words into the inbox. */
    fun receive(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SAY)?.let { say.value = it }
        val id = intent?.getStringExtra(EXTRA_ID) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val minutes = intent.getIntExtra(EXTRA_MINUTES, -1).takeIf { it >= 0 }
        waiting.value = Prompt(id, text, intent.getStringExtra(EXTRA_CONTEXT), minutes = minutes)
    }

    internal const val EXTRA_ID = "prompt_id"
    internal const val EXTRA_TEXT = "prompt_text"
    internal const val EXTRA_CONTEXT = "prompt_context"
    internal const val EXTRA_MINUTES = "prompt_minutes"
    const val EXTRA_SAY = "say"
}

/**
 * Checks every fifteen minutes whether a prompt is due, and shows it as a
 * notification. A Speak tap opens Anthemos listening for the answer.
 * Everything happens on the phone; nothing needs Google services.
 */
@HiltWorker
class PromptWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dataRepository: DataRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val log = PromptLog(applicationContext)
        val now = ZonedDateTime.now()

        inputData.getString(KEY_CALLED)?.let { name ->
            // Still on the call: look again in five minutes.
            val audio = applicationContext.getSystemService(AudioManager::class.java)
            if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
                afterCall(applicationContext, name, delayMinutes = 5)
                return Result.success()
            }
            val prompt = Prompt("call", "Anything to remember from the call with $name?", "call")
            if (!PromptRules.quiet(now.toLocalTime()) && !log.stopped("call")) show(applicationContext, prompt, log)
            return Result.success()
        }

        val since = now.truncatedTo(ChronoUnit.DAYS).minusDays(14).toInstant()
        val entries = dataRepository.getPluginData(Entry.PLUGIN_ID).first()
            .mapNotNull(Entry::from)
            .filter { it.time >= since }
        val events = dataRepository.getPluginData(Event.PLUGIN_ID).first().mapNotNull(Event::from)
        val sense = PhoneSense(applicationContext)
        keepScreenTime(sense, now)

        var prompt = PromptRules.due(now, entries, log, events) ?: return Result.success()
        // The phone saw when it was put down and picked up: say so, and keep it if the answer doesn't.
        if (prompt.id == "sleep") sense.lastNight(now)?.let { guess ->
            val bed = TIME.format(guess.bed.atZone(now.zone)).lowercase()
            val up = TIME.format(guess.up.atZone(now.zone)).lowercase()
            prompt = prompt.copy(text = "Phone down at $bed, up at $up. How did you sleep?", minutes = guess.minutes)
        }
        show(applicationContext, prompt, log)
        return Result.success()
    }

    /** Once a day, yesterday's screen time becomes an entry, so it can be asked about later. */
    private suspend fun keepScreenTime(sense: PhoneSense, now: ZonedDateTime) {
        if (!sense.allowed() || now.hour < 4) return
        val prefs = applicationContext.getSharedPreferences("anthemos_prompts", Context.MODE_PRIVATE)
        val day = now.toLocalDate().minusDays(1)
        if (prefs.getString("screen_kept", null) == day.toString()) return
        val from = day.atStartOfDay(now.zone).toInstant()
        val until = day.plusDays(1).atStartOfDay(now.zone).toInstant()
        val minutes = sense.screenMinutes(from, until)
        val top = sense.topApps(from, until)
        val entry = Entry(
            id = "screen-$day",
            time = until.minusSeconds(60),
            words = PhoneAnswers.screenTime("on $day", minutes, top),
            items = listOf(Item("screen", "screen time", minutes = minutes)),
            reader = "phone",
            source = "phone"
        )
        dataRepository.saveDataPoint(entry.toDataPoint())
        prefs.edit().putString("screen_kept", day.toString()).apply()
    }

    companion object {
        private const val WORK = "anthemos-prompts"
        private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        private const val KEY_CALLED = "called"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PromptWorker>(15, TimeUnit.MINUTES).build()
            )
        }

        /** Asks about a call once it has ended. */
        fun afterCall(context: Context, name: String, delayMinutes: Long = 10) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK-call",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PromptWorker>()
                    .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    .setInputData(workDataOf(KEY_CALLED to name))
                    .build()
            )
        }
    }
}

private const val CHANNEL = "prompts"
private const val NOTIFICATION_ID = 7001
private const val ACTION_STOP = "com.anthemos.PROMPT_STOP"

internal fun show(context: Context, prompt: Prompt, log: PromptLog) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return

    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Prompts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Anthemos asking about your day"
            }
        )
    }
    val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    val speak = Intent(context, MainActivity::class.java)
        .setAction("com.anthemos.LISTEN")
        .putExtra(PromptInbox.EXTRA_ID, prompt.id)
        .putExtra(PromptInbox.EXTRA_TEXT, prompt.text)
        .putExtra(PromptInbox.EXTRA_CONTEXT, prompt.context)
        .putExtra(PromptInbox.EXTRA_MINUTES, prompt.minutes ?: -1)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val open = PendingIntent.getActivity(context, prompt.id.hashCode(), speak, PendingIntent.FLAG_UPDATE_CURRENT or immutable)
    val stop = PendingIntent.getBroadcast(
        context, prompt.id.hashCode() + 1,
        Intent(context, PromptActionReceiver::class.java).setAction(ACTION_STOP).putExtra(PromptInbox.EXTRA_ID, family(prompt.id)),
        PendingIntent.FLAG_UPDATE_CURRENT or immutable
    )
    val notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle(prompt.text)
        .setContentText("Tap to answer by voice")
        .setContentIntent(open)
        .setAutoCancel(true)
        .addAction(0, "Answer", open)
        .addAction(0, "Stop asking this", stop)
        .build()
    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    log.asked(prompt.id)
    // Prompts after events count, and stop, together.
    if (family(prompt.id) != prompt.id) log.asked(family(prompt.id))
}

private fun family(id: String) = if (id.startsWith("${PromptRules.EVENT}:")) PromptRules.EVENT else id

/** "Stop asking this" from a notification. */
class PromptActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        intent.getStringExtra(PromptInbox.EXTRA_ID)?.let { PromptLog(context).stop(it) }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
