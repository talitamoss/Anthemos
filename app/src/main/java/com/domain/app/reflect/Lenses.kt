// app/src/main/java/com/domain/app/reflect/Lenses.kt
package com.domain.app.reflect

import com.domain.app.voice.Item
import java.time.Duration
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The reflections on the page, in this order. */
object Lenses {
    val ALL: List<Lens> = listOf(SleepLens, MovementLens, MoodLens, ScreenLens)

    fun look(record: Record): List<Reflection> = ALL.mapNotNull { it.look(record) }
}

/**
 * Sleep score, out of 100, over the last week. Most of it (70) is how long
 * you slept against eight hours; the rest (30) is how steady your bedtime
 * was, once three are known. A night comes from what you said ("slept seven
 * hours"), then from "went to bed" and "woke up", then from the phone.
 */
object SleepLens : Lens {
    override val id = "sleep"

    private const val GOAL = 8 * 60
    private const val STEADY_SPREAD = 120.0

    data class Night(val minutes: Int, val bedMinute: Int?)

    fun night(record: Record, morning: LocalDate): Night? {
        val day = record.on(morning)
        day.flatMap { it.items }.firstOrNull { it.kind == "sleep" && (it.minutes ?: 0) > 0 }?.let {
            return Night(it.minutes!!, null)
        }
        val evening = morning.minusDays(1).atTime(18, 0).atZone(record.zone).toInstant()
        val noon = morning.atTime(13, 0).atZone(record.zone).toInstant()
        val bed = record.entries.lastOrNull { e -> e.time in evening..noon && e.items.any { it.kind == "bed" } }
        val wake = record.entries.firstOrNull { e -> bed != null && e.time > bed.time && e.time <= noon && e.items.any { it.kind == "wake" } }
        if (bed != null && wake != null) {
            return Night(Duration.between(bed.time, wake.time).toMinutes().toInt(), bedMinute(bed.time, record))
        }
        return record.phoneNights[morning]?.let { Night(it.minutes, bedMinute(it.bed, record)) }
    }

    /** Minutes after 6 pm, so a bedtime either side of midnight compares. */
    private fun bedMinute(at: java.time.Instant, record: Record): Int {
        val t = at.atZone(record.zone).toLocalTime()
        return ((t.hour - 18 + 24) % 24) * 60 + t.minute
    }

    fun score(nights: List<Night>): Int {
        val avg = nights.map { it.minutes }.average()
        val length = (avg / GOAL).coerceAtMost(1.0)
        val beds = nights.mapNotNull { it.bedMinute }
        if (beds.size < 3) return (length * 100).roundToInt()
        val mean = beds.average()
        val spread = sqrt(beds.sumOf { (it - mean) * (it - mean) } / beds.size)
        val steady = (1 - spread / STEADY_SPREAD).coerceIn(0.0, 1.0)
        return ((0.7 * length + 0.3 * steady) * 100).roundToInt()
    }

    override fun look(record: Record): Reflection? {
        val nights = record.week.map { night(record, it) }
        val known = nights.filterNotNull()
        if (known.isEmpty()) return null
        val avg = known.map { it.minutes }.average().roundToInt()
        val last = nights.last()
        return Reflection(
            id, "Sleep score", score(known).toString(),
            listOfNotNull(last?.let { hm(it.minutes) + " last night" }, hm(avg) + " a night this week").joinToString(" · "),
            nights.map { it?.minutes?.toFloat() }
        )
    }
}

/**
 * Movement this week: sport, the gym, walks and time outside. Minutes when
 * they were said, otherwise how many times.
 */
object MovementLens : Lens {
    override val id = "movement"

    private val MOVES = Regex("\\b(?:walk|walked|walking|run|ran|running|jog|jogging|swim|swam|swimming|yoga|cycle|cycling|ride|rode|hike|hiked|hiking|gym|workout|stretch|stretching|pilates|dance|danced|dancing)\\b")

    fun moves(item: Item) = item.kind == "activity" || item.kind == "outside" || item.group == "sport" || MOVES.containsMatchIn(item.label)

    override fun look(record: Record): Reflection? {
        val found = record.thisWeek(::moves)
        if (found.isEmpty()) return null
        val minutes = found.sumOf { it.second.minutes ?: 0 }
        val days = found.map { record.dateOf(it.first) }.toSet().size
        val most = found.groupingBy { it.second.label }.eachCount().maxByOrNull { it.value }?.key
        val byDay = record.week.map { d ->
            val those = found.filter { record.dateOf(it.first) == d }
            if (those.isEmpty()) null else if (minutes > 0) those.sumOf { it.second.minutes ?: 0 }.toFloat() else those.size.toFloat()
        }
        return Reflection(
            id, "Movement",
            if (minutes > 0) hm(minutes) else "${found.size} ${if (found.size == 1) "time" else "times"}",
            listOfNotNull("this week, ${if (days == 1) "one day" else "$days days"}", most?.let { "mostly $it" }).joinToString(" · "),
            byDay
        )
    }
}

/** Mood this week: the average of the scores given, and the word said most. */
object MoodLens : Lens {
    override val id = "mood"

    private fun scoreOf(item: Item) = item.details["score"]?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

    override fun look(record: Record): Reflection? {
        val found = record.thisWeek { it.kind == "mood" }
        if (found.isEmpty()) return null
        val scores = found.mapNotNull { scoreOf(it.second) }
        val word = found.map { it.second.label }.filter { it != "mood" }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val byDay = record.week.map { d ->
            found.filter { record.dateOf(it.first) == d }.mapNotNull { scoreOf(it.second) }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        }
        return if (scores.isNotEmpty()) {
            Reflection(id, "Mood", "%.1f / 10".format(java.util.Locale.ROOT, scores.average()).replace(".0 ", " "), listOfNotNull("this week", word?.let { "mostly $it" }).joinToString(" · "), byDay)
        } else {
            Reflection(id, "Mood", word ?: "${found.size} noted", "this week", byDay)
        }
    }
}

/** Screen time, from the phone, when usage access is on. */
object ScreenLens : Lens {
    override val id = "screen"

    override fun look(record: Record): Reflection? {
        if (record.screen.isEmpty()) return null
        val days = record.week.map { record.screen[it] }
        val today = days.last() ?: return null
        val past = days.dropLast(1).filterNotNull()
        return Reflection(
            id, "Screen time", hm(today),
            listOfNotNull("today", past.takeIf { it.isNotEmpty() }?.let { hm(it.average().roundToInt()) + " a day before" }).joinToString(" · "),
            days.map { it?.toFloat() }
        )
    }
}
