// app/src/main/java/com/domain/app/voice/RuleParser.kt
package com.domain.app.voice

import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Works out what a sentence asks for, by rules (docs/anthemos-2.md, "Mind"):
 * undo, a call, teaching a name, a question, or else something to keep.
 * Whatever is kept keeps its exact words; [RuleReader] finds the items in it.
 *
 * Speech to text mishears, especially with accents ("call mum" comes out
 * "cool mum"), so commands accept what they are likely to be heard as.
 */
class RuleParser(
    private val now: () -> LocalTime = { LocalTime.now() },
    private val today: () -> ZonedDateTime = { ZonedDateTime.now() }
) {
    private val reader = RuleReader(now)

    fun parse(raw: String): VoiceRequest {
        val transcript = raw.trim()
        val text = transcript.lowercase()
            .replace(Regex("[.!?,]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val command = text.replace(POLITE, "").trim()

        if (command in UNDO) return VoiceRequest.Undo(transcript)
        call(transcript, command)?.let { return it }
        teach(transcript, command)?.let { return it }
        Questions.parse(text, today())?.let { return VoiceRequest.Ask(transcript, it) }

        val note = NOTE.find(command)
        val items = if (note != null) emptyList() else reader.read(transcript)
        return VoiceRequest.Record(transcript, items, reader.reply(items))
    }

    private fun call(transcript: String, text: String): VoiceRequest.Call? {
        CALL.matchEntire(text)?.let { m ->
            val name = m.groupValues[2].trim()
            if (name.isEmpty()) return null
            return VoiceRequest.Call(transcript, name, sure = m.groupValues[1] !in MISHEARD)
        }
        GIVE.matchEntire(text)?.let { m -> return VoiceRequest.Call(transcript, m.groupValues[1].trim()) }
        return null
    }

    private fun teach(transcript: String, text: String): VoiceRequest.Teach? {
        val m = TEACH.matchEntire(text) ?: return null
        return VoiceRequest.Teach(transcript, m.groupValues[1], m.groupValues[2].trim())
    }

    companion object {
        private val UNDO = setOf("undo", "undo that", "take that back", "delete that", "scratch that", "cancel that", "remove that")
        private val POLITE = Regex("^(?:(?:hey|ok|okay)\\s+)?(?:anthemos\\s+)?(?:please\\s+|can you\\s+|could you\\s+|would you\\s+|i want to\\s+|i'd like to\\s+|i need to\\s+)*")

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

        private val NOTE = Regex("^(?:take a note|make a note|note that|note|remember that|remember)\\b")
    }
}
