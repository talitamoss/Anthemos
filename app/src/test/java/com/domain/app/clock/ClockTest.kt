package com.domain.app.clock

import com.domain.app.voice.RuleParser
import com.domain.app.voice.VoiceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ClockTest {
    private val now = ZonedDateTime.of(2026, 11, 18, 21, 0, 0, 0, ZoneId.of("Australia/Sydney"))
    private fun parse(s: String) = Clock.parse(s, now)

    @Test
    fun `timers`() {
        assertEquals(Clock.Timer(600, null), parse("set a timer for ten minutes"))
        assertEquals(Clock.Timer(300, null), parse("5 minute timer"))
        assertEquals(Clock.Timer(5400, null), parse("timer for an hour and a half"))
        assertEquals(Clock.Timer(1800, null), parse("set a timer for half an hour"))
        assertEquals(Clock.Timer(90, null), parse("timer 90 seconds"))
        assertEquals(Clock.Timer(720, "pasta"), parse("set a pasta timer for 12 minutes"))
        assertEquals(Clock.StopTimer, parse("stop the timer"))
    }

    @Test
    fun `alarms`() {
        assertEquals(LocalTime.of(6, 30), (parse("set an alarm for 6:30 am") as Clock.Alarm).time)
        assertEquals(LocalTime.of(7, 0), (parse("wake me up at 7") as Clock.Alarm).time)
        val weekdays = parse("set an alarm for 6 am every weekday") as Clock.Alarm
        assertEquals(LocalTime.of(6, 0), weekdays.time)
        assertEquals(DayOfWeek.values().take(5), weekdays.days)
        assertEquals(Clock.ShowAlarms, parse("cancel my alarm"))
    }

    @Test
    fun `routed ahead of the calendar, the house and the log`() {
        val parser = RuleParser()
        assertTrue(parser.parse("Hey Polly, set a timer for 10 minutes") is VoiceRequest.Clock)
        assertTrue(parser.parse("set the alarm for 7") is VoiceRequest.Clock)
        assertTrue(parser.parse("remind me to take the bins out at 7") is VoiceRequest.AddEvent)
        assertTrue(parser.parse("turn on the torch") is VoiceRequest.Device)
        assertTrue(parser.parse("turn on the kitchen lights") is VoiceRequest.Home)
        assertTrue(parser.parse("open signal") is VoiceRequest.Device)
        assertTrue(parser.parse("how much battery have I got") is VoiceRequest.Device)
        assertTrue(parser.parse("will it rain tomorrow") is VoiceRequest.Weather)
        assertTrue(parser.parse("what's the temperature inside") is VoiceRequest.Home)
    }
}
