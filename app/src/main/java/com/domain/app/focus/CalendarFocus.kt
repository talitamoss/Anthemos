// app/src/main/java/com/domain/app/focus/CalendarFocus.kt
package com.domain.app.focus

import com.domain.app.calendar.Diary
import com.domain.app.calendar.Event
import com.domain.app.calendar.Reminders
import com.domain.app.voice.Focus
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/** Calendar: Anthemos's own calendar and reminders (docs/anthemos-2.md, "Calendar"). */
object CalendarFocus {

    fun focus() = Focus(
        "calendar", "Anthemos's own calendar and reminders", listOf(
            command(
                "calendar.add", "Adds an event or a reminder, after asking",
                listOf("add dentist thursday at three", "remind me to call dad at five"),
                { s -> Diary.parseAdd(s.transcript, s.now)?.let { VoiceRequest.AddEvent(s.transcript, it) } }
            ) { confirmEvent(it.event) },
            command(
                "calendar.ask", "What's on, when something is, whether you're free",
                listOf("what's on tomorrow", "when's the dentist", "am I free friday afternoon"),
                { s -> Diary.parseQuestion(s.transcript, s.now)?.let { VoiceRequest.AskDiary(s.transcript, it) } }
            ) { reply(Diary.answer(it.question, events(), ZonedDateTime.now())) }
        )
    )

    private suspend fun Turn.events(): List<Event> = withContext(Dispatchers.IO) {
        data.getPluginData(Event.PLUGIN_ID).first().mapNotNull(Event::from)
    }

    /** "Add Dentist, Thursday 20 November at 3:00 pm?" */
    private fun Turn.confirmEvent(event: Event) {
        val now = ZonedDateTime.now()
        val question = if (event.reminder) {
            "Remind you: ${event.title}, ${Diary.dayAndTime(event, now)}?"
        } else {
            "Add ${Diary.describe(event, now)}?"
        }
        ask(confirm(question, "Yes, add", "Okay, not added", yesWords = listOf("add")) { addEvent(event) })
    }

    private suspend fun Turn.addEvent(event: Event) {
        withContext(Dispatchers.IO) { data.saveDataPoint(event.toDataPoint()) }
        Reminders.schedule(context, event)
        val now = ZonedDateTime.now()
        if (event.reminder) {
            reply("I'll remind you ${Diary.dayAndTime(event, now)}")
        } else {
            reply("Added ${Diary.describe(event, now)}")
            // Offered once: "Also add to Proton".
            show { it.copy(handoff = event) }
        }
    }
}
