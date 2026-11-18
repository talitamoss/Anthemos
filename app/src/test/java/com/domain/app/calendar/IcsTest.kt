package com.domain.app.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class IcsTest {
    private val zone = ZoneId.of("Australia/Sydney")
    // Wednesday 18 November 2026.
    private val from = ZonedDateTime.of(2026, 11, 16, 0, 0, 0, 0, zone).toInstant()
    private val until = ZonedDateTime.of(2026, 12, 1, 0, 0, 0, 0, zone).toInstant()

    private val ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        UID:dentist
        DTSTART:20261119T040000Z
        DTEND:20261119T050000Z
        SUMMARY:Dentist\, check-up
        LOCATION:Bondi
        BEGIN:VALARM
        TRIGGER:-PT15M
        SUMMARY:Alarm
        END:VALARM
        END:VEVENT
        BEGIN:VEVENT
        UID:yoga
        DTSTART;TZID=Australia/Sydney:20261102T070000
        DURATION:PT1H
        RRULE:FREQ=WEEKLY;BYDAY=MO,TH
        EXDATE;TZID=Australia/Sydney:20261119T070000
        SUMMARY:Yoga with a very long
          name
        END:VEVENT
        BEGIN:VEVENT
        UID:yoga
        RECURRENCE-ID;TZID=Australia/Sydney:20261123T070000
        DTSTART;TZID=Australia/Sydney:20261123T090000
        DTEND;TZID=Australia/Sydney:20261123T100000
        SUMMARY:Yoga (late)
        END:VEVENT
        BEGIN:VEVENT
        UID:birthday
        DTSTART;VALUE=DATE:20201125
        RRULE:FREQ=YEARLY
        SUMMARY:Sam's birthday
        END:VEVENT
        BEGIN:VEVENT
        UID:gone
        STATUS:CANCELLED
        DTSTART:20261120T000000Z
        SUMMARY:Cancelled
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    private fun events() = Ics.expand(Ics.parse(ics, zone), from, until).sortedBy { it.start }

    private fun at(e: Event) = e.start.atZone(zone).let { "${it.monthValue}/${it.dayOfMonth} ${it.hour}:${"%02d".format(it.minute)}" }

    @Test
    fun `one-off event in UTC, escaped, alarm ignored`() {
        val dentist = events().single { it.title.startsWith("Dentist") }
        assertEquals("Dentist, check-up", dentist.title)
        assertEquals("Bondi", dentist.place)
        assertEquals("11/19 15:00", at(dentist))
    }

    @Test
    fun `weekly repeats skip exceptions and take changes`() {
        val yoga = events().filter { it.title.startsWith("Yoga") }
        assertEquals("Yoga with a very long name", yoga.first().title)
        // Mon 16, (Thu 19 skipped), Mon 23 moved to 9, Thu 26, Mon 30.
        assertEquals(listOf("11/16 7:00", "11/23 9:00", "11/26 7:00", "11/30 7:00"), yoga.map(::at))
        assertEquals("Yoga (late)", yoga[1].title)
        assertEquals(60L, java.time.Duration.between(yoga[0].start, yoga[0].end).toMinutes())
    }

    @Test
    fun `yearly all-day, and cancelled left out`() {
        val birthday = events().single { it.title == "Sam's birthday" }
        assertTrue(birthday.allDay)
        assertEquals("11/25 0:00", at(birthday))
        assertTrue(events().none { it.title == "Cancelled" })
    }

    @Test
    fun `share links found in what was pasted`() {
        assertEquals(
            "https://calendar.proton.me/api/calendar/v1/url/abc/calendar.ics?CacheKey=x&PassphraseKey=y",
            LinkedCalendar.find("here: https://calendar.proton.me/api/calendar/v1/url/abc/calendar.ics?CacheKey=x&PassphraseKey=y.")
        )
        assertEquals(null, LinkedCalendar.find("https://example.com/page"))
    }
}
