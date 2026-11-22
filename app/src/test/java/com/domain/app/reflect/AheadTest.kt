package com.domain.app.reflect

import com.domain.app.calendar.Event
import com.domain.app.weather.DayForecast
import com.domain.app.weather.Forecast
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class AheadTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val today = LocalDate.of(2026, 11, 22)
    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone)

    private val forecast = Forecast(
        null, 18.4, 17.0, 2,
        listOf(
            DayForecast(today, 2, 12.2, 21.4, 40, 1.2),
            DayForecast(today.plusDays(1), 80, 14.0, 22.0, 70, 5.0)
        )
    )

    private fun event(title: String, start: ZonedDateTime, hours: Long = 1, allDay: Boolean = false) =
        Event(title, title, start.toInstant(), start.plusHours(hours).toInstant(), allDay)

    @Test
    fun `the weather now and the day`() {
        assertEquals("18°, partly cloudy · today 12 to 21°, 40% rain", Upcoming.weather(forecast, at(today, 9)))
    }

    @Test
    fun `in the evening, tomorrow`() {
        assertEquals("18°, partly cloudy · tomorrow 14 to 22°, showers, 70% rain", Upcoming.weather(forecast, at(today, 19)))
    }

    @Test
    fun `the rest of today and tomorrow`() {
        val now = at(today, 10)
        val shown = Upcoming.events(
            listOf(
                event("Breakfast", at(today, 8)),
                event("Standup", at(today, 9, 30), hours = 1),
                event("Dentist", at(today, 14)),
                event("Gym", at(today.plusDays(1), 7)),
                event("Holiday", at(today.plusDays(1), 0), hours = 24, allDay = true),
                event("Later", at(today.plusDays(4), 9))
            ),
            now
        )
        assertEquals(
            listOf("Now Standup", "14:00 Dentist", "Tomorrow, all day Holiday", "Tomorrow 7:00 Gym"),
            shown.map { "${it.time} ${it.title}" }
        )
    }

    @Test
    fun `nothing soon shows the next in the week`() {
        val shown = Upcoming.events(listOf(event("Later", at(today.plusDays(4), 9))), at(today, 10))
        assertEquals(listOf("Thu 9:00 Later"), shown.map { "${it.time} ${it.title}" })
    }

    @Test
    fun `one in both calendars is told once`() {
        val shown = Upcoming.events(listOf(event("Dentist", at(today, 14)), event("dentist", at(today, 14))), at(today, 10))
        assertEquals(1, shown.size)
    }
}
