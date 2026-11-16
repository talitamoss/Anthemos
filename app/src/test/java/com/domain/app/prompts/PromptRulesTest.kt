package com.domain.app.prompts

import com.domain.app.voice.Entry
import com.domain.app.voice.Item
import com.domain.app.voice.RuleReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class PromptRulesTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private fun at(hour: Int, minute: Int = 0, daysAgo: Long = 0) =
        ZonedDateTime.of(2026, 11, 16, hour, minute, 0, 0, zone).minusDays(daysAgo)

    private class Log(val asks: Map<String, List<Ask>> = emptyMap(), val stopped: Set<String> = emptySet()) : PromptHistory {
        override fun asks(id: String) = asks[id].orEmpty()
        override fun stopped(id: String) = id in stopped
    }

    private fun entry(time: ZonedDateTime, vararg items: Item, prompt: String? = null) =
        Entry(time.toString(), time.toInstant(), "words", items.toList(), "t", prompt)

    @Test
    fun `morning asks about sleep, but not in quiet hours`() {
        assertNull(PromptRules.due(at(6, 30), emptyList(), Log()))
        assertEquals("sleep", PromptRules.due(at(7, 15), emptyList(), Log())?.id)
        assertNull(PromptRules.due(at(21, 30), emptyList(), Log()))
    }

    @Test
    fun `nothing asked about what is already there`() {
        val slept = entry(at(7, 5), Item("sleep", "well", minutes = 420))
        assertNull(PromptRules.due(at(7, 30), listOf(slept), Log()))
        val lunch = entry(at(12, 30), Item("food", "salad", meal = "lunch"))
        assertNull(PromptRules.due(at(13, 30), listOf(lunch), Log()))
        assertEquals("lunch", PromptRules.due(at(13, 30), emptyList(), Log())?.id)
    }

    @Test
    fun `once a day, three a day at most`() {
        val askedToday = Log(mapOf("lunch" to listOf(Ask(at(13, 0).toInstant(), false))))
        assertNull(PromptRules.due(at(13, 30), emptyList(), askedToday))
        val three = Log(mapOf(
            "sleep" to listOf(Ask(at(7, 0).toInstant(), true)),
            "breakfast" to listOf(Ask(at(9, 0).toInstant(), true)),
            "lunch" to listOf(Ask(at(13, 0).toInstant(), true))
        ))
        assertNull(PromptRules.due(at(19, 30), emptyList(), three))
    }

    @Test
    fun `water only once it is usual`() {
        assertNull(PromptRules.due(at(14, 30), emptyList(), Log(mapOf("lunch" to listOf(Ask(at(13).toInstant(), true))))))
        val usual = (1L..3L).map { entry(at(10, daysAgo = it), Item("drink", "water", group = "water", ml = 250)) }
        assertEquals("water", PromptRules.due(at(14, 30), usual, Log(mapOf("lunch" to listOf(Ask(at(13).toInstant(), true)))))?.id)
    }

    @Test
    fun `ignored three times rests, stopped never comes back`() {
        val ignored = Log(mapOf("sleep" to (1L..3L).map { Ask(at(7, daysAgo = it).toInstant(), false) }))
        assertNull(PromptRules.due(at(7, 30), emptyList(), ignored))
        assertNull(PromptRules.due(at(7, 30), emptyList(), Log(stopped = setOf("sleep"))))
    }

    @Test
    fun `follow-ups for missing details`() {
        assertEquals("How long was the walk?", PromptRules.followUp(entry(at(10), Item("activity", "walk")))?.text)
        assertNull(PromptRules.followUp(entry(at(10), Item("activity", "walk", minutes = 30))))
        val bought = Entry("b", Instant.now(), "bought lunch from the bakery", listOf(Item("food", "lunch")), "t")
        assertEquals("amount", PromptRules.followUp(bought)?.field)
    }

    @Test
    fun `answers read in the light of the prompt`() {
        val reader = RuleReader(now = { LocalTime.of(9, 0) })
        val sleep = reader.read("pretty good about seven hours", "sleep").single()
        assertEquals("sleep", sleep.kind)
        assertEquals(420, sleep.minutes)
        assertEquals("good", sleep.label)
        val breakfast = reader.read("yeah porridge and a coffee", "breakfast")
        assertEquals(listOf("porridge", "coffee"), breakfast.map { it.label })
        assertEquals("breakfast", breakfast[0].meal)
        assertEquals(emptyList<Item>(), reader.read("not yet", "breakfast"))
        assertEquals(500, reader.read("yes two glasses", "water").single().ml)
    }

    @Test
    fun `money is read once`() {
        val reader = RuleReader(now = { LocalTime.of(13, 0) })
        val items = reader.read("bought a burrito for lunch for fifteen dollars fifty")
        assertEquals(listOf("burrito", "$15.50"), items.map { it.label })
        assertEquals(12.0, reader.money("twelve")!!.amount!!, 0.0)
        assertEquals(360, reader.duration("about six", bareIsHours = true))
        assertEquals(40, reader.duration("forty minutes", bareIsHours = false))
    }
}
