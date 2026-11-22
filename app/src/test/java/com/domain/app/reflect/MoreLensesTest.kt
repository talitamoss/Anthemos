package com.domain.app.reflect

import com.domain.app.voice.Entry
import com.domain.app.voice.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MoreLensesTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val today = LocalDate.of(2026, 11, 22)
    private var n = 0

    private fun entry(date: LocalDate, vararg items: Item, hour: Int = 12) =
        Entry("e${n++}", date.atTime(hour, 0).atZone(zone).toInstant(), "words", items.toList(), "rules")

    private fun record(vararg entries: Entry) = Record(entries.toList(), today, zone)

    @Test
    fun `smoke-free counts days since the last one`() {
        val r = SmokeFreeLens.look(record(entry(today.minusDays(31), Item("smoking", "cigarette"))))!!
        assertEquals("Smoke-free", r.title)
        assertEquals("31 days", r.value)
        assertEquals("since 22 Oct", r.note)
    }

    @Test
    fun `smoke-free from when you said you quit`() {
        val r = SmokeFreeLens.look(record(entry(today.minusDays(5), Item("smoking", "quit"))))!!
        assertEquals("5 days", r.value)
    }

    @Test
    fun `a day you smoked says how many`() {
        val r = SmokeFreeLens.look(record(entry(today, Item("smoking", "cigarette", count = 2)), entry(today.minusDays(1), Item("smoking", "cigarette"))))!!
        assertEquals("Smoking", r.title)
        assertEquals("2 cigarettes today", r.value)
        assertEquals("3 cigarettes this week", r.note)
    }

    @Test
    fun `nothing said about smoking shows nothing`() {
        assertNull(SmokeFreeLens.look(record(entry(today, Item("drink", "coffee", group = "coffee")))))
    }

    @Test
    fun `alcohol this week and the days without`() {
        val r = AlcoholLens.look(
            record(
                entry(today, Item("drink", "wine", count = 2, group = "alcohol")),
                entry(today.minusDays(1), Item("drink", "coffee", group = "coffee")),
                entry(today.minusDays(2), Item("drink", "no alcohol", count = 0, group = "alcohol"))
            )
        )!!
        assertEquals("2 drinks", r.value)
        assertEquals("this week · 2 days without", r.note)
    }

    @Test
    fun `alcohol-free once you've said a day was`() {
        val r = AlcoholFreeLens.look(
            record(
                entry(today.minusDays(10), Item("drink", "beer", group = "alcohol")),
                entry(today.minusDays(3), Item("drink", "no alcohol", count = 0, group = "alcohol", details = mapOf("none" to "yes")))
            )
        )!!
        assertEquals("10 days", r.value)
        assertNull(AlcoholFreeLens.look(record(entry(today.minusDays(3), Item("drink", "beer", group = "alcohol")))))
    }

    @Test
    fun `water and coffee by the day`() {
        val r = WaterLens.look(record(entry(today, Item("drink", "water", count = 3, group = "water"))))!!
        assertEquals("3 glasses", r.value)
        assertEquals("1 cup", CoffeeLens.look(record(entry(today, Item("drink", "coffee", group = "coffee"))))!!.value)
    }

    @Test
    fun `steps today and the week`() {
        val r = StepsLens.look(
            record(
                entry(today, Item("steps", "steps", count = 4000), hour = 12),
                entry(today, Item("steps", "steps", count = 9000), hour = 20),
                entry(today.minusDays(1), Item("steps", "steps", count = 7000))
            )
        )!!
        assertEquals("9,000", r.value)
        assertEquals("today · 8,000 a day this week", r.note)
    }

    @Test
    fun `care habits, days out of seven, one each`() {
        val all = CareLens.all(
            record(
                entry(today, Item("care", "flossed")),
                entry(today.minusDays(1), Item("care", "flossed")),
                entry(today, Item("care", "sauna"))
            )
        )
        assertEquals(listOf("Flossed" to "2 of 7 days", "Sauna" to "1 of 7 days"), all.map { it.title to it.value })
    }

    @Test
    fun `meditation is not movement`() {
        val rec = record(entry(today, Item("activity", "meditation", minutes = 20)))
        assertNull(MovementLens.look(rec))
        assertEquals("20m", MeditationLens.look(rec)!!.value)
    }

    @Test
    fun `a nap is not the night`() {
        assertNull(SleepLens.look(record(entry(today, Item("sleep", "nap", minutes = 30)))))
    }

    @Test
    fun `mood reads scores as kept`() {
        val r = MoodLens.look(record(entry(today, Item("mood", "good", details = mapOf("score" to "8/10")))))!!
        assertEquals("8 / 10", r.value)
    }

    @Test
    fun `weight and its month`() {
        val r = WeightLens.look(
            record(
                entry(today.minusDays(20), Item("weight", "weight", details = mapOf("value" to "74 kg"))),
                entry(today, Item("weight", "weight", details = mapOf("value" to "72.5 kg")))
            )
        )!!
        assertEquals("72.5 kg", r.value)
        assertEquals("22 Nov · -1.5 kg in a month", r.note)
    }

    @Test
    fun `the latest of each measure`() {
        val all = VitalsLens.all(
            record(
                entry(today.minusDays(2), Item("vital", "blood pressure", details = mapOf("value" to "130/85"))),
                entry(today, Item("vital", "blood pressure", details = mapOf("value" to "120/80"))),
                entry(today, Item("vital", "resting heart rate", details = mapOf("value" to "58")))
            )
        )
        assertEquals(listOf("Blood pressure" to "120/80", "Resting heart rate" to "58 bpm"), all.map { it.title to it.value })
    }

    @Test
    fun `people this week`() {
        val r = PeopleLens.look(
            record(
                entry(today, Item("social", "saw sam", details = mapOf("who" to "sam"))),
                entry(today.minusDays(1), Item("call", "mum", details = mapOf("who" to "mum"))),
                entry(today.minusDays(2), Item("social", "saw sam", details = mapOf("who" to "sam")))
            )
        )!!
        assertEquals("3 times", r.value)
        assertEquals("this week · most with sam", r.note)
    }

    @Test
    fun `gratitude keeps the latest`() {
        val r = GratitudeLens.look(record(entry(today, Item("gratitude", "the sun"))))!!
        assertEquals("1 thing", r.value)
        assertEquals("the sun", r.note)
    }
}
