// app/src/main/java/com/domain/app/focus/ClockFocus.kt
package com.domain.app.focus

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.AlarmClock
import com.domain.app.clock.Clock
import com.domain.app.voice.Focus
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.command
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Clock: timers and alarms, set in the phone's own Clock app so they ring
 * as usual with Anthemos closed (docs/anthemos-2.md, "Phone").
 */
object ClockFocus {

    fun focus() = Focus(
        "clock", "Timers and alarms, in the phone's Clock", listOf(
            command(
                "clock.set", "Sets a timer or an alarm, stops a timer, shows alarms",
                listOf("set a timer for ten minutes", "pasta timer for 12 minutes", "set an alarm for 6:30", "wake me up at 7 every weekday", "stop the timer", "show my alarms"),
                { s -> Clock.parse(s.command, s.now)?.let { VoiceRequest.Clock(s.transcript, it) } }
            ) { set(it.clock) }
        )
    )

    private fun Turn.set(clock: Clock) {
        val (intent, said) = when (clock) {
            is Clock.Timer -> Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, clock.seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply { clock.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it.replaceFirstChar { c -> c.uppercase() }) } } to
                "Timer set for ${length(clock.seconds)}"
            is Clock.Alarm -> Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, clock.time.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, clock.time.minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply {
                    clock.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it.replaceFirstChar { c -> c.uppercase() }) }
                    if (clock.days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(clock.days.map { it.value % 7 + 1 }))
                } to "Alarm set for ${TIME.format(clock.time).lowercase()}" + days(clock)
            Clock.StopTimer -> Intent(AlarmClock.ACTION_DISMISS_TIMER) to "Timer stopped"
            Clock.ShowAlarms -> Intent(AlarmClock.ACTION_SHOW_ALARMS) to "Here are your alarms"
            Clock.ShowTimers -> Intent(AlarmClock.ACTION_SHOW_TIMERS) to "Here are your timers"
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            reply(said)
        } catch (e: ActivityNotFoundException) {
            reply("The phone's Clock app didn't take that")
        }
    }

    /** "10 minutes", "1 hour 30 minutes", "45 seconds". */
    fun length(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour" + if (it > 1) "s" else "" },
            m.takeIf { it > 0 }?.let { "$it minute" + if (it > 1) "s" else "" },
            s.takeIf { it > 0 }?.let { "$it second" + if (it > 1) "s" else "" }
        ).joinToString(" ")
    }

    private fun days(alarm: Clock.Alarm) = when (alarm.days.size) {
        0 -> ""
        7 -> ", every day"
        5 -> if (alarm.days.none { it.value > 5 }) ", weekdays" else ""
        else -> ", " + alarm.days.joinToString(" and ") { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } + "s" }
    }

    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
}
