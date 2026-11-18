// app/src/main/java/com/domain/app/focus/ChangeFocus.kt
package com.domain.app.focus

import com.domain.app.focus.LogFocus.keepWords
import com.domain.app.voice.Change
import com.domain.app.voice.Changes
import com.domain.app.voice.Entry
import com.domain.app.voice.Focus
import com.domain.app.voice.RuleParser
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Change: fixing what was kept (docs/anthemos-2.md, "Words first", Changing an entry). */
object ChangeFocus {

    fun focus() = Focus(
        "change", "Fixing what was kept", listOf(
            command(
                "change.undo", "Takes back everything from the last thing said",
                listOf("undo", "scratch that", "undo the last entry", "delete my last one"),
                { s -> if (RuleParser.UNDO.matches(s.command)) VoiceRequest.Undo(s.transcript) else null }
            ) { undo() },
            command(
                "change.edit", "Changes, moves or removes something kept",
                listOf("change the coffee to tea", "delete the coffee", "the walk was at seven", "make that two"),
                { s -> Change.parse(s.command, s.now)?.let { VoiceRequest.Edit(s.transcript, it) } }
            ) { change(it) }
        )
    )

    suspend fun Turn.undo() {
        val id = lastSavedId ?: todayNewest()
        if (id == null) return reply("There's nothing to take back")
        val ids = if (id in lastTurn) lastTurn else listOf(id)
        withContext(Dispatchers.IO) { ids.forEach { data.deleteDataPoint(it) } }
        lastSavedId = null
        lastTurn = emptyList()
        show { it.copy(canUndo = false) }
        reply("Taken back")
    }

    private suspend fun Turn.todayNewest(): String? {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        return withContext(Dispatchers.IO) { data.getPluginData(Entry.PLUGIN_ID).first() }
            // Places the phone kept on its own aren't something said.
            .mapNotNull(Entry::from).filter { it.source != "phone" && !it.time.isBefore(start) }.maxByOrNull { it.time }?.id
    }

    /** "Change the coffee to tea", "delete the coffee", "that was at eight". */
    private suspend fun Turn.change(request: VoiceRequest.Edit) {
        val now = ZonedDateTime.now()
        val since = now.minusDays(7).toInstant()
        val entries = withContext(Dispatchers.IO) {
            data.getPluginData(Entry.PLUGIN_ID).first().mapNotNull(Entry::from)
        }.filter { it.source != "phone" && it.time.isAfter(since) }.sortedByDescending { it.time }
        val last = entries.firstOrNull { it.id == lastSavedId } ?: entries.firstOrNull()
        finish(Changes.apply(request.change, entries, last, now), request.transcript)
    }

    private suspend fun Turn.finish(outcome: Changes.Outcome, transcript: String?) {
        when (outcome) {
            is Changes.Outcome.Save -> {
                withContext(Dispatchers.IO) { data.saveDataPoint(outcome.entry.toDataPoint()) }
                lastSavedId = outcome.entry.id
                reply(outcome.reply)
            }
            is Changes.Outcome.Delete -> {
                withContext(Dispatchers.IO) { data.deleteDataPoint(outcome.entry.id) }
                if (lastSavedId == outcome.entry.id) lastSavedId = null
                reply(outcome.reply)
            }
            is Changes.Outcome.Say -> reply(outcome.reply)
            // "Which coffee, 8:00 am or 11:00 am?", by voice or by tapping a time.
            is Changes.Outcome.Choose -> ask(
                Waiting(
                    outcome.question,
                    outcome.options.map { entry ->
                        Choice(TIME.format(entry.time.atZone(ZoneId.systemDefault()))) {
                            finish(Changes.applyTo(outcome.change, entry, ZonedDateTime.now()), null)
                        }
                    }
                ) { text, _ ->
                    val picked = Changes.pick(text, outcome.options, ZonedDateTime.now())
                    if (picked == null) reply("Okay, left as it was") else finish(Changes.applyTo(outcome.change, picked, ZonedDateTime.now()), null)
                }
            )
            Changes.Outcome.Keep -> transcript?.let { keepWords(it) } ?: reply("Okay, left as it was")
        }
    }

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
}
