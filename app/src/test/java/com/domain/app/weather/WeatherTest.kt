package com.domain.app.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class WeatherTest {
    // Wednesday 18 November 2026.
    private val now = ZonedDateTime.of(2026, 11, 18, 9, 0, 0, 0, ZoneId.of("Australia/Sydney"))
    private val today = now.toLocalDate()
    private val forecast = Forecast(
        null, 17.4, 13.0, 3,
        listOf(
            DayForecast(today, 80, 14.2, 21.0, 70, 6.2),
            DayForecast(today.plusDays(1), 0, 12.0, 24.4, 5, 0.0),
            DayForecast(LocalDate.of(2026, 11, 21), 61, 15.0, 19.0, 40, 2.0)
        )
    )

    private fun ask(s: String) = Weather.answer(Weather.parse(s, now)!!, forecast, today)

    @Test
    fun `questions read`() {
        assertEquals(WeatherAsk(today, "today", null, WeatherAsk.About.ALL), Weather.parse("what's the weather like", now))
        assertEquals(WeatherAsk(today.plusDays(1), "tomorrow", null, WeatherAsk.About.RAIN), Weather.parse("will it rain tomorrow", now))
        assertEquals(WeatherAsk(LocalDate.of(2026, 11, 21), "on Saturday", "melbourne", WeatherAsk.About.ALL), Weather.parse("what's the weather in melbourne this weekend", now))
        assertNull(Weather.parse("how cold is it inside", now))
    }

    @Test
    fun `answers`() {
        assertEquals("Today: showers, 14 to 21 degrees, 70% chance of rain. Now 17 degrees and cloudy.", ask("what's the weather"))
        assertEquals("Probably not tomorrow: 5% chance.", ask("will it rain tomorrow"))
        assertEquals("Maybe on Saturday: 40% chance, about 2 mm.", ask("is it going to rain on saturday"))
        assertEquals("Now 17 degrees, feels like 13. Today: 14 to 21 degrees.", ask("how cold is it"))
    }
}
