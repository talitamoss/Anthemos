package com.domain.app.reflect

import com.domain.app.sense.SleepGuess
import com.domain.app.voice.Entry
import com.domain.app.voice.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LensesTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val today = LocalDate.of(2026, 11, 22)
    private var n = 0

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant()

    private fun entry(date: LocalDate, hour: Int, vararg items: Item, minute: Int = 0) =
        Entry("e${n++}", at(date, hour, minute), "words", items.toList(), "rules")

    private fun record(vararg entries: Entry, nights: Map<LocalDate, SleepGuess> = emptyMap(), screen: Map<LocalDate, Int> = emptyMap()) =
        Record(entries.toList(), today, zone, nights, screen)

    @Test
    fun `nothing recorded shows nothing`() {
        assertEquals(emptyList<Reflection>(), Lenses.look(record()))
    }

    @Test
    fun `sleep said in words counts for that morning`() {
        val r = SleepLens.look(record(entry(today, 7, Item("sleep", "slept well", minutes = 8 * 60))))!!
        assertEquals("100", r.value)
        assertEquals("8h last night · 8h a night this week", r.note)
        assertEquals(480f, r.days.last())
    }

    @Test
    fun `bed and wake make a night`() {
        val yesterday = today.minusDays(1)
        val night = SleepLens.night(
            record(entry(yesterday, 23, Item("bed", "went to bed")), entry(today, 6, Item("wake", "woke up"))),
            today
        )!!
        assertEquals(7 * 60, night.minutes)
        assertEquals(5 * 60, night.bedMinute)
    }

    @Test
    fun `the phone fills nights nothing was said about`() {
        val guess = SleepGuess(at(today.minusDays(1), 22), at(today, 6))
        val r = SleepLens.look(record(nights = mapOf(today to guess)))!!
        assertEquals("8h last night · 8h a night this week", r.note)
    }

    @Test
    fun `an unsteady bedtime costs some of the score`() {
        val steady = listOf(SleepLens.Night(480, 300), SleepLens.Night(480, 300), SleepLens.Night(480, 300))
        val unsteady = listOf(SleepLens.Night(480, 180), SleepLens.Night(480, 300), SleepLens.Night(480, 480))
        assertEquals(100, SleepLens.score(steady))
        assertEquals(true, SleepLens.score(unsteady) < 100)
    }

    @Test
    fun `movement adds up minutes and names the usual one`() {
        val r = MovementLens.look(
            record(
                entry(today, 9, Item("activity", "tennis", group = "sport", minutes = 60)),
                entry(today.minusDays(2), 9, Item("activity", "tennis", group = "sport", minutes = 30)),
                entry(today.minusDays(1), 17, Item("outside", "outside", minutes = 20))
            )
        )!!
        assertEquals("1h 50m", r.value)
        assertEquals("this week, 3 days · mostly tennis", r.note)
    }

    @Test
    fun `movement counts times when no minutes were said`() {
        val r = MovementLens.look(record(entry(today, 9, Item("activity", "walk"))))!!
        assertEquals("1 time", r.value)
    }

    @Test
    fun `last week's movement is not this week's`() {
        assertNull(MovementLens.look(record(entry(today.minusDays(9), 9, Item("activity", "tennis", minutes = 60)))))
    }

    @Test
    fun `mood averages the scores given`() {
        val r = MoodLens.look(
            record(
                entry(today, 20, Item("mood", "good", details = mapOf("score" to "8"))),
                entry(today.minusDays(1), 20, Item("mood", "good", details = mapOf("score" to "6")))
            )
        )!!
        assertEquals("7 / 10", r.value)
        assertEquals("this week · mostly good", r.note)
    }

    @Test
    fun `screen time is today against the days before`() {
        val r = ScreenLens.look(record(screen = mapOf(today to 190, today.minusDays(1) to 120, today.minusDays(2) to 180)))!!
        assertEquals("3h 10m", r.value)
        assertEquals("today · 2h 30m a day before", r.note)
    }
}
