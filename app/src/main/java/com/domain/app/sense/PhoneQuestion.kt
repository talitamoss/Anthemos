// app/src/main/java/com/domain/app/sense/PhoneQuestion.kt
package com.domain.app.sense

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Questions the phone itself can answer: screen time and last night's sleep. */
sealed class PhoneQuestion {
    data class ScreenTime(val from: Instant, val until: Instant, val phrase: String) : PhoneQuestion()
    object Slept : PhoneQuestion()

    companion object {
        fun parse(raw: String, now: ZonedDateTime): PhoneQuestion? {
            val text = raw.lowercase().replace(Regex("[?.!,]"), " ").replace(Regex("\\s+"), " ").trim()
            if (SLEPT.containsMatchIn(text)) return Slept
            if (!SCREEN.containsMatchIn(text)) return null
            val midnight = now.truncatedTo(ChronoUnit.DAYS)
            return when {
                text.contains("yesterday") -> ScreenTime(midnight.minusDays(1).toInstant(), midnight.toInstant(), "yesterday")
                text.contains("this week") -> ScreenTime(
                    midnight.minusDays(now.dayOfWeek.value - 1L).toInstant(), now.toInstant(), "this week"
                )
                else -> ScreenTime(midnight.toInstant(), now.toInstant(), "today")
            }
        }

        private val SCREEN = Regex("\\bscreen time\\b|how (?:long|much time) (?:have i|did i) (?:been |spent |spend )?on (?:my|the) phone")
        private val SLEPT = Regex("how (?:long|much|well) did i sleep|what time did i (?:go to bed|get up|wake up|fall asleep)|how much sleep did i get")
    }
}

object PhoneAnswers {
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    fun hours(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }

    fun screenTime(phrase: String, minutes: Int, top: List<Pair<String, Int>>): String {
        val apps = if (top.isEmpty()) "" else " Most: " + top.joinToString(", ") { (name, m) -> "$name ${hours(m)}" } + "."
        return "Screen time $phrase: ${hours(minutes)}.$apps"
    }

    fun slept(guess: SleepGuess?, now: ZonedDateTime): String {
        guess ?: return "I can't tell from the phone last night."
        val bed = TIME.format(guess.bed.atZone(now.zone)).lowercase()
        val up = TIME.format(guess.up.atZone(now.zone)).lowercase()
        return "Phone down at $bed, picked up at $up: about ${hours(guess.minutes)}."
    }
}
