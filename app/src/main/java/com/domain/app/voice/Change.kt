// app/src/main/java/com/domain/app/voice/Change.kt
package com.domain.app.voice

import com.domain.app.calendar.When
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Changing something already kept, by saying so (docs/anthemos-2.md,
 * "Changing an entry"): "change the coffee to tea", "it was tea, not
 * coffee", "make that two coffees", "delete the coffee", "the walk was at
 * eight", "that was yesterday".
 *
 * The words are the record, so a change edits the words and reads them
 * again. [sure] is false for forms that might just be something to keep
 * ("the meeting was at three"): if nothing matches, the words are kept.
 */
sealed class Change {
    abstract val sure: Boolean

    /** [from] null: whatever the new words replace in the last entry. */
    data class Words(val from: String?, val to: String, override val sure: Boolean = true) : Change()

    /** [what] null: the last entry. */
    data class Remove(val what: String?, override val sure: Boolean = true) : Change()

    /** [what] null: the last entry. [daysBack] null keeps the entry's day; [time] null keeps its time. */
    data class Time(
        val what: String?,
        val daysBack: Long?,
        val time: LocalTime?,
        override val sure: Boolean = true
    ) : Change()

    companion object {
        /** [command] is lowercased, without punctuation or "please". */
        fun parse(command: String, now: ZonedDateTime): Change? {
            val opener = OPENER.find(command)
            val corrected = opener != null
            val c = if (opener != null) command.substring(opener.range.last + 1).trim() else command
            if (c.isEmpty()) return null

            REMOVE.matchEntire(c)?.let { m -> return Remove(target(m.groupValues[1])) }

            MOVE.matchEntire(c)?.let { m ->
                whenPhrase(m.groupValues[2], now)?.let { (days, time) -> return Time(target(m.groupValues[1]), days, time) }
            }
            WAS.matchEntire(c)?.let { m ->
                whenPhrase(m.groupValues[2], now)?.let { (days, time) ->
                    val what = target(m.groupValues[1])
                    return Time(what, days, time, sure = what == null)
                }
            }

            SWAP.matchEntire(c)?.let { m ->
                val to = m.groupValues[2].trim()
                return Words(target(m.groupValues[1]), to)
            }
            MAKE.matchEntire(c)?.let { m -> return Words(null, m.groupValues[1].trim()) }
            // "No, tea not coffee", "it was tea, not coffee"; never a plain "I'm not hungry".
            NOT.matchEntire(c)?.let { m ->
                val to = m.groupValues[2].trim()
                if ((corrected || m.groupValues[1].isNotEmpty()) && to.split(" ").size <= 4) {
                    return Words(target(m.groupValues[3]) ?: return null, to)
                }
            }
            MEANT.matchEntire(c)?.let { m -> return Words(null, m.groupValues[1].trim()) }
            if (corrected) IT_WAS.matchEntire(c)?.let { m -> return Words(null, m.groupValues[1].trim(), sure = false) }
            return null
        }

        /** "the coffee" -> "coffee"; "that", "it", "the last one" -> null (the last entry). */
        private fun target(words: String): String? {
            val t = words.trim()
                .replace(Regex("^(?:the time (?:of|for)\\s+)"), "")
                .replace(Regex("^(?:the|my|that|this|a|an)\\s+"), "")
                .replace(Regex("\\s+(?:entry|one|i had|i logged|bit)$"), "")
                .trim()
            return t.takeIf { it !in LAST }
        }

        /**
         * "at eight", "yesterday", "this morning", "last night at ten",
         * "on monday": how many days back, and a time. Null when the words
         * are anything else, so "the bus was late" isn't a change.
         */
        internal fun whenPhrase(words: String, now: ZonedDateTime): Pair<Long?, LocalTime?>? {
            var rest = " ${words.trim()} "
            var days: Long? = null
            var time: LocalTime? = null
            fun cut(r: Regex): MatchResult? = r.find(rest)?.also { rest = rest.replaceRange(it.range, " ") }

            when {
                cut(DAY_BEFORE) != null -> days = 2
                cut(LAST_NIGHT) != null -> { days = 1; time = LocalTime.of(21, 0) }
                cut(YESTERDAY) != null -> days = 1
                cut(TODAY) != null -> days = 0
                else -> cut(WEEKDAY)?.let { m ->
                    val day = DayOfWeek.valueOf(m.groupValues[1].uppercase())
                    days = ((now.dayOfWeek.value - day.value + 7) % 7).toLong().takeIf { it > 0 } ?: 7
                }
            }
            val found = When.find(rest, now)
            if (found.date != null || found.exact != null) return null
            found.time?.let { time = it }
            val leftover = found.rest.replace(FILLER, " ").trim()
            if (leftover.isNotEmpty() || (days == null && time == null)) return null
            return days to time
        }

        private val LAST = setOf("", "that", "it", "this", "last", "last one", "last entry", "time", "the time", "that one")
        private const val DET = "(?:a|an|the|my|that|this)"
        private val OPENER = Regex("^(?:(?:no|nope|oh|oops|sorry|wait|actually|correction|hang on)\\b\\s*)+")
        private val REMOVE = Regex("^(?:delete|remove|take out|take off|get rid of|scratch|cross off)\\s+(.+?)(?:\\s+from (?:today|earlier|before|the list))?$")
        private val MOVE = Regex("^(?:move|change|set|put)\\s+(.+?)\\s+to\\s+(.+)$")
        private val WAS = Regex("^((?:$DET\\s+)?[a-z' ]{1,40}?)\\s+(?:was|were|happened)\\s+(.+)$")
        private val SWAP = Regex("^(?:change|swap|switch|replace|correct|fix)\\s+(.+?)\\s+(?:to|into|for|with)\\s+(.+)$")
        private val MAKE = Regex("^(?:make that|change that|change it)\\s+(?!to\\b)(.+)$")
        private val NOT = Regex("^(it was\\s+|that was\\s+|i had\\s+|it's\\s+|i meant\\s+|i said\\s+)?(?:a\\s+|an\\s+)?(.+?)\\s+not\\s+(?:a\\s+|an\\s+)?(.+)$")
        private val MEANT = Regex("^(?:i meant|i said|i meant to say)\\s+(.+)$")
        private val IT_WAS = Regex("^(?:it was|that was|i had)\\s+(.+)$")

        private val DAY_BEFORE = Regex("\\b(?:the )?day before yesterday\\b")
        private val LAST_NIGHT = Regex("\\blast night\\b")
        private val YESTERDAY = Regex("\\byesterday\\b")
        private val TODAY = Regex("\\btoday\\b|\\bthis (?=morning|afternoon|evening)")
        private val WEEKDAY = Regex("\\b(?:on |last )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")
        private val FILLER = Regex("\\b(?:at|around|about|on|in|the|for|earlier|actually|really)\\b")
    }
}

/** What a [Change] does to the entries. Pure, so it can be tested off the phone. */
object Changes {

    sealed class Outcome {
        data class Save(val entry: Entry, val reply: String) : Outcome()
        data class Delete(val entry: Entry, val reply: String) : Outcome()
        data class Say(val reply: String) : Outcome()
        /** More than one of today's entries fits: ask which, then [applyTo] the one picked. */
        data class Choose(val change: Change, val options: List<Entry>, val question: String) : Outcome()
        /** Not a change after all: keep the words as an entry. */
        object Keep : Outcome()
    }

    /**
     * [entries] are the ones that can be changed, newest first; [last] is
     * the one just kept, or the newest.
     */
    fun apply(change: Change, entries: List<Entry>, last: Entry?, now: ZonedDateTime): Outcome {
        val what = when (change) {
            is Change.Words -> change.from
            is Change.Remove -> change.what
            is Change.Time -> change.what
        }
        if (what != null) {
            // Several of today's fit ("the coffee" with three coffees): ask rather than guess.
            val today = entries.filter { it.time.atZone(now.zone).toLocalDate() == now.toLocalDate() && fits(it, what) }
            if (today.size > 1) {
                val options = today.take(3).sortedBy { it.time }
                val times = options.map { clock(it, now) }
                val list = times.dropLast(1).joinToString(", ") + " or " + times.last()
                return Outcome.Choose(change, options, "Which $what, $list?")
            }
        }
        val target = if (what == null) last else find(entries, what)
        if (target == null) {
            return when {
                !change.sure -> Outcome.Keep
                what == null -> Outcome.Say("There's nothing to change yet")
                else -> Outcome.Say("I couldn't find $what")
            }
        }
        return applyTo(change, target, now)
    }

    fun applyTo(change: Change, target: Entry, now: ZonedDateTime): Outcome {
        return when (change) {
            is Change.Remove -> remove(target, change.what, now)
            is Change.Time -> move(target, change, now)
            is Change.Words -> words(target, change, now)
        }
    }

    private fun remove(target: Entry, what: String?, now: ZonedDateTime): Outcome {
        if (what == null) return Outcome.Delete(target, "Taken back")
        val words = cut(target.words, what)
        val items = if (words != target.words) reread(target, words, now) else target.items.filterNot { matches(it, what) }
        if (items.isEmpty() || words.isBlank()) return Outcome.Delete(target, "Removed $what")
        return Outcome.Save(target.copy(words = words, items = items), "Removed $what")
    }

    private fun move(target: Entry, change: Change.Time, now: ZonedDateTime): Outcome {
        val old = target.time.atZone(now.zone)
        val date = change.daysBack?.let { now.toLocalDate().minusDays(it) } ?: old.toLocalDate()
        var at = ZonedDateTime.of(date, change.time ?: old.toLocalTime(), now.zone)
        // "Breakfast was at seven": seven in the morning, not the evening still to come.
        if (at.isAfter(now) && change.time != null && change.time.hour >= 12) at = at.minusHours(12)
        if (at.isAfter(now)) return Outcome.Say("That hasn't happened yet")
        val moved = target.copy(time = at.toInstant())
        val name = target.items.firstOrNull()?.label ?: "it"
        return Outcome.Save(moved.copy(items = reread(moved, moved.words, now)), "Moved $name to ${describe(at, now)}")
    }

    private fun words(target: Entry, change: Change.Words, now: ZonedDateTime): Outcome {
        val to = change.to.replace(Regex("^(?:it was|that was|i had|to)\\s+"), "").trim()
        val words = if (change.from != null) {
            swap(target.words, change.from, to)
        } else {
            replaceLast(target, to, now) ?: return if (change.sure) Outcome.Say("Which part? Say change coffee to tea") else Outcome.Keep
        }
        if (words == target.words) {
            return if (change.sure) Outcome.Say("I couldn't find ${change.from ?: "that"} to change") else Outcome.Keep
        }
        val items = reread(target, words, now)
        val reply = if (items.isEmpty()) "Changed" else "Changed to " + RuleReader().reply(items).removePrefix("Logged ")
        return Outcome.Save(target.copy(words = words, items = items), reply)
    }

    /** "Make that two coffees": the new words take the place of what they're like in the last entry. */
    private fun replaceLast(target: Entry, to: String, now: ZonedDateTime): String? {
        // A bare number: how many of the one thing.
        Numbers.parse(to)?.let { n ->
            val item = target.items.singleOrNull { it.kind == "drink" || it.kind == "food" } ?: return null
            return swap(target.words, item.label, if (n > 1) "$to ${item.label}s" else "$to ${item.label}")
        }
        val reader = RuleReader { target.time.atZone(now.zone).toLocalTime() }
        val fresh = reader.read(to)
        if (fresh.isEmpty()) return null
        val used = mutableSetOf<Item>()
        var words = target.words
        if (fresh.size == 1) {
            val old = target.items.firstOrNull { it.kind == fresh[0].kind } ?: return wholeOrNull(target, to)
            return swap(words, old.label, to)
        }
        for (item in fresh) {
            val old = target.items.firstOrNull { it.kind == item.kind && it !in used } ?: continue
            used += old
            words = swap(words, old.label, item.label)
        }
        return if (used.isEmpty()) wholeOrNull(target, to) else words
    }

    /** With nothing alike to replace, the new words replace the whole entry, if it was about one thing. */
    private fun wholeOrNull(target: Entry, to: String) = if (target.items.size <= 1) to else null

    private fun find(entries: List<Entry>, what: String): Entry? =
        entries.firstOrNull { e -> e.items.any { matches(it, what) } }
            ?: entries.firstOrNull { e -> mentions(e, what) }

    private fun fits(entry: Entry, what: String) = entry.items.any { matches(it, what) } || mentions(entry, what)

    private fun mentions(entry: Entry, what: String) =
        Regex("\\b${label(what)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(entry.words)

    /**
     * The answer to "Which coffee, 8:00 am or 11:00 am?": a time ("eight",
     * "the 11 one"), or first / second / last / earlier / later. [options]
     * are oldest first.
     */
    fun pick(answer: String, options: List<Entry>, now: ZonedDateTime): Entry? {
        val text = answer.lowercase().replace(Regex("[.!?,]"), " ").replace(Regex("\\s+"), " ").trim()
        ORDINALS.entries.firstOrNull { (word, _) -> Regex("\\b$word\\b").containsMatchIn(text) }?.let { (_, i) ->
            return if (i < 0) options.lastOrNull() else options.getOrNull(i)
        }
        val hours = options.map { it.time.atZone(now.zone).toLocalTime() }
        val said = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\b").find(text)?.let { it.groupValues[1].toInt() * 60 + (it.groupValues[2].toIntOrNull() ?: 0) }
            ?: Regex("\\b(${Numbers.PATTERN})\\b").findAll(text).firstNotNullOfOrNull { Numbers.parse(it.value) }?.takeIf { it in 1..24 }?.let { it * 60 }
            ?: return null
        // "Eight" could be morning or evening: whichever is nearer one of the options.
        val best = options.indices.minByOrNull { i ->
            val at = hours[i].hour * 60 + hours[i].minute
            minOf(Math.abs(at - said), Math.abs(at - (said + 12 * 60)))
        }
        return best?.let { options[it] }
    }

    private val ORDINALS = linkedMapOf(
        "first" to 0, "earliest" to 0, "earlier" to 0, "morning one" to 0,
        "second" to 1, "middle" to 1, "third" to 2,
        "last" to -1, "latest" to -1, "later" to -1, "most recent" to -1
    )

    private fun clock(entry: Entry, now: ZonedDateTime) = CLOCK.format(entry.time.atZone(now.zone)).lowercase()

    private fun matches(item: Item, what: String): Boolean {
        val w = singular(what)
        return singular(item.label) == w || item.group == w || item.meal == w || item.kind == w
    }

    private fun singular(word: String): String {
        val w = word.lowercase().trim()
        return when {
            Regex("(?:ch|sh|ss|x)es$").containsMatchIn(w) -> w.removeSuffix("es")
            w.endsWith("s") && !w.endsWith("ss") -> w.removeSuffix("s")
            else -> w
        }
    }

    /** A pattern for a label, with or without a plural. */
    private fun label(what: String) = Regex.escape(singular(what)) + "(?:es|s)?"

    private val QUANTITY = "(?:a|an|some|another|the|my|one|two|three|four|five|six|seven|eight|nine|ten|\\d+)"

    /** "I had two coffees" with coffee -> tea: "I had two teas". */
    internal fun swap(words: String, from: String, to: String): String {
        val m = Regex("\\b(?:($QUANTITY)\\s+)?(${label(from)})\\b", RegexOption.IGNORE_CASE).find(words) ?: return words
        val ownQuantity = Regex("^$QUANTITY\\s+", RegexOption.IGNORE_CASE).containsMatchIn(to)
        val quantity = m.groups[1]?.value?.takeIf { !ownQuantity }?.let { "$it " }.orEmpty()
        val wasPlural = m.groupValues[2].lowercase() != singular(m.groupValues[2])
        val plural = if (wasPlural && !ownQuantity && !to.endsWith("s") && quantity.isNotEmpty()) "s" else ""
        return words.replaceRange(m.range, quantity + to + plural)
    }

    /** "I had a coffee and toast" without coffee: "I had toast". */
    internal fun cut(words: String, what: String): String {
        val thing = "(?:$QUANTITY\\s+)?${label(what)}"
        val options = listOf(
            Regex("\\b$thing\\s*(?:,\\s*|\\s+)and\\s+", RegexOption.IGNORE_CASE),
            Regex("\\b$thing\\s*,\\s*", RegexOption.IGNORE_CASE),
            Regex("\\s*(?:,|\\band\\b)\\s*$thing\\b", RegexOption.IGNORE_CASE),
            Regex("\\b$thing\\b", RegexOption.IGNORE_CASE)
        )
        for (r in options) {
            val m = r.find(words) ?: continue
            return words.replaceRange(m.range, if (r == options[2]) "" else " ")
                .replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.])"), "$1").trim().trim(',').trim()
        }
        return words
    }

    /** Reads changed words again, at the entry's own time, keeping details added by follow-ups. */
    private fun reread(target: Entry, words: String, now: ZonedDateTime): List<Item> {
        val reader = RuleReader { target.time.atZone(now.zone).toLocalTime() }
        val context = if (target.items.any { it.kind == "sleep" }) "sleep" else null
        return reader.read(words, context).map { item ->
            val old = target.items.firstOrNull { it.kind == item.kind && it.label == item.label }
                ?: target.items.singleOrNull { it.kind == item.kind && (it.kind == "sleep" || it.kind == "activity") }
            item.copy(minutes = item.minutes ?: old?.minutes, amount = item.amount ?: old?.amount)
        }
    }

    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    private fun describe(at: ZonedDateTime, now: ZonedDateTime): String {
        val time = CLOCK.format(at).lowercase()
        val days = java.time.temporal.ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate())
        return when (days) {
            0L -> time
            1L -> "$time yesterday"
            else -> "$time on ${at.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}"
        }
    }
}
