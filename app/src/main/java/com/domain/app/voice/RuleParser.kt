// app/src/main/java/com/domain/app/voice/RuleParser.kt
package com.domain.app.voice

import com.domain.app.calendar.When
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Works out what a sentence asks for, by rules (docs/anthemos-2.md, "Mind").
 * Which commands there are, and the order they're tried in, is [Commands];
 * this holds the reading shared by several of them. Whatever is kept keeps
 * its exact words; [RuleReader] finds the items in it.
 *
 * Speech to text mishears, especially with accents ("call mum" comes out
 * "cool mum"), so commands accept what they are likely to be heard as.
 */
class RuleParser(
    private val now: () -> LocalTime = { LocalTime.now() },
    private val today: () -> ZonedDateTime = { ZonedDateTime.now() }
) {
    private val reader = RuleReader(now)

    fun parse(raw: String): VoiceRequest = match(raw).request

    /** The command a sentence was, and what it asks for. */
    fun match(raw: String): Match {
        val transcript = raw.trim()
        val text = transcript.lowercase()
            .replace(Regex("[.!?,]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val command = text.replace(POLITE, "").trim()
        return Commands.match(Said(transcript, text, command, today()), this)
    }

    /**
     * Something to keep, at the time said ("a coffee at about 8 am"). Where
     * parts carry different times ("woke up at 5:30, having a coffee now"),
     * each is its own entry: a time covers its own part and the untimed
     * parts just before it; parts after the last time happened now.
     */
    internal fun record(transcript: String): VoiceRequest.Record {
        val now = today()
        val seps = SPLIT.findAll(transcript).toList()
        val starts = listOf(0) + seps.map { it.range.last + 1 }
        val ends = seps.map { it.range.first } + transcript.length
        val clauses = starts.indices.map { transcript.substring(starts[it], ends[it]) }
        val times = clauses.map { When.past(it, now) }

        val groups = mutableListOf<IntRange>()
        var from = 0
        times.forEachIndexed { i, past ->
            if (past != null) {
                groups += from..i
                from = i + 1
            }
        }
        if (from < clauses.size) groups += from until clauses.size

        val parts = groups.map { g ->
            val at = times[g.last]?.at
            val words = transcript.substring(starts[g.first], ends[g.last]).trim().trim(',').trim()
            val readable = g.joinToString(", ") { i -> times[i]?.rest ?: clauses[i] }
            val items = RuleReader { at?.toLocalTime() ?: now() }.read(readable)
            VoiceRequest.Record(words, items, said(items, at, now), at)
        }
        if (parts.size == 1) return parts[0].copy(transcript = transcript)
        val reply = "Logged " + parts.joinToString("; ") { it.reply.removePrefix("Logged ") }
        return VoiceRequest.Record(transcript, parts.flatMap { it.items }, reply, parts.last().at, parts)
    }

    /** "Logged coffee at 8:00 am". */
    private fun said(items: List<Item>, at: ZonedDateTime?, now: ZonedDateTime): String {
        val what = if (items.isEmpty()) "Noted" else reader.reply(items)
        at ?: return what
        val day = if (at.toLocalDate() == now.toLocalDate()) "" else if (at.toLocalDate() == now.toLocalDate().minusDays(1)) " yesterday" else " on ${at.dayOfWeek.name.lowercase()}"
        return "$what at ${CLOCK.format(at).lowercase()}$day"
    }

    internal fun call(transcript: String, text: String): VoiceRequest.Call? {
        CALL.matchEntire(text)?.let { m ->
            val name = m.groupValues[2].trim()
            if (name.isEmpty()) return null
            return VoiceRequest.Call(transcript, name, sure = m.groupValues[1] !in MISHEARD)
        }
        GIVE.matchEntire(text)?.let { m -> return VoiceRequest.Call(transcript, m.groupValues[1].trim()) }
        return null
    }

    internal fun teach(transcript: String, text: String): VoiceRequest.Teach? {
        val m = TEACH.matchEntire(text) ?: return null
        return VoiceRequest.Teach(transcript, m.groupValues[1], m.groupValues[2].trim())
    }

    companion object {
        internal val UNDO = setOf("undo", "undo that", "take that back", "delete that", "scratch that", "cancel that", "remove that")
        private val POLITE = Regex("^(?:(?:hey|ok|okay)\\s+)?(?:(?:anthemos|poly|polly|pauly|paulie)\\s+)?(?:please\\s+|can you\\s+|could you\\s+|would you\\s+|i want to\\s+|i'd like to\\s+|i need to\\s+)*")

        // How "call" gets heard. These alone don't make a call: if no
        // contact matches, the words are kept instead.
        private val MISHEARD = setOf("coal", "cool", "col", "cole", "kohl", "cold", "caul", "core", "cull", "kol", "cal", "kool")
        private val CALL = Regex(
            "^(call|ring|phone|dial|${MISHEARD.joinToString("|")})(?:\\s+up)?\\s+(.+?)(?:\\s+(?:please|now|for me|on (?:the )?(?:mobile|phone|speaker)))?$"
        )
        private val GIVE = Regex("^give\\s+(.+?)\\s+a\\s+(?:call|ring|bell|buzz)(?:\\s+.*)?$")

        // Only family and similar words, so "dinner is pasta" stays a note.
        private val RELATIONS = "mum|mom|mother|mama|mam|dad|father|papa|pop|nan|nana|gran|grandma|grandpa|granny|" +
            "brother|sister|partner|wife|husband|boyfriend|girlfriend|son|daughter|aunt|auntie|uncle|cousin|boss|doctor"
        private val TEACH = Regex("^(?:my\\s+)?($RELATIONS)(?:'s name is|'s called| is called| is named| is)\\s+(.+)$")

        private val SPLIT = Regex("\\s*(?:[,;]|\\band then\\b|\\band\\b|\\bthen\\b|\\balso\\b|\\bplus\\b)\\s*", RegexOption.IGNORE_CASE)
        private val CLOCK = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH)
        // Only these keep words as a note; anything else should be something specific.
        internal val NOTE = Regex("^(?:take a note|make a note|add a note|note to self|note that|note|remember that|remember|write down|write this down|jot down|journal|diary entry|dear diary)\\b")
    }
}
