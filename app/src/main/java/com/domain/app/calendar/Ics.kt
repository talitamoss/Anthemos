// app/src/main/java/com/domain/app/calendar/Ics.kt
package com.domain.app.calendar

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Reads a calendar shared as a link (an .ics file: Proton's "Share with
 * anyone", Google's secret address) into [Event]s, with repeating events
 * worked out over a window (docs/anthemos-2.md, "Calendar"). Only what
 * answering needs: titles, times, places, and the usual repeats.
 */
object Ics {

    data class Vevent(
        val uid: String,
        val title: String,
        val start: ZonedDateTime,
        val end: ZonedDateTime,
        val allDay: Boolean,
        val place: String?,
        val rule: Map<String, String>?,
        val except: Set<Instant>,
        /** Set on a changed occurrence of a repeating event: which one it replaces. */
        val replaces: Instant?
    )

    fun parse(text: String, zone: ZoneId): List<Vevent> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
            .fold(mutableListOf<String>()) { acc, line ->
                if ((line.startsWith(" ") || line.startsWith("\t")) && acc.isNotEmpty()) acc[acc.size - 1] = acc.last() + line.substring(1)
                else acc += line
                acc
            }
        val events = mutableListOf<Vevent>()
        var props: MutableList<Triple<String, Map<String, String>, String>>? = null
        var depth = 0
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> { props = mutableListOf(); depth = 0 }
                line == "END:VEVENT" -> { props?.let { event(it, zone)?.let(events::add) }; props = null }
                // Alarms inside an event have their own properties.
                line.startsWith("BEGIN:") && props != null -> depth++
                line.startsWith("END:") && props != null -> depth--
                props != null && depth == 0 -> property(line)?.let { props.add(it) }
            }
        }
        return events
    }

    /** Every occurrence starting before [until] and ending after [from]. */
    fun expand(events: List<Vevent>, from: Instant, until: Instant): List<Event> {
        val replaced = events.filter { it.replaces != null }.map { it.uid to it.replaces!! }.toSet()
        return events.flatMap { e ->
            val length = Duration.between(e.start, e.end)
            occurrences(e, until)
                .filter { it.toInstant() !in e.except && (e.replaces != null || (e.uid to it.toInstant()) !in replaced) }
                .map { start -> Event("link:${e.uid}:${start.toEpochSecond()}", e.title, start.toInstant(), start.plus(length).toInstant(), e.allDay, e.place) }
                .filter { it.start < until && it.end > from }
        }
    }

    private fun occurrences(e: Vevent, until: Instant): Sequence<ZonedDateTime> {
        val rule = e.rule ?: return sequenceOf(e.start)
        val interval = rule["INTERVAL"]?.toLongOrNull()?.coerceAtLeast(1) ?: 1
        val count = rule["COUNT"]?.toIntOrNull()
        val last = rule["UNTIL"]?.let { time(it, emptyMap(), e.start.zone)?.first?.toInstant() }
        val days = rule["BYDAY"]?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        val monthDays = rule["BYMONTHDAY"]?.split(",")?.mapNotNull { it.toIntOrNull() }.orEmpty()
        val positions = rule["BYSETPOS"]?.split(",")?.mapNotNull { it.toIntOrNull() }.orEmpty()
        // Each period (day, week, month, year) is only looked at while it starts before
        // [until], so every rule ends, even one that never lands on a day.
        fun <T> Sequence<T>.upTo(start: (T) -> ZonedDateTime) = takeWhile { start(it).toInstant() < until }.take(PERIODS)
        val candidates: Sequence<ZonedDateTime> = when (rule["FREQ"]) {
            "DAILY" -> generateSequence(e.start) { it.plusDays(interval) }.upTo { it }
            "WEEKLY" -> {
                val wanted = days.mapNotNull { DAY[it.takeLast(2)] }.ifEmpty { listOf(e.start.dayOfWeek) }.sorted()
                val monday = e.start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                generateSequence(monday) { it.plusWeeks(interval) }.upTo { it }
                    .flatMap { week -> wanted.asSequence().map { week.with(TemporalAdjusters.nextOrSame(it)) } }
            }
            "MONTHLY" -> generateSequence(e.start.withDayOfMonth(1)) { it.plusMonths(interval) }.upTo { it }.flatMap { month ->
                val inMonth = when {
                    days.isNotEmpty() -> days.flatMap { inMonth(month, it) }
                    monthDays.isNotEmpty() -> monthDays.mapNotNull { dayOfMonth(month, it) }
                    else -> listOfNotNull(dayOfMonth(month, e.start.dayOfMonth))
                }.distinct().sorted()
                // "The last weekday": BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1.
                if (positions.isEmpty()) inMonth.asSequence()
                else positions.mapNotNull { p -> inMonth.getOrNull(if (p > 0) p - 1 else inMonth.size + p) }.sorted().asSequence()
            }
            // Counted from the start, so 29 February stays the 29th.
            "YEARLY" -> generateSequence(0L) { it + interval }.map { e.start.plusYears(it) }.upTo { it }.filter { it.dayOfMonth == e.start.dayOfMonth }
            else -> sequenceOf(e.start)
        }
        var n = 0
        return candidates.filter { !it.isBefore(e.start) }
            .takeWhile { it.toInstant() < until && (last == null || !it.toInstant().isAfter(last)) && (count == null || n < count) }
            .onEach { n++ }
    }

    /** "2TU" is the second Tuesday, "-1FR" the last Friday, a bare "TU" every Tuesday in the month. */
    private fun inMonth(month: ZonedDateTime, spec: String): List<ZonedDateTime> {
        val day = DAY[spec.takeLast(2)] ?: return emptyList()
        if (spec.length == 2) {
            return generateSequence(month.with(TemporalAdjusters.firstInMonth(day))) { it.plusWeeks(1) }
                .takeWhile { it.month == month.month }.toList()
        }
        return listOfNotNull(nthDay(month, spec))
    }

    /** "2TU" is the second Tuesday, "-1FR" the last Friday; a bare "TU" every Tuesday is left to weekly. */
    private fun nthDay(month: ZonedDateTime, spec: String): ZonedDateTime? {
        val day = DAY[spec.takeLast(2)] ?: return null
        val n = spec.dropLast(2).toIntOrNull() ?: return null
        return if (n > 0) month.with(TemporalAdjusters.dayOfWeekInMonth(n, day)).takeIf { it.month == month.month }
        else month.with(TemporalAdjusters.lastInMonth(day)).minusWeeks((-n - 1).toLong()).takeIf { it.month == month.month }
    }

    private fun dayOfMonth(month: ZonedDateTime, day: Int): ZonedDateTime? {
        val length = month.toLocalDate().lengthOfMonth()
        val d = if (day < 0) length + day + 1 else day
        return if (d in 1..length) month.withDayOfMonth(d) else null
    }

    private fun event(props: List<Triple<String, Map<String, String>, String>>, zone: ZoneId): Vevent? {
        fun one(name: String) = props.firstOrNull { it.first == name }
        if (one("STATUS")?.third == "CANCELLED") return null
        val startProp = one("DTSTART") ?: return null
        val (start, allDay) = time(startProp.third, startProp.second, zone) ?: return null
        val end = one("DTEND")?.let { time(it.third, it.second, zone)?.first }
            ?: one("DURATION")?.let { d -> duration(d.third)?.let { start.plus(it) } }
            ?: if (allDay) start.plusDays(1) else start
        val except = props.filter { it.first == "EXDATE" }.flatMap { p ->
            p.third.split(",").mapNotNull { time(it, p.second, zone)?.first?.toInstant() }
        }.toSet()
        return Vevent(
            uid = one("UID")?.third ?: "${start.toEpochSecond()}",
            title = one("SUMMARY")?.third?.let(::unescape)?.trim().orEmpty().ifEmpty { "Busy" },
            start = start, end = end, allDay = allDay,
            place = one("LOCATION")?.third?.let(::unescape)?.trim()?.ifEmpty { null },
            rule = one("RRULE")?.third?.split(";")?.mapNotNull { kv -> kv.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap(),
            except = except,
            replaces = one("RECURRENCE-ID")?.let { time(it.third, it.second, zone)?.first?.toInstant() }
        )
    }

    /** "NAME;PARAM=x:value", with ':' inside quoted params left alone. */
    private fun property(line: String): Triple<String, Map<String, String>, String>? {
        var quoted = false
        val colon = line.indices.firstOrNull { i ->
            if (line[i] == '"') quoted = !quoted
            !quoted && line[i] == ':'
        } ?: return null
        val head = line.substring(0, colon).split(";")
        val params = head.drop(1).mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1].trim('"') } }.toMap()
        return Triple(head[0].uppercase(), params, line.substring(colon + 1))
    }

    /** A date-time in UTC, a named zone, or floating (the phone's); or a whole day. */
    private fun time(value: String, params: Map<String, String>, zone: ZoneId): Pair<ZonedDateTime, Boolean>? = runCatching {
        val v = value.trim()
        if (params["VALUE"] == "DATE" || v.length == 8) {
            return@runCatching LocalDate.parse(v, BASIC_DATE).atStartOfDay(zone) to true
        }
        val local = LocalDateTime.parse(v.removeSuffix("Z"), BASIC_TIME)
        val at = when {
            v.endsWith("Z") -> local.atZone(ZoneId.of("UTC")).withZoneSameInstant(zone)
            params["TZID"] != null -> local.atZone(runCatching { ZoneId.of(params["TZID"]) }.getOrDefault(zone)).withZoneSameInstant(zone)
            else -> local.atZone(zone)
        }
        at to false
    }.getOrNull()

    private fun duration(value: String): Duration? = runCatching {
        val weeks = Regex("^P(\\d+)W$").matchEntire(value)?.groupValues?.get(1)?.toLong()
        if (weeks != null) Duration.ofDays(weeks * 7) else Duration.parse(value)
    }.getOrNull()

    private fun unescape(s: String) = s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    /** Periods looked at for one rule at most, against a broken one. */
    private const val PERIODS = 200_000
    private val BASIC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val BASIC_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DAY = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY
    )
}
