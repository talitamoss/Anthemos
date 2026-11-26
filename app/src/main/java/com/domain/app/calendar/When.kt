// app/src/main/java/com/domain/app/calendar/When.kt
package com.domain.app.calendar

import com.domain.app.voice.Numbers
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Finds a day and a time in spoken words: "thursday at three", "tomorrow
 * at half past twelve", "on the twentieth", "in twenty minutes", "at 3 pm".
 * Hours one to seven without am or pm are taken as afternoon.
 */
object When {

    data class Found(
        val date: LocalDate?,
        val time: LocalTime?,
        /** The words with the day and time taken out. */
        val rest: String,
        /** "in twenty minutes": an exact moment. */
        val exact: ZonedDateTime? = null
    )

    fun find(text: String, now: ZonedDateTime): Found {
        var rest = " $text "
        var date: LocalDate? = null
        var time: LocalTime? = null
        val today = now.toLocalDate()

        fun take(m: MatchResult) {
            rest = rest.replaceRange(m.range, " ")
        }

        // "in twenty minutes", "in two hours", "in three days"
        IN.find(rest)?.let { m ->
            val n = Numbers.parse(m.groupValues[1])
            if (n != null) {
                take(m)
                val unit = m.groupValues[2]
                return when {
                    unit.startsWith("min") -> Found(null, null, tidy(rest), now.plusMinutes(n.toLong()))
                    unit.startsWith("hour") -> Found(null, null, tidy(rest), now.plusHours(n.toLong()))
                    unit.startsWith("day") -> Found(today.plusDays(n.toLong()), timeIn(rest)?.also { (_, r) -> rest = r }?.first, tidy(rest))
                    else -> Found(today.plusWeeks(n.toLong()), timeIn(rest)?.also { (_, r) -> rest = r }?.first, tidy(rest))
                }
            }
        }

        DAY_AFTER.find(rest)?.let { take(it); date = today.plusDays(2) }
        if (date == null) TOMORROW.find(rest)?.let { take(it); date = today.plusDays(1) }
        if (date == null) TODAY.find(rest)?.let { m ->
            take(m)
            date = today
            if (m.value.contains("tonight")) time = LocalTime.of(19, 0)
        }
        if (date == null) WEEKDAY.find(rest)?.let { m ->
            take(m)
            val day = DayOfWeek.valueOf(m.groupValues[2].uppercase())
            date = today.with(TemporalAdjusters.next(day))
        }
        if (date == null) DAY_OF_MONTH.find(rest)?.let { m ->
            val day = ordinal(m.groupValues[1])
            val month = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { Month.valueOf(it.uppercase()) }
            if (day != null) {
                take(m)
                date = dateFor(day, month, today)
            }
        }
        if (date == null) MONTH_DAY.find(rest)?.let { m ->
            val day = ordinal(m.groupValues[2])
            if (day != null) {
                take(m)
                date = dateFor(day, Month.valueOf(m.groupValues[1].uppercase()), today)
            }
        }

        timeIn(rest)?.let { (t, r) ->
            time = t
            rest = r
        }
        if (time == null) PART_OF_DAY.find(rest)?.let { m ->
            take(m)
            time = when {
                m.value.contains("morning") -> LocalTime.of(9, 0)
                m.value.contains("lunch") -> LocalTime.of(12, 0)
                m.value.contains("afternoon") -> LocalTime.of(14, 0)
                m.value.contains("evening") -> LocalTime.of(18, 0)
                else -> LocalTime.of(20, 0)
            }
        }
        return Found(date, time, tidy(rest))
    }

    /** A moment already past, and the words without it. */
    data class Past(val at: ZonedDateTime, val rest: String)

    /**
     * When something already happened, if the words say: "at about 8 am",
     * "at half past seven", "yesterday at noon". Null when they don't, so it
     * happened now. A time still to come today is taken as the morning one
     * ("at seven", said at nine, is 7 am).
     */
    fun past(text: String, now: ZonedDateTime): Past? {
        // "About 8 am" is a time; "about 8 hours" isn't.
        var rest = ROUGHLY.replace(" ${text.lowercase()} ") { m ->
            val after = text.lowercase().let { " $it " }.substring(m.range.last + 1)
            if (AMOUNT.containsMatchIn(after)) m.value else "at "
        }
        var days = 0L
        DAY_BEFORE_YESTERDAY.find(rest)?.let { rest = rest.replaceRange(it.range, " "); days = 2 }
        if (days == 0L) YESTERDAY.find(rest)?.let { rest = rest.replaceRange(it.range, " "); days = 1 }
        val found = timeIn(rest)
        if (found == null) {
            return if (days > 0) Past(now.minusDays(days), tidy(rest)) else null
        }
        rest = found.second.replace(SOFTENERS, " ")
        var at = ZonedDateTime.of(now.toLocalDate().minusDays(days), found.first, now.zone)
        if (at.isAfter(now) && found.first.hour >= 12) at = at.minusHours(12)
        if (at.isAfter(now)) return null
        return Past(at, tidy(rest))
    }

    /** A time, and the words without it. */
    private fun timeIn(text: String): Pair<LocalTime, String>? {
        NOON.find(text)?.let { m ->
            val t = if (m.value.contains("midnight")) LocalTime.MIDNIGHT else LocalTime.NOON
            return t to text.replaceRange(m.range, " ")
        }
        PAST.find(text)?.let { m ->
            val hour = Numbers.parse(m.groupValues[3]) ?: return@let
            val minutes = when (m.groupValues[1]) {
                "half" -> 30
                "quarter" -> if (m.groupValues[2] == "to") -15 else 15
                else -> Numbers.parse(m.groupValues[1])?.let { if (m.groupValues[2] == "to") -it else it } ?: return@let
            }
            val base = LocalTime.of(adjust(hour, m.groupValues[4]) % 24, 0)
            return base.plusMinutes(minutes.toLong()) to text.replaceRange(m.range, " ")
        }
        for (m in CLOCK.findAll(text)) {
            val (at, h, digits, words, suffix) = m.destructured
            // A bare number needs "at" or am/pm to be a time ("two coffees" isn't).
            if (at.isBlank() && suffix.isBlank() && digits.isBlank()) continue
            val hour = Numbers.parse(h)?.takeIf { it in 0..23 } ?: continue
            if (h == "a" || h == "an") continue
            val minute = when {
                digits.isNotBlank() -> digits.toInt()
                words.isNotBlank() -> Numbers.parse(words.removePrefix("o ").trim()) ?: continue
                else -> 0
            }
            if (minute !in 0..59) continue
            return LocalTime.of(adjust(hour, suffix), minute) to text.replaceRange(m.range, " ")
        }
        return null
    }

    private fun adjust(hour: Int, suffix: String): Int {
        val s = suffix.replace(" ", "")
        return when {
            s.startsWith("pm") || s.contains("afternoon") || s.contains("evening") || s.contains("night") ->
                if (hour < 12) hour + 12 else hour
            s.startsWith("am") || s.contains("morning") -> if (hour == 12) 0 else hour
            hour in 1..7 -> hour + 12
            else -> hour
        }
    }

    private fun dateFor(day: Int, month: Month?, today: LocalDate): LocalDate? {
        val m = month ?: today.month
        var d = runCatching { LocalDate.of(today.year, m, day) }.getOrNull() ?: return null
        if (d.isBefore(today)) {
            d = if (month == null) runCatching { d.plusMonths(1) }.getOrNull() ?: return null else d.plusYears(1)
        }
        return d
    }

    private fun ordinal(word: String): Int? {
        val w = word.trim().replace(Regex("(st|nd|rd|th)$"), "").takeIf { it.toIntOrNull() != null } ?: word.trim()
        w.toIntOrNull()?.let { return it.takeIf { d -> d in 1..31 } }
        val parts = w.split(Regex("[\\s-]+"))
        var total = 0
        for (p in parts) total += ORDINALS[p] ?: Numbers.parse(p) ?: return null
        return total.takeIf { it in 1..31 }
    }

    private fun tidy(text: String) = text.replace(Regex("\\s+"), " ").trim()

    private val ORDINALS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7,
        "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12, "thirteenth" to 13,
        "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18,
        "nineteenth" to 19, "twentieth" to 20, "thirtieth" to 30, "twenty" to 20, "thirty" to 30
    )
    private val ORDINAL = "(?:\\d{1,2}(?:st|nd|rd|th)?|(?:twenty|thirty)?[\\s-]?(?:${ORDINALS.keys.joinToString("|")}))"
    private const val MONTHS = "january|february|march|april|may|june|july|august|september|october|november|december"
    private const val SUFFIX = "(\\s+(?:a\\s?m|p\\s?m|in the morning|in the afternoon|in the evening|at night))?"

    private val ROUGHLY = Regex("\\b(?:at\\s+)?(?:about|around|roughly|approximately)\\s+(?=(?:\\d|${Numbers.PATTERN})\\b)")
    private val AMOUNT = Regex("^(?:${Numbers.PATTERN})(?:\\s+and a half)?\\s+(?:hours?|hrs?|minutes?|mins?|days?|weeks?|months?|years?|dollars?|bucks|times|glasses|cups|km|kilometres?|kilos?)\\b")
    private val DAY_BEFORE_YESTERDAY = Regex("\\b(?:the )?day before yesterday\\b")
    private val YESTERDAY = Regex("\\byesterday\\b")
    private val SOFTENERS = Regex("\\b(?:early|late) (?=this (?:morning|afternoon|evening)\\b)|\\bthis (?:morning|afternoon|evening)\\b|\\bin the (?:morning|afternoon|evening)\\b")
    private val IN = Regex("\\bin (?:an? )?(${Numbers.PATTERN})\\s*(minutes?|mins?|hours?|days?|weeks?)\\b")
    private val DAY_AFTER = Regex("\\bthe day after tomorrow\\b|\\bday after tomorrow\\b")
    private val TOMORROW = Regex("\\btomorrow\\b")
    private val TODAY = Regex("\\btoday\\b|\\btonight\\b|\\bthis (?=morning|afternoon|evening)")
    private val WEEKDAY = Regex("\\b(on |this |next )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")
    private val DAY_OF_MONTH = Regex("\\bon the ($ORDINAL)(?: of ($MONTHS))?\\b")
    private val MONTH_DAY = Regex("\\b(?:on )?($MONTHS) (?:the )?($ORDINAL)\\b")
    private val NOON = Regex("\\b(?:at )?(noon|midday|midnight)\\b")
    private val PAST = Regex("\\b(?:at )?(half|quarter|${Numbers.PATTERN}) (past|to) (${Numbers.PATTERN})\\b$SUFFIX")
    private val CLOCK = Regex(
        "\\b(at |by |from )?(\\d{1,2}|${Numbers.PATTERN})(?::(\\d{2})|\\s+(o (?:one|two|three|four|five|six|seven|eight|nine)|fifteen|thirty|forty five|forty|fifty five|fifty|forty-five|twenty|ten|five))?\\b$SUFFIX"
    )
    private val PART_OF_DAY = Regex("\\b(?:in the |this |at )?(morning|afternoon|evening|night|lunchtime|lunch time)\\b")
}
