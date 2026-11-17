// app/src/main/java/com/domain/app/calendar/Reminders.kt
package com.domain.app.calendar

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.domain.app.MainActivity
import com.domain.app.core.data.DataRepository
import com.domain.app.prompts.PromptInbox
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Anthemos sets off its own reminders: its calendar lives in its own store,
 * and there may be no calendar app on the phone to do it (Proton Calendar
 * can't see Anthemos's events). An alarm at the time, then a notification;
 * a reminder like "Call Dad" opens Anthemos ready to make the call.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    internal const val EXTRA_EVENT = "event_id"
    internal const val EXTRA_TITLE = "event_title"

    fun schedule(context: Context, event: Event) {
        if (!event.reminder || event.start.isBefore(Instant.now())) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = alarm(context, event.id, event.title)
        val at = event.start.toEpochMilli()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(context: Context, event: Event) {
        context.getSystemService(AlarmManager::class.java).cancel(alarm(context, event.id, event.title))
    }

    private fun alarm(context: Context, id: String, title: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, id.hashCode(),
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_EVENT, id).putExtra(EXTRA_TITLE, title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    internal fun notify(context: Context, id: String, title: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        // "Call Dad": tapping opens Anthemos and starts the call, confirmed as usual.
        val open = Intent(context, MainActivity::class.java)
            .setAction("com.anthemos.LISTEN")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Regex("^(call|ring|phone)\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)) {
            open.putExtra(PromptInbox.EXTRA_SAY, title)
        }
        val tap = PendingIntent.getActivity(context, id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText("Reminder")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }

    /**
     * Opens the phone's calendar app on a new event with the details filled
     * in: Proton Calendar if it takes the standard request. False if no app
     * on the phone does.
     */
    fun handOff(context: Context, event: Event): Boolean {
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, event.title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start.toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end.toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        event.place?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}

/** The alarm going off. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Reminders.EXTRA_EVENT) ?: return
        Reminders.notify(context, id, intent.getStringExtra(Reminders.EXTRA_TITLE) ?: "Reminder")
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RemindersEntryPoint {
    fun dataRepository(): DataRepository
}

/** Alarms don't survive a restart: set them again from the store. */
class RemindersOnBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val dataRepository = EntryPointAccessors.fromApplication(context.applicationContext, RemindersEntryPoint::class.java)
            .dataRepository()
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                dataRepository.getPluginData(Event.PLUGIN_ID).first()
                    .mapNotNull(Event::from)
                    .forEach { Reminders.schedule(context, it) }
            } finally {
                done.finish()
            }
        }
    }
}
