// app/src/main/java/com/domain/app/voice/Questions.kt
package com.domain.app.voice

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** What a question is about: a call ("call dad") or a thing ("coffee"). */
data class Subject(val call: Boolean, val words: String)

data class Period(val start: Instant, val end: Instant, val phrase: String)

sealed class Question {
    /** "When did I last call Dad?" */
    data class Last(val subject: Subject) : Question()

    /** "How many coffees this week?", "Did I have a coffee today?" */
    data class Count(val subject: Subject, val period: Period, val yesNo: Boolean = false, val amount: Boolean = false) : Question()

    /** "What did I eat yesterday?" [kind] null means everything. */
    data class What(val kind: String?, val period: Period) : Question()
}

/**
 * Spoken questions about the record, answered from the entries on the
 * phone, in words (docs/anthemos-2.md, build order 4). Rules for now; the
 * person's own server can take harder questions later.
 */
object Questions {

    fun parse(raw: String, now: ZonedDateTime): Question? {
        val text = raw.lowercase()
            .replace(Regex("[?.!,]"), " ")
            .replace(Regex("^\\s*(hey|ok|okay)?\\s*(anthemos)?\\s*"), "")
            .replace(Regex("\\s+"), " ").trim()

        LAST.matchEntire(text)?.let { m -> return Question.Last(subject(m.groupValues[1])) }

        WHAT.matchEntire(text)?.let { m ->
            val kind = when (m.groupValues[1]) {
                "eat", "eaten" -> "food"
                "drink", "drunk" -> "drink"
                else -> null
            }
            return Question.What(kind, period(m.groupValues[2], now) ?: today(now))
        }

        HOW.matchEntire(text)?.let { m ->
            val (rest, period) = splitPeriod(m.groupValues[2], now)
            val amount = m.groupValues[1] == "much"
            val times = Regex("^times (?:have|did|do|has) (?:i|we) (.+)$").matchEntire(rest)
            val subject = if (times != null) {
                subject(times.groupValues[1])
            } else {
                Subject(false, rest.replace(Regex("\\s+(have|did|do|has|had|was)\\b.*$"), "").trim())
            }
            return Question.Count(subject, period ?: today(now), amount = amount)
        }

        DID.matchEntire(text)?.let { m ->
            val (rest, period) = splitPeriod(m.groupValues[1], now)
            return Question.Count(subject(rest), period ?: today(now), yesNo = true)
        }
        return null
    }

    fun answer(
        question: Question,
        entries: List<Entry>,
        now: ZonedDateTime,
        /** A contact's name for a word like "dad", from what was taught. */
        alias: (String) -> String? = { null }
    ): String = when (question) {
        is Question.Last -> {
            val match = entries.sortedByDescending { it.time }.firstOrNull { entry ->
                entry.items.any { matches(it, question.subject, alias) } ||
                    (!question.subject.call && containsWords(entry.words, question.subject.words))
            }
            when {
                match != null && question.subject.call -> {
                    val who = match.items.first { matches(it, question.subject, alias) }.label
                    "You last called $who ${whenSaid(match.time, now)}."
                }
                match != null -> "Last ${question.subject.words.ifEmpty { "time" }}: ${whenSaid(match.time, now)}."
                question.subject.call -> "I don't have a call to ${question.subject.words.ifEmpty { "anyone" }} yet. I only know calls made through Anthemos."
                else -> "I don't have a record of ${question.subject.words} yet."
            }
        }

        is Question.Count -> {
            val inPeriod = entries.filter { it.time >= question.period.start && it.time < question.period.end }
            val found = inPeriod.flatMap { e -> e.items.filter { matches(it, question.subject, alias) }.map { e to it } }
            val count = found.sumOf { it.second.count }
            val thing = question.subject.words.ifEmpty { "entries" }
            val single = singular(thing)
            when {
                question.yesNo && found.isEmpty() -> "No, not ${question.period.phrase}."
                question.yesNo -> {
                    val last = found.maxBy { it.first.time }.first.time
                    "Yes, ${whenSaid(last, now)}" + (if (count > 1) ", $count in all." else ".")
                }
                question.amount && single == "water" ->
                    "${found.sumOf { it.second.ml ?: 0 }} ml of water ${question.period.phrase}."
                question.subject.call && count == 0 ->
                    "You haven't called ${question.subject.words.ifEmpty { "anyone" }} ${question.period.phrase}."
                count == 0 -> "No ${plural(single)} ${question.period.phrase}."
                question.subject.call -> {
                    val who = found.firstOrNull()?.second?.label ?: question.subject.words.ifEmpty { "people" }
                    "You called $who ${times(count)} ${question.period.phrase}."
                }
                else -> "${count} ${if (count == 1) single else plural(single)} ${question.period.phrase}."
            }.replaceFirstChar { it.uppercase() }
        }

        is Question.What -> {
            val items = entries
                .filter { it.time >= question.period.start && it.time < question.period.end }
                .sortedBy { it.time }
                .flatMap { it.items }
                .filter { question.kind == null || it.kind == question.kind }
                .filter { it.kind != "call" || question.kind == null }
            if (items.isEmpty()) {
                "Nothing recorded ${question.period.phrase}."
            } else {
                val list = items.map { if (it.kind == "call") "called ${it.label}" else if (it.count > 1) "${it.count} ${it.label}" else it.label }
                    .distinct()
                "${question.period.phrase.replaceFirstChar { it.uppercase() }}: ${list.joinToString(", ")}."
            }
        }
    }

    // ---- matching

    private fun matches(item: Item, subject: Subject, alias: (String) -> String?): Boolean {
        if (subject.call) {
            if (item.kind != "call") return false
            val who = subject.words
            if (who.isEmpty()) return true
            val known = alias(who)?.lowercase()
            return item.said?.lowercase() == who || item.label.lowercase().contains(who) ||
                (known != null && item.label.lowercase() == known)
        }
        if (item.kind == "call") return false
        val wanted = singular(subject.words)
        if (wanted.isEmpty() || wanted == "entry" || wanted == "thing") return true
        val names = listOfNotNull(item.label, item.group, item.kind, item.meal, if (item.kind == "food") "meal" else null)
            .map { singular(it.lowercase()) }
        return names.any { it == wanted || Regex("\\b${Regex.escape(wanted)}\\b").containsMatchIn(it) }
    }

    private fun containsWords(words: String, wanted: String): Boolean =
        wanted.isNotEmpty() && Regex("\\b${Regex.escape(wanted)}\\b").containsMatchIn(words.lowercase())

    /** "call dad" -> call, "dad"; "have a coffee" -> "coffee". */
    private fun subject(phrase: String): Subject {
        val p = phrase.trim()
        CALL_VERB.find(p)?.let { m -> return Subject(true, p.substring(m.range.last + 1).trim()) }
        return Subject(false, p.replace(LEADING, "").trim())
    }

    private fun singular(word: String): String = word.trim().split(" ").joinToString(" ") { w ->
        when {
            w.length <= 3 -> w
            w.endsWith("ies") -> w.dropLast(3) + "y"
            w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") || w.endsWith("xes") -> w.dropLast(2)
            w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }
    }

    private fun plural(word: String): String = when {
        word.endsWith("s") -> word
        word.endsWith("ch") || word.endsWith("sh") || word.endsWith("x") -> word + "es"
        else -> word + "s"
    }

    private fun times(n: Int) = if (n == 1) "once" else if (n == 2) "twice" else "$n times"

    // ---- time

    private fun splitPeriod(rest: String, now: ZonedDateTime): Pair<String, Period?> {
        val m = PERIOD.find(rest) ?: return rest.trim() to null
        val without = rest.removeRange(m.range).replace(Regex("\\s+"), " ").trim()
        return without to period(m.value, now)
    }

    private fun today(now: ZonedDateTime) = day(now, 0, "today")

    private fun day(now: ZonedDateTime, back: Long, phrase: String): Period {
        val start = now.truncatedTo(ChronoUnit.DAYS).minusDays(back)
        return Period(start.toInstant(), start.plusDays(1).toInstant(), phrase)
    }

    private fun period(phrase: String, now: ZonedDateTime): Period? {
        val p = phrase.trim()
        if (p.isEmpty()) return null
        val midnight = now.truncatedTo(ChronoUnit.DAYS)
        val end = now.toInstant().plusSeconds(1)
        val monday = midnight.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val first = midnight.withDayOfMonth(1)
        Regex("(?:in )?the (?:last|past) (${Numbers.PATTERN}) (days?|weeks?|months?)").find(p)?.let { m ->
            val n = Numbers.parse(m.groupValues[1]) ?: return null
            val start = when {
                m.groupValues[2].startsWith("day") -> midnight.minusDays(n.toLong() - 1)
                m.groupValues[2].startsWith("week") -> now.minusWeeks(n.toLong())
                else -> now.minusMonths(n.toLong())
            }
            return Period(start.toInstant(), end, "in the last $n ${m.groupValues[2]}")
        }
        return when {
            p.contains("today") || p.contains("this morning") || p.contains("tonight") -> day(now, 0, "today")
            p.contains("yesterday") -> day(now, 1, "yesterday")
            p.contains("this week") -> Period(monday.toInstant(), end, "this week")
            p.contains("last week") -> Period(monday.minusWeeks(1).toInstant(), monday.toInstant(), "last week")
            p.contains("this month") -> Period(first.toInstant(), end, "this month")
            p.contains("last month") -> Period(first.minusMonths(1).toInstant(), first.toInstant(), "last month")
            p.contains("this year") -> Period(midnight.withDayOfYear(1).toInstant(), end, "this year")
            else -> null
        }
    }

    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val DATE = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)

    /** "today at 9:15 am", "yesterday at 6:40 pm", "on Tuesday at...", "on 3 November". */
    fun whenSaid(time: Instant, now: ZonedDateTime): String {
        val at = time.atZone(now.zone)
        val days = ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate())
        val clock = TIME.format(at).lowercase()
        return when {
            days == 0L -> "today at $clock"
            days == 1L -> "yesterday at $clock"
            days < 7 -> "on ${at.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} at $clock"
            else -> "on ${DATE.format(at)}"
        }
    }

    private val LAST = Regex("^when (?:did (?:i|we) last|was the last time (?:that )?(?:i|we)|have (?:i|we) last|did (?:i|we)|was (?:i|we) last) (.+)$")
    private val WHAT = Regex("^what (?:did|have) (?:i|we) (eat|eaten|have|had|drink|drunk|do|done)\\b(.*)$")
    private val HOW = Regex("^how (many|much) (.+)$")
    private val DID = Regex("^(?:did|have) (?:i|we) (.+)$")
    private val CALL_VERB = Regex("^(?:call|called|ring|rang|rung|phone|phoned|speak to|spoke to|spoken to|talk to|talked to|talk with|talked with)\\b")
    private val LEADING = Regex("^(?:(?:have|had|eat|ate|eaten|drink|drank|drunk|go for|went for|gone for|go to|went to|been to|do|did|done|a|an|some|any|the|my)\\s+)+")
    private val PERIOD = Regex(
        "\\b(today|this morning|tonight|yesterday|this week|last week|this month|last month|this year|" +
            "(?:in )?the (?:last|past) (?:${Numbers.PATTERN}) (?:days?|weeks?|months?))\\b"
    )
}
