// app/src/main/java/com/domain/app/clock/Clock.kt
package com.domain.app.clock

import com.domain.app.calendar.When
import com.domain.app.voice.Numbers
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Timers and alarms, said (docs/anthemos-2.md, "Phone"). They're set in the
 * phone's own Clock app, so they ring as usual with Anthemos closed.
 */
sealed class Clock {
    data class Timer(val seconds: Int, val label: String?) : Clock()
    data class Alarm(val time: LocalTime, val label: String?, val days: List<DayOfWeek> = emptyList()) : Clock()
    /** "Stop the timer". */
    object StopTimer : Clock()
    /** "Show my alarms", "cancel my alarm": the Clock app's list, to pick from. */
    object ShowAlarms : Clock()
    object ShowTimers : Clock()

    companion object {
        /** [command] is lowercase, without punctuation or "hey Polly". */
        fun parse(command: String, now: ZonedDateTime): Clock? {
            val c = command.trim()
            if (STOP_TIMER.matches(c)) return StopTimer
            if (SHOW_ALARMS.matches(c)) return ShowAlarms
            if (SHOW_TIMERS.matches(c)) return ShowTimers

            TIMER.matchEntire(c)?.let { m ->
                val (forWhat, before, named, length) = m.destructured
                val seconds = seconds(forWhat.ifEmpty { before.ifEmpty { length } }) ?: return null
                return Timer(seconds, named.trim().takeIf { it.isNotEmpty() })
            }

            ALARM.matchEntire(c)?.let { m ->
                val rest = m.groupValues[1]
                val days = days(rest)
                val found = When.find("at " + rest.replace(DAYS_WORDS, " ").trim(), now)
                val said = found.time ?: BARE_HOUR.find(rest)?.let { h -> Numbers.parse(h.groupValues[1])?.takeIf { it in 0..23 }?.let { LocalTime.of(it, 0) } } ?: return null
                // An alarm with no am or pm is for the morning: "wake me up at 7" said at night.
                val time = if (said.hour in 13..23 && !Regex("\\b(?:pm|p m|evening|tonight|night|afternoon)\\b").containsMatchIn(rest)) said.minusHours(12) else said
                val label = Regex("\\b(?:for|to) ([a-z ]+?)$").find(found.rest.trim())?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() && !Regex("^(?:me|wake me|wake up|get up)$").matches(it.trim()) }
                return Alarm(time, label, days)
            }
            return null
        }

        /** "ten minutes", "an hour and a half", "1 hour 30 minutes", "90 seconds", "half an hour". */
        fun seconds(words: String): Int? {
            var w = words.replace("-", " ")
            var total = 0
            var any = false
            if (Regex("\\bhalf an hour\\b").containsMatchIn(w)) {
                total += 1800
                any = true
                w = w.replace(Regex("\\bhalf an hour\\b"), " ")
            }
            Regex("\\b(${Numbers.PATTERN}|an|a) (hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b").findAll(w).forEach { m ->
                val n = if (m.groupValues[1] == "a" || m.groupValues[1] == "an") 1 else Numbers.parse(m.groupValues[1]) ?: return@forEach
                total += n * when {
                    m.groupValues[2].startsWith("h") -> 3600
                    m.groupValues[2].startsWith("m") -> 60
                    else -> 1
                }
                any = true
            }
            if (Regex("\\b(?:hours?|minutes?) and a half\\b").containsMatchIn(w)) {
                total += if (Regex("\\bhours? and a half").containsMatchIn(w)) 1800 else 30
            }
            return if (any && total > 0) total else null
        }

        private fun days(text: String): List<DayOfWeek> = when {
            Regex("\\b(?:every day|daily|each day)\\b").containsMatchIn(text) -> DayOfWeek.values().toList()
            Regex("\\b(?:weekdays|every weekday|on weekdays|work days)\\b").containsMatchIn(text) -> DayOfWeek.values().take(5)
            Regex("\\b(?:weekends|every weekend|on weekends)\\b").containsMatchIn(text) -> DayOfWeek.values().drop(5)
            else -> DayOfWeek.values().filter { d -> Regex("\\bevery ${d.name.lowercase()}\\b").containsMatchIn(text) }
        }

        private val TIMER = Regex(
            "^(?:set |start |make )?(?:a |an )?(?:timer (?:for )?(.+)|(.+?) timer(?: please)?|(.+?) timer for (.+))$"
        )
        private val ALARM = Regex("^(?:set (?:an |the |my |a )?alarm(?: for| at)?|wake me(?: up)?(?: at)?|alarm(?: for| at)?) (.+)$")
        private val BARE_HOUR = Regex("^(?:at )?(${Numbers.PATTERN})(?: o'?clock)?\\b")
        private val DAYS_WORDS = Regex("\\b(?:every (?:day|weekday|weekend|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|daily|each day|weekdays|weekends|on weekdays|on weekends|work days)\\b")
        private val STOP_TIMER = Regex("^(?:stop|cancel|end|turn off|dismiss|delete) (?:the |my )?timers?$")
        private val SHOW_ALARMS = Regex("^(?:show|open|list|what are|cancel|delete|turn off|remove)(?: me)? (?:my |the )?alarms?$|^what alarms (?:do i have|are set)$")
        private val SHOW_TIMERS = Regex("^(?:show|open|list)(?: me)? (?:my |the )?timers?$|^how (?:long|much time) (?:is )?left on (?:the |my )?timer$")
    }
}
