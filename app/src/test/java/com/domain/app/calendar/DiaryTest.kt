package com.domain.app.calendar

import com.domain.app.sense.ScreenOn
import com.domain.app.sense.Sleep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DiaryTest {
    private val zone = ZoneId.of("Australia/Sydney")
    // Tuesday 17 November 2026, 10 am.
    private val now = ZonedDateTime.of(2026, 11, 17, 10, 0, 0, 0, zone)

    private fun local(e: Event) = LocalDateTime.ofInstant(e.start, zone)
    private fun add(text: String) = Diary.parseAdd(text, now) ?: error("not an event: $text")

    @Test
    fun `an appointment on a weekday`() {
        val e = add("add dentist thursday at three")
        assertEquals("Dentist", e.title)
        assertEquals(LocalDateTime.of(2026, 11, 19, 15, 0), local(e))
        assertFalse(e.reminder)
        assertEquals("Add Dentist, Thursday 19 November at 3:00 pm", "Add " + Diary.describe(e, now))
    }

    @Test
    fun `lunch with a place and half past`() {
        val e = add("put lunch with sam tomorrow at half past twelve at the bakery in my calendar")
        assertEquals("Lunch with sam", e.title)
        assertEquals("bakery", e.place)
        assertEquals(LocalDateTime.of(2026, 11, 18, 12, 30), local(e))
    }

    @Test
    fun `reminders`() {
        val e = add("remind me to call dad at five")
        assertTrue(e.reminder)
        assertEquals("Call dad", e.title)
        assertEquals(LocalDateTime.of(2026, 11, 17, 17, 0), local(e))
        val soon = add("remind me to take the washing out in twenty minutes")
        assertEquals(now.plusMinutes(20).toInstant(), soon.start)
    }

    @Test
    fun `dates by number and all day`() {
        val e = add("I've got the market on the twenty first")
        assertEquals(LocalDateTime.of(2026, 11, 21, 0, 0), local(e))
        assertTrue(e.allDay)
        assertEquals(LocalDateTime.of(2026, 12, 3, 9, 30), local(add("book haircut december third at 9:30 am")))
    }

    @Test
    fun `not events`() {
        assertNull(Diary.parseAdd("I had a coffee", now))
        assertNull(Diary.parseAdd("I've been to the gym today", now))
        assertNull(Diary.parseAdd("I have two coffees", now))
    }

    @Test
    fun `questions about what's on`() {
        val dentist = add("add dentist thursday at three")
        val lunch = add("add lunch with sam tomorrow at 12:30")
        val events = listOf(dentist, lunch)
        fun ask(t: String) = Diary.answer(Diary.parseQuestion(t, now)!!, events, now)
        assertEquals("Tomorrow: Lunch with sam at 12:30 pm.", ask("what's on tomorrow"))
        assertEquals("Dentist is Thursday 19 November at 3:00 pm.", ask("when's the dentist"))
        assertEquals("Yes, you're free on Friday 20 November afternoon.", ask("am I free friday afternoon"))
        assertEquals("Not quite: Lunch with sam at 12:30 pm.", ask("am I free tomorrow"))
        assertEquals("Nothing on today.", ask("what's on today"))
    }

    @Test
    fun `sleep from phone use, glances ignored`() {
        fun t(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant()
        val spans = listOf(
            ScreenOn(t(16, 22, 0), t(16, 23, 10)),
            ScreenOn(t(17, 3, 0), t(17, 3, 1)), // checking the time
            ScreenOn(t(17, 6, 40), t(17, 7, 0))
        )
        val guess = Sleep.guess(spans, t(16, 18), t(17, 13))!!
        assertEquals(t(16, 23, 10), guess.bed)
        assertEquals(t(17, 6, 40), guess.up)
        assertEquals(450, guess.minutes)
    }
}
