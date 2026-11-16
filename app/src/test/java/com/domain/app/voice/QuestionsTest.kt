package com.domain.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class QuestionsTest {
    private val zone = ZoneId.of("Australia/Sydney")
    // Monday 16 November 2026, 1 pm.
    private val now = ZonedDateTime.of(2026, 11, 16, 13, 0, 0, 0, zone)

    private fun at(daysAgo: Long, hour: Int, minute: Int = 0) =
        now.minusDays(daysAgo).withHour(hour).withMinute(minute).toInstant()

    private val entries = listOf(
        Entry("1", at(0, 9, 15), "had a flat white", listOf(Item("drink", "flat white", group = "coffee")), "t"),
        Entry("2", at(0, 12, 30), "chicken sandwich and a coffee", listOf(
            Item("food", "chicken sandwich", meal = "lunch"), Item("drink", "coffee", group = "coffee")
        ), "t"),
        Entry("3", at(1, 18, 40), "Called John Moss", listOf(Item("call", "John Moss", said = "dad")), "t"),
        Entry("4", at(1, 8, 0), "two coffees", listOf(Item("drink", "coffee", count = 2, group = "coffee")), "t"),
        Entry("5", at(3, 7, 0), "two glasses of water", listOf(Item("drink", "water", count = 2, group = "water", ml = 500)), "t"),
        Entry("6", at(20, 12, 0), "pizza", listOf(Item("food", "pizza")), "t")
    )

    private fun ask(text: String, alias: (String) -> String? = { null }): String {
        val q = Questions.parse(text, now) ?: error("not a question: $text")
        return Questions.answer(q, entries, now, alias)
    }

    @Test
    fun `last call by the word said`() {
        assertEquals("You last called John Moss yesterday at 6:40 pm.", ask("When did I last call dad?"))
        assertEquals("You last called John Moss yesterday at 6:40 pm.", ask("when was the last time I rang dad"))
    }

    @Test
    fun `last call by a taught name`() {
        val q = Questions.parse("when did I last call papa", now)!!
        val e = listOf(Entry("x", at(2, 10), "Called John Moss", listOf(Item("call", "John Moss", said = "john")), "t"))
        assertEquals("You last called John Moss on Saturday at 10:00 am.",
            Questions.answer(q, e, now) { if (it == "papa") "John Moss" else null })
    }

    @Test
    fun `no record says so`() {
        assertEquals(
            "I don't have a call to mum yet. I only know calls made through Anthemos.",
            ask("when did I last call mum")
        )
    }

    @Test
    fun `counting by group and period`() {
        assertEquals("2 coffees today.", ask("How many coffees have I had today?"))
        assertEquals("2 coffees this week.", ask("how many coffees have I had this week"))
        assertEquals("4 coffees in the last 3 days.", ask("how many coffees in the last three days"))
        assertEquals("2 coffees today.", ask("how many coffees have I had"))
        assertEquals("500 ml of water in the last 7 days.", ask("how much water have I had in the last seven days"))
        assertEquals("You called John Moss once in the last 7 days.", ask("how many times have I called dad in the last seven days"))
        assertEquals("You haven't called dad this week.", ask("how many times have I called dad this week"))
        assertEquals("No pizzas today.", ask("how many pizzas today"))
    }

    @Test
    fun `yes or no`() {
        assertEquals("Yes, today at 12:30 pm, 2 in all.", ask("did I have a coffee today"))
        assertEquals("No, not today.", ask("did I have a pizza today"))
    }

    @Test
    fun `what did I eat`() {
        assertEquals("Today: chicken sandwich.", ask("what did I eat today"))
        assertEquals("Yesterday: 2 coffee, called John Moss.", ask("what did I do yesterday"))
        assertEquals("Nothing recorded yesterday.", ask("what did I eat yesterday"))
    }

    @Test
    fun `last thing by its words`() {
        assertEquals("Last pizza: on 27 October.", ask("when did I last have pizza"))
    }
}
