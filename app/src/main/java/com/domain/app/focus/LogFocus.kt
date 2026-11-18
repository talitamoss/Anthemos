// app/src/main/java/com/domain/app/focus/LogFocus.kt
package com.domain.app.focus

import com.domain.app.prompts.Prompt
import com.domain.app.prompts.PromptRules
import com.domain.app.voice.Day
import com.domain.app.voice.Entry
import com.domain.app.voice.Focus
import com.domain.app.voice.Item
import com.domain.app.voice.RuleParser
import com.domain.app.voice.RuleReader
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/**
 * Log: things that happened, kept as said with what was found in them
 * (docs/anthemos-2.md, "Words first"). Also what every other focus falls
 * back on when words turn out not to be a command, and the answers to
 * prompts and follow-ups.
 */
object LogFocus {

    fun focus(parser: RuleParser) = Focus(
        "log", "Things that happened, kept as said with what was in them", listOf(
            command(
                "log.record", "Food, drink, activity, sleep, money; at the time said, or now",
                listOf("I had a coffee and toast", "went for a walk for forty five minutes", "I had a coffee at about 8 am"),
                { parser.record(it.transcript) }
            ) { keep(it) }
        )
    )

    /** Something to keep: an answer to a waiting prompt, checked if doubtful, or kept. */
    suspend fun Turn.keep(request: VoiceRequest.Record) {
        val waiting = prompt
        if (waiting != null) return answer(waiting, request)
        if (doubtful(request)) return askUnsure(request)
        record(request)
    }

    /** Keeps what was said: one entry, or one per part said with its own time. */
    suspend fun Turn.record(request: VoiceRequest.Record) {
        val parts = request.parts.ifEmpty { listOf(request) }
        val saved = parts.map { save(it.transcript, it.items, at = it.at?.toInstant()) }
        lastTurn = saved.map { it.id }
        followUpOr(saved.last(), request.reply)
    }

    suspend fun Turn.save(words: String, items: List<Item>, prompt: String? = null, at: Instant? = null): Entry {
        val entry = Entry(
            UUID.randomUUID().toString(), at ?: Instant.now(), words, items, RuleReader.NAME, prompt,
            source = if (voice) "voice" else "typed"
        )
        withContext(Dispatchers.IO) { data.saveDataPoint(entry.toDataPoint()) }
        lastSavedId = entry.id
        show { it.copy(canUndo = true) }
        return entry
    }

    /** Says [said], and asks one follow-up if an obvious detail is missing. */
    fun Turn.followUpOr(entry: Entry, said: String) {
        val next = PromptRules.followUp(entry) ?: return reply(said)
        askPrompt("$said. ${next.text}", next)
    }

    /** Words that turned out not to be a command: read them, and check if nothing's found. */
    suspend fun Turn.keepWords(transcript: String) {
        val items = reader.read(transcript)
        if (items.isEmpty()) return askUnsure(VoiceRequest.Record(transcript, items, "Noted"))
        followUpOr(save(transcript, items), reader.reply(items))
    }

    /**
     * Everything kept should be something specific; words become a note only
     * when asked for ("take a note"). So words with nothing found in them,
     * or that the recogniser wasn't sure of, are checked first.
     */
    private fun Turn.doubtful(request: VoiceRequest.Record): Boolean {
        if (request.note) return false
        return request.items.isEmpty() || unclear
    }

    private fun Turn.askUnsure(request: VoiceRequest.Record) {
        val found = request.items.isNotEmpty()
        val question = if (!found) {
            "I'm not sure what to log from that. Try again, or keep it as a note?"
        } else {
            "I may have misheard: ${reader.reply(request.items).replaceFirst("Logged", "log")}? Say yes, or try again."
        }
        ask(
            Waiting(
                question,
                listOf(
                    Choice("Try again", primary = true) { retry() },
                    Choice(if (found) "Yes, log it" else "Keep as note") { record(request) }
                )
            ) { text, _ ->
                val lower = text.lowercase().trim()
                when {
                    KEEP.containsMatchIn(lower) || (found && Words.yes(lower)) -> record(request)
                    Words.exactlyNo(lower) || AGAIN.containsMatchIn(lower) -> retry()
                    // Anything else is the second try.
                    else -> handle(text)
                }
            }
        )
    }

    private fun Turn.retry() {
        show { it.copy(reply = "") }
        listen()
    }

    /** An answer to a prompt or a follow-up. */
    private suspend fun Turn.answer(prompt: Prompt, request: VoiceRequest.Record) {
        val text = request.transcript
        if (prompt.entryId != null) {
            // "Skip", "no": the rest of this entry's questions are dropped.
            if (SKIP.matches(text.lowercase().trim().trimEnd('.', '!'))) return reply("Okay")
            if (prompt.field?.startsWith("detail:") == true) {
                if (!addDetail(prompt, text)) record(request)
                return
            }
            // "Forty minutes" fills in the walk; "I had a coffee" is something new.
            // "What did you have?" and "How did you sleep?" are answered with things; the rest aren't.
            val ownThings = prompt.field !in setOf("what", "how") && request.items.any { it.kind != "spend" && it.kind != "sleep" }
            if (ownThings || !addTo(prompt, text)) record(request)
            return
        }
        promptLog.answered(prompt.id)
        if (prompt.id.startsWith("${PromptRules.EVENT}:")) promptLog.answered(PromptRules.EVENT)
        // Hours the phone saw, when the answer gives none.
        val items = reader.read(text, prompt.context).map { item ->
            if (item.kind == "sleep" && item.minutes == null && prompt.minutes != null) item.copy(minutes = prompt.minutes) else item
        }
        val entry = save(text, items, prompt = prompt.text, at = request.at?.toInstant())
        followUpOr(entry, if (items.isEmpty()) "Kept" else reader.reply(items))
    }

    /** "Sam and Jo" to "Who was it with?": kept on the item asked about, then the next question, if any. */
    private suspend fun Turn.addDetail(prompt: Prompt, answer: String): Boolean {
        val (_, index, detail) = prompt.field!!.split(":", limit = 3)
        val entry = withContext(Dispatchers.IO) { data.getDataPoint(prompt.entryId!!) }?.let(Entry::from) ?: return false
        val i = index.toIntOrNull()?.takeIf { it in entry.items.indices } ?: return false
        val filled = Day.fill(entry.items[i], detail, answer, reader) ?: return false
        val items = entry.items.toMutableList().also { it[i] = filled }
        val updated = entry.copy(words = entry.words.trimEnd('.', ' ') + ". " + answer, items = items)
        withContext(Dispatchers.IO) { data.saveDataPoint(updated.toDataPoint()) }
        val next = PromptRules.followUp(updated, prompt.round + 1) ?: return true.also { reply("Got it") }
        askPrompt("Got it. ${next.text}", next)
        return true
    }

    /**
     * A follow-up answer fills in the detail on the entry it was about.
     * False when the answer doesn't hold that detail, so it's kept on its own.
     */
    private suspend fun Turn.addTo(prompt: Prompt, answer: String): Boolean {
        val entry = withContext(Dispatchers.IO) { data.getDataPoint(prompt.entryId!!) }?.let(Entry::from)
            ?: return false
        val items = entry.items.toMutableList()
        var said: String? = null
        when (prompt.field) {
            "minutes" -> {
                val i = items.indexOfFirst { (it.kind == "activity" || it.kind == "sleep") && it.minutes == null }
                val minutes = if (i >= 0) reader.duration(answer, bareIsHours = items[i].kind == "sleep") else null
                if (minutes != null) {
                    items[i] = items[i].copy(minutes = minutes)
                    said = if (items[i].kind == "sleep") "Added, ${minutes / 60.0} hours".replace(".0 ", " ") else "Added, $minutes minutes"
                }
            }
            "amount" -> reader.money(answer)?.let {
                items += it
                said = "Added, ${it.label}"
            }
            // "What did you have?" after "I had lunch": the food takes the meal's place.
            "what" -> {
                val food = reader.read(answer, prompt.context).filter { it.kind == "food" || it.kind == "drink" }
                if (food.isNotEmpty()) {
                    items.removeAll { it.kind == "food" && it.label == it.meal }
                    items += food
                    said = reader.reply(food)
                }
            }
            "sleep" -> reader.duration(answer, bareIsHours = true)?.let { minutes ->
                items += Item("sleep", "sleep", minutes = minutes)
                said = "Added, ${minutes / 60.0} hours".replace(".0 ", " ")
            }
            // "How did you sleep?": the words are the answer.
            "how" -> if (answer.isNotBlank()) said = "Noted"
        }
        val done = said ?: return false
        val updated = entry.copy(words = entry.words.trimEnd('.', ' ') + ". " + answer, items = items)
        withContext(Dispatchers.IO) { data.saveDataPoint(updated.toDataPoint()) }
        // A second question, if one's still open ("How many hours?" then "How did you sleep?").
        val next = PromptRules.followUp(updated, prompt.round + 1) ?: return true.also { reply(done) }
        askPrompt("$done. ${next.text}", next)
        return true
    }

    private val SKIP = Regex("^(?:skip|no|nope|nah|pass|never mind|nevermind|not sure|don't know|dont know|i don't know|rather not|no thanks|none|nothing)$")
    private val KEEP = Regex("^(?:yes |yeah |ok |okay )?(?:keep(?: it)?|save it|note|as a note|that's right|correct|it's fine)\\b")
    private val AGAIN = Regex("\\b(?:try again|again|retry|start again|wrong|that's wrong)\\b")
}
