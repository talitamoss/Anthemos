// app/src/main/java/com/domain/app/focus/CalendarFocus.kt
package com.domain.app.focus

import android.content.ClipboardManager
import android.content.Context
import com.domain.app.calendar.Diary
import com.domain.app.calendar.Event
import com.domain.app.calendar.LinkedCalendar
import com.domain.app.calendar.Reminders
import com.domain.app.voice.Focus
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/**
 * Calendar: Anthemos's own calendar and reminders, and Proton's read
 * through its share link (docs/anthemos-2.md, "Calendar").
 */
object CalendarFocus {

    fun focus() = Focus(
        "calendar", "Anthemos's own calendar and reminders, and Proton's read through its link", listOf(
            command(
                "calendar.link", "Reads a calendar from its share link, typed, pasted or copied",
                listOf("connect my calendar", "link my Proton calendar", "https://calendar.proton.me/api/calendar/v1/url/abc/calendar.ics?CacheKey=x"),
                { s ->
                    LinkedCalendar.find(s.transcript)?.let { VoiceRequest.LinkCalendar(s.transcript, it) }
                        ?: if (LINK.matches(s.command)) VoiceRequest.LinkCalendar(s.transcript, null) else null
                }
            ) { link(it.url) },
            command(
                "calendar.unlink", "Stops reading the linked calendar",
                listOf("disconnect my calendar"),
                { s -> if (UNLINK.matches(s.command)) VoiceRequest.UnlinkCalendar(s.transcript) else null }
            ) {
                calendar.forget()
                reply("Okay, I've stopped reading your Proton calendar")
            },
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

    /** Anthemos's own events and the linked calendar's; one added to both is told once. */
    private suspend fun Turn.events(): List<Event> = withContext(Dispatchers.IO) {
        val own = data.getPluginData(Event.PLUGIN_ID).first().mapNotNull(Event::from)
        val now = ZonedDateTime.now()
        val linked = calendar.events(now.minusDays(7).toInstant(), now.plusDays(120).toInstant())
        own.filterNot { e -> linked.any { it.start == e.start && it.title.equals(e.title, ignoreCase = true) } } + linked
    }

    /** Keeps the link, from what was typed or the clipboard. */
    private suspend fun Turn.link(typed: String?) {
        val url = typed ?: clipboard()?.let(LinkedCalendar::find)
            ?: return reply(
                "In Proton Calendar on the web, open Settings, Calendars, your calendar, then Share with anyone, " +
                    "and copy a full view link. Then paste it here, or say connect my calendar."
            )
        try {
            val count = withContext(Dispatchers.IO) { calendar.link(url) }
            reply("Connected your calendar, $count events. Ask me what's on today.")
        } catch (e: Exception) {
            reply(e.message ?: "I couldn't read that calendar")
        }
    }

    private fun Turn.clipboard(): String? = runCatching {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()

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
            // Proton saves only through its own screen: opened with the event filled in.
            if (calendar.linked && Reminders.handOff(context, event)) {
                reply("Added ${Diary.describe(event, now)}. Tap save in Proton to keep it there too.")
            } else {
                reply("Added ${Diary.describe(event, now)}")
                // Offered once: "Also add to Proton".
                show { it.copy(handoff = event) }
            }
        }
    }

    private val LINK = Regex("^(?:connect|link|add|sync|hook up)(?: to| up)? (?:my |the )?(?:proton )?calendar(?: to (?:anthemos|you))?$")
    private val UNLINK = Regex("^(?:disconnect|unlink|forget|remove)(?: from)? (?:my |the )?(?:proton )?calendar$")
}
