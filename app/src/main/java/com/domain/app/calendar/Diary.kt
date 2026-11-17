// app/src/main/java/com/domain/app/calendar/Diary.kt
package com.domain.app.calendar

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** "What's on tomorrow?", "When's the dentist?", "Am I free Friday afternoon?" */
sealed class DiaryQuestion {
    data class WhatsOn(val from: Instant, val until: Instant, val phrase: String) : DiaryQuestion()
    data class WhenIs(val words: String) : DiaryQuestion()
    data class Free(val from: Instant, val until: Instant, val phrase: String) : DiaryQuestion()
}

/**
 * The calendar by voice, by rules: adding events and reminders, and
 * questions about what's on. Pure, so it can be tested.
 */
object Diary {

    /** An event or reminder from "add dentist thursday at three" or "remind me to call dad at five". */
    fun parseAdd(raw: String, now: ZonedDateTime): Event? {
        val text = clean(raw)
        val remind = REMIND.matchEntire(text)
        val body = remind?.groupValues?.get(1)
            ?: ADD.matchEntire(text)?.groupValues?.get(1)
            ?: HAVE.matchEntire(text)?.groupValues?.get(1)
            ?: return null

        val found = When.find(body, now)
        // "I've got" only counts with a day or time in it.
        if (remind == null && ADD.matchEntire(text) == null && found.date == null && found.time == null) return null
        if (found.exact == null && found.date == null && found.time == null) return null

        var rest = found.rest
        var minutes = 60L
        DURATION.find(rest)?.let { m ->
            val n = com.domain.app.voice.Numbers.parse(m.groupValues[1])
            if (n != null) {
                minutes = if (m.groupValues[2].startsWith("hour")) n * 60L else n.toLong()
                rest = rest.removeRange(m.range)
            }
        }
        var place: String? = null
        PLACE.find(rest)?.let { m ->
            place = m.groupValues[1].trim()
            rest = rest.removeRange(m.range)
        }
        val title = rest.replace(LEADING, "").replace(Regex("\\s+"), " ").trim()
            .replaceFirstChar { it.uppercase() }
            .ifEmpty { if (remind != null) "Reminder" else "Event" }

        val zone = now.zone
        val reminder = remind != null
        val allDay = found.exact == null && found.time == null && !reminder
        val start = when {
            found.exact != null -> found.exact
            else -> {
                val time = found.time ?: if (reminder) LocalTime.of(9, 0) else LocalTime.MIDNIGHT
                // A time with no day: today if still ahead, else tomorrow.
                val date = found.date ?: if (time.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
                date.atTime(time).atZone(zone)
            }
        }
        // "I've got..." is only an event if it's still to come.
        if (remind == null && ADD.matchEntire(text) == null && !(if (allDay) start.plusDays(1) else start).isAfter(now)) return null
        val end = if (allDay) start.plusDays(1) else if (reminder) start else start.plusMinutes(minutes)
        return Event(UUID.randomUUID().toString(), title, start.toInstant(), end.toInstant(), allDay, place, reminder, raw.trim())
    }

    fun parseQuestion(raw: String, now: ZonedDateTime): DiaryQuestion? {
        val text = clean(raw)
        WHEN_IS.matchEntire(text)?.let { m -> return DiaryQuestion.WhenIs(m.groupValues[1].replace(LEADING, "").trim()) }
        WHATS_ON.matchEntire(text)?.let { m -> return range(m.groupValues[1], now).let { (a, b, p) -> DiaryQuestion.WhatsOn(a, b, p) } }
        FREE.matchEntire(text)?.let { m -> return range(m.groupValues[1], now).let { (a, b, p) -> DiaryQuestion.Free(a, b, p) } }
        return null
    }

    fun answer(q: DiaryQuestion, events: List<Event>, now: ZonedDateTime): String = when (q) {
        is DiaryQuestion.WhatsOn -> {
            val on = events.filter { it.start < q.until && it.end > q.from }.sortedBy { it.start }
            if (on.isEmpty()) "Nothing on ${q.phrase}."
            else "${q.phrase.replaceFirstChar { it.uppercase() }}: " + on.joinToString("; ") { describe(it, now, withDay = q.phrase.startsWith("this") || q.phrase.startsWith("next")) } + "."
        }
        is DiaryQuestion.WhenIs -> {
            val words = q.words.lowercase().split(" ").filter { it.length > 2 }
            val next = events.filter { it.end >= now.toInstant() }.sortedBy { it.start }
                .firstOrNull { e -> words.isNotEmpty() && words.all { w -> e.title.lowercase().contains(w) } }
            if (next == null) "I can't see ${q.words} coming up." else "${next.title} is ${dayAndTime(next, now)}."
        }
        is DiaryQuestion.Free -> {
            val busy = events.filter { !it.reminder && it.start < q.until && it.end > q.from }.sortedBy { it.start }
            if (busy.isEmpty()) "Yes, you're free ${q.phrase}."
            else "Not quite: " + busy.joinToString("; ") { describe(it, now, withDay = false) } + "."
        }
    }

    /** "Dentist, Thursday 20 November at 3:00 pm" for confirming. */
    fun describe(e: Event, now: ZonedDateTime, withDay: Boolean = true): String {
        val what = e.title + (e.place?.let { " at ${it.replaceFirstChar { c -> c.uppercase() }}" } ?: "")
        return if (withDay) "$what, ${dayAndTime(e, now)}" else {
            if (e.allDay) "$what, all day" else "$what at ${TIME.format(e.start.atZone(now.zone)).lowercase()}"
        }
    }

    fun dayAndTime(e: Event, now: ZonedDateTime): String {
        val at = e.start.atZone(now.zone)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        val day = when (days) {
            0L -> "today"
            1L -> "tomorrow"
            else -> DAY.format(at)
        }
        return if (e.allDay) day else "$day at ${TIME.format(at).lowercase()}"
    }

    /** The span a question covers: "tomorrow", "friday afternoon", "this week". */
    private fun range(phrase: String, now: ZonedDateTime): Triple<Instant, Instant, String> {
        val p = phrase.trim()
        val midnight = now.truncatedTo(ChronoUnit.DAYS)
        if (p.contains("this week")) return Triple(now.toInstant(), midnight.plusDays(8 - now.dayOfWeek.value.toLong()).toInstant(), "this week")
        if (p.contains("next week")) {
            val monday = midnight.plusDays(8 - now.dayOfWeek.value.toLong())
            return Triple(monday.toInstant(), monday.plusDays(7).toInstant(), "next week")
        }
        val found = When.find(p, now)
        val date = found.date ?: now.toLocalDate()
        val dayStart = date.atStartOfDay(now.zone)
        val label = when {
            date == now.toLocalDate() -> "today"
            date == now.toLocalDate().plusDays(1) -> "tomorrow"
            else -> "on " + DAY.format(dayStart)
        }
        val (from, until, part) = when {
            p.contains("morning") -> Triple(dayStart.plusHours(6), dayStart.plusHours(12), " morning")
            p.contains("afternoon") -> Triple(dayStart.plusHours(12), dayStart.plusHours(17), " afternoon")
            p.contains("evening") || p.contains("tonight") -> Triple(dayStart.plusHours(17), dayStart.plusHours(23), " evening")
            found.time != null -> {
                val t = date.atTime(found.time).atZone(now.zone)
                Triple(t, t.plus(Duration.ofHours(1)), " at " + TIME.format(t).lowercase())
            }
            else -> Triple(dayStart, dayStart.plusDays(1), "")
        }
        val start = if (from.isBefore(now) && date == now.toLocalDate() && part.isEmpty()) now else from
        return Triple(start.toInstant(), until.toInstant(), label + part)
    }

    private fun clean(raw: String) = raw.lowercase()
        .replace(Regex("[?!,]|\\.(?!\\d)"), " ")
        .replace(Regex("^\\s*(?:hey |ok |okay )?(?:anthemos )?(?:please |can you |could you )*"), "")
        .replace(Regex("\\s+"), " ").trim()

    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

    private val REMIND = Regex("^remind me (?:to |about |that )?(.+)$")
    private val ADD = Regex("^(?:add|put|schedule|book|pop|make|create|set up)\\s+(?:an? )?(?:event |appointment |meeting )?(?:for |called )?(.+?)(?:\\s+(?:in|on|to|into) (?:my |the )?(?:calendar|diary|schedule))?$")
    private val HAVE = Regex("^(?:i've got|i have got|ive got|i have)\\s+(?!(?:had|been|done|just|already)\\b)(.+)$")
    private val DURATION = Regex("\\bfor (${com.domain.app.voice.Numbers.PATTERN}) (hours?|minutes?|mins?)\\b")
    private val PLACE = Regex("\\bat (?:the )?([a-z][a-z' ]{1,40}?)\\s*$")
    private val LEADING = Regex("^(?:(?:a|an|the|my|to|that|i|have|need to)\\s+)+")

    private val WHEN_IS = Regex("^when(?:'s| is| are) (?:my |the |our )?(.+?)(?: on)?$")
    private val WHATS_ON = Regex(
        "^(?:(?:what's|what is|whats) (?:on|happening|coming up|in my (?:calendar|diary))|what (?:have|do) i (?:got|have) on|what have i got)(.*)$"
    )
    private val FREE = Regex("^(?:am i|are we) (?:free|busy|available)(.*)$")
}
