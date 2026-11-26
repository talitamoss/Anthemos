// app/src/main/java/com/domain/app/reflect/More.kt
package com.domain.app.reflect

import com.domain.app.voice.Entry
import com.domain.app.voice.Item
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

// The lenses beyond sleep, movement, mood and screen time, built from a few
// shapes: minutes or times this week, a count a day, days since, days out of
// seven, and the latest measure.

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

private fun Record.byDay(found: List<Pair<Entry, Item>>, value: (List<Item>) -> Float?): List<Float?> =
    week.map { d -> found.filter { dateOf(it.first) == d }.map { it.second }.takeIf { it.isNotEmpty() }?.let(value) }

/** Minutes this week when said, otherwise how many times, and what most. */
open class TimeLens(override val id: String, private val title: String, private val matches: (Item) -> Boolean) : Lens {
    override fun look(record: Record): Reflection? {
        val found = record.thisWeek(matches)
        if (found.isEmpty()) return null
        val minutes = found.sumOf { it.second.minutes ?: 0 }
        val most = found.groupingBy { it.second.label }.eachCount().maxByOrNull { it.value }?.key
        return Reflection(
            id, title,
            if (minutes > 0) hm(minutes) else plural(found.size, "time"),
            listOfNotNull("this week", most?.takeIf { found.map { f -> f.second.label }.toSet().size > 1 }?.let { "mostly $it" }).joinToString(" · "),
            record.byDay(found) { items -> if (minutes > 0) items.sumOf { it.minutes ?: 0 }.toFloat() else items.size.toFloat() }
        )
    }
}

object MeditationLens : TimeLens("meditation", "Meditation", { it.label == "meditation" || it.label == "breathwork" })
object WorkLens : TimeLens("work", "Work", { it.kind == "work" || it.kind == "meeting" })
object LearningLens : TimeLens("learning", "Learning", { it.kind == "study" })
object MakingLens : TimeLens("making", "Making", { it.kind == "creative" })
object ScrollingLens : TimeLens("scrolling", "Scrolling", { it.kind == "scrolling" })

/** People seen or called this week, and who most. */
object PeopleLens : Lens {
    override val id = "people"

    override fun look(record: Record): Reflection? {
        val found = record.thisWeek { it.kind == "social" || it.kind == "call" }
        if (found.isEmpty()) return null
        val who = found.mapNotNull { it.second.details["who"] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return Reflection(
            id, "Time with people", plural(found.size, "time"),
            listOfNotNull("this week", who?.let { "most with $it" }).joinToString(" · "),
            record.byDay(found) { it.size.toFloat() }
        )
    }
}

/** Steps: today, and a day on average this week. */
object StepsLens : Lens {
    override val id = "steps"

    override fun look(record: Record): Reflection? {
        val found = record.thisWeek { it.kind == "steps" }
        if (found.isEmpty()) return null
        // The highest said in a day: a later count includes the earlier.
        val days = record.byDay(found) { items -> items.maxOf { it.count }.toFloat() }
        val known = days.filterNotNull()
        val shown = days.last() ?: known.last()
        return Reflection(
            id, "Steps", "%,d".format(Locale.ROOT, shown.toInt()),
            listOf(if (days.last() != null) "today" else "last counted", "%,d a day this week".format(Locale.ROOT, known.average().roundToInt())).joinToString(" · "),
            days
        )
    }
}

/** A count of drinks a day in a group (water by the glass, coffee by the cup). */
open class DrinkLens(
    override val id: String,
    private val title: String,
    private val group: String,
    private val unit: String,
    private val units: String = unit + "s"
) : Lens {
    override fun look(record: Record): Reflection? {
        val found = record.thisWeek { it.kind == "drink" && it.group == group && it.count > 0 }
        if (found.isEmpty()) return null
        val days = record.byDay(found) { items -> items.sumOf { it.count }.toFloat() }
        val today = days.last()?.toInt() ?: 0
        val avg = found.sumOf { it.second.count }.toDouble() / days.count { it != null }
        return Reflection(
            id, title, plural(today, unit, units),
            "today · %.1f a day when you had any".format(Locale.ROOT, avg).replace(".0 ", " "),
            days
        )
    }
}

object WaterLens : DrinkLens("water", "Water", "water", "glass", "glasses")
object CoffeeLens : DrinkLens("coffee", "Coffee", "coffee", "cup")

/** Drinks this week, and the days you said had none or had none recorded. */
object AlcoholLens : Lens {
    override val id = "alcohol"

    override fun look(record: Record): Reflection? {
        val found = record.thisWeek { it.kind == "drink" && it.group == "alcohol" }
        if (found.none { it.second.count > 0 }) return null
        val drinks = found.sumOf { it.second.count }
        val drinking = found.filter { it.second.count > 0 }.map { record.dateOf(it.first) }.toSet()
        val recorded = record.week.filter { record.on(it).isNotEmpty() }
        val without = recorded.count { it !in drinking }
        return Reflection(
            id, "Alcohol", plural(drinks, "drink"),
            "this week · ${plural(without, "day")} without",
            record.byDay(found) { items -> items.sumOf { it.count }.toFloat().takeIf { it > 0f } }
        )
    }
}

/**
 * Days free of something you're cutting out: since the last time, or since
 * you said you quit. Shown once you've said anything about it. On a day you
 * had some, it says how many instead.
 */
open class SinceLens(
    override val id: String,
    private val freeTitle: String,
    private val usedTitle: String,
    private val unit: String,
    private val matches: (Item) -> Boolean,
    private val used: (Item) -> Boolean
) : Lens {
    override fun look(record: Record): Reflection? {
        val all = record.entries.flatMap { e -> e.items.filter(matches).map { e to it } }
        if (all.isEmpty()) return null
        val uses = all.filter { used(it.second) }
        val week = record.byDay(uses.filter { record.dateOf(it.first) in record.week }) { items -> items.sumOf { it.count.coerceAtLeast(1) }.toFloat() }
        val last = uses.lastOrNull()?.let { record.dateOf(it.first) }
        if (last == record.today) {
            val today = uses.filter { record.dateOf(it.first) == record.today }.sumOf { it.second.count.coerceAtLeast(1) }
            val thisWeek = week.filterNotNull().sum().toInt()
            return Reflection(id, usedTitle, "${plural(today, unit)} today", "${plural(thisWeek, unit)} this week", week)
        }
        val from = last ?: all.first().let { record.dateOf(it.first) }
        val days = ChronoUnit.DAYS.between(from, record.today).toInt()
        return Reflection(
            id, freeTitle, plural(days, "day"),
            if (last != null) "since ${DAY.format(last)}" else "since you said, ${DAY.format(from)}",
            week
        )
    }
}

object SmokeFreeLens : SinceLens(
    "smoke-free", "Smoke-free", "Smoking", "cigarette",
    { it.kind == "smoking" }, { it.kind == "smoking" && it.label != "none" && it.label != "quit" }
)
object CannabisFreeLens : SinceLens(
    "cannabis-free", "Cannabis-free", "Cannabis", "time",
    { it.kind == "cannabis" }, { it.kind == "cannabis" && it.label != "none" && it.label != "quit" }
)
// Only for someone who has said a day was alcohol-free: then it counts.
object AlcoholFreeLens : Lens {
    override val id = "alcohol-free"
    private val since = SinceLens(
        id, "Alcohol-free", "Alcohol", "drink",
        { it.kind == "drink" && it.group == "alcohol" }, { it.kind == "drink" && it.group == "alcohol" && it.count > 0 }
    )

    override fun look(record: Record): Reflection? {
        if (record.entries.none { e -> e.items.any { it.kind == "drink" && it.details["none"] == "yes" } }) return null
        return since.look(record)?.takeIf { it.title == "Alcohol-free" }
    }
}

/** One reflection for each thing you do for yourself: days out of the last seven. */
object CareLens : Lens {
    override val id = "care"

    override fun look(record: Record): Reflection? = null

    override fun all(record: Record): List<Reflection> {
        val found = record.thisWeek { it.kind == "care" }
        return found.groupBy { it.second.label }.map { (label, those) ->
            val days = those.map { record.dateOf(it.first) }.toSet()
            Reflection(
                "care-$label", label.replaceFirstChar { it.uppercase() }, "${days.size} of 7 days", "this week",
                record.week.map { if (it in days) 1f else null }
            )
        }
    }
}

/** Your latest weight, and how it has moved over about a month. */
object WeightLens : Lens {
    override val id = "weight"

    private fun kg(item: Item): Pair<Double, String>? {
        val (n, unit) = item.details["value"]?.split(" ")?.let { it.getOrNull(0)?.toDoubleOrNull() to it.getOrElse(1) { "kg" } } ?: return null
        return n?.let { it to unit }
    }

    override fun look(record: Record): Reflection? {
        val found = record.entries.flatMap { e -> e.items.filter { it.kind == "weight" }.mapNotNull { i -> kg(i)?.let { e to it } } }
        if (found.isEmpty()) return null
        val (lastEntry, last) = found.last()
        val monthAgo = record.today.minusDays(30)
        val then = found.firstOrNull { record.dateOf(it.first) >= monthAgo && it.second.second == last.second && it.first !== lastEntry }
        val change = then?.let { last.first - it.second.first }
        val note = listOfNotNull(
            DAY.format(record.dateOf(lastEntry)),
            change?.let { "%+.1f %s in a month".format(Locale.ROOT, it, last.second) }
        ).joinToString(" · ")
        return Reflection(
            id, "Weight", "%s %s".format(Locale.ROOT, "%.1f".format(Locale.ROOT, last.first).removeSuffix(".0"), last.second), note,
            record.week.map { d -> found.lastOrNull { record.dateOf(it.first) == d }?.second?.first?.toFloat() }
        )
    }
}

/** The latest of each measure said: heart rate, blood pressure, temperature, blood sugar, oxygen, waist. */
object VitalsLens : Lens {
    override val id = "vitals"

    private val UNITS = mapOf("heart rate" to " bpm", "resting heart rate" to " bpm", "temperature" to "°", "oxygen" to "%", "waist" to " cm")

    override fun look(record: Record): Reflection? = null

    override fun all(record: Record): List<Reflection> {
        val found = record.entries.flatMap { e -> e.items.filter { it.kind == "vital" && it.details["value"] != null }.map { e to it } }
        return found.groupBy { it.second.label }.map { (label, those) ->
            val (entry, item) = those.last()
            val week = record.week.map { d -> those.lastOrNull { record.dateOf(it.first) == d }?.second?.details?.get("value")?.substringBefore('/')?.toFloatOrNull() }
            Reflection(
                "vital-$label", label.replaceFirstChar { it.uppercase() }, item.details["value"] + UNITS[label].orEmpty(),
                if (record.dateOf(entry) == record.today) "today" else DAY.format(record.dateOf(entry)),
                week
            )
        }
    }
}

/** Symptoms and injuries this week, and which most. */
object SymptomsLens : Lens {
    override val id = "symptoms"

    override fun look(record: Record): Reflection? {
        val found = record.thisWeek { it.kind == "symptom" || it.kind == "injury" }
        if (found.isEmpty()) return null
        val most = found.groupingBy { it.second.label }.eachCount().maxByOrNull { it.value }!!.key
        val days = found.map { record.dateOf(it.first) }.toSet().size
        return Reflection(
            id, "Symptoms", most,
            "this week, ${plural(days, "day")}" + if (found.map { it.second.label }.toSet().size > 1) " · and ${plural(found.size - found.count { it.second.label == most }, "other")}" else "",
            record.byDay(found) { it.size.toFloat() }
        )
    }
}

/** Things kept in words (gratitude, wins): how many this week, and the latest. */
open class KeptLens(override val id: String, private val title: String, private val kind: String, private val unit: String) : Lens {
    override fun look(record: Record): Reflection? {
        val found = record.thisWeek { it.kind == kind }
        if (found.isEmpty()) return null
        return Reflection(id, title, plural(found.size, unit), found.last().second.label, record.byDay(found) { it.size.toFloat() })
    }
}

object GratitudeLens : KeptLens("gratitude", "Grateful for", "gratitude", "thing")
object WinsLens : KeptLens("wins", "Wins", "win", "win")
