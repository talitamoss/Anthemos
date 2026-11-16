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
import com.domain.app.voice.Entry
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

    fun take(): Prompt? = waiting.value.also { waiting.value = null }

    fun fromIntent(intent: Intent?): Prompt? {
        val id = intent?.getStringExtra(EXTRA_ID) ?: return null
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return null
        return Prompt(id, text, intent.getStringExtra(EXTRA_CONTEXT))
    }

    internal const val EXTRA_ID = "prompt_id"
    internal const val EXTRA_TEXT = "prompt_text"
    internal const val EXTRA_CONTEXT = "prompt_context"
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
        PromptRules.due(now, entries, log)?.let { show(applicationContext, it, log) }
        return Result.success()
    }

    companion object {
        private const val WORK = "anthemos-prompts"
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
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val open = PendingIntent.getActivity(context, prompt.id.hashCode(), speak, PendingIntent.FLAG_UPDATE_CURRENT or immutable)
    val stop = PendingIntent.getBroadcast(
        context, prompt.id.hashCode() + 1,
        Intent(context, PromptActionReceiver::class.java).setAction(ACTION_STOP).putExtra(PromptInbox.EXTRA_ID, prompt.id),
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
}

/** "Stop asking this" from a notification. */
class PromptActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        intent.getStringExtra(PromptInbox.EXTRA_ID)?.let { PromptLog(context).stop(it) }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
