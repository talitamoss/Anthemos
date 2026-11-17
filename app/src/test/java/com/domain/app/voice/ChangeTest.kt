package com.domain.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ChangeTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val now = ZonedDateTime.of(2026, 11, 16, 13, 0, 0, 0, zone)
    private val parser = RuleParser(now = { LocalTime.of(13, 0) }, today = { now })

    private fun entry(id: String, words: String, hour: Int = 12): Entry {
        val at = now.withHour(hour).withMinute(0)
        return Entry(id, at.toInstant(), words, RuleReader { at.toLocalTime() }.read(words), RuleReader.NAME)
    }

    private fun change(text: String) = (parser.parse(text) as VoiceRequest.Edit).change

    private fun apply(text: String, vararg entries: Entry) =
        Changes.apply(change(text), entries.toList(), entries.first(), now)

    @Test
    fun `changing one thing for another`() {
        val lunch = entry("1", "I had a chicken sandwich and a coffee")
        val saved = apply("change the coffee to tea", lunch) as Changes.Outcome.Save
        assertEquals("I had a chicken sandwich and a tea", saved.entry.words)
        assertEquals(listOf("chicken sandwich", "tea"), saved.entry.items.map { it.label })

        val not = apply("no it was tea not coffee", lunch) as Changes.Outcome.Save
        assertEquals("I had a chicken sandwich and a tea", not.entry.words)
    }

    @Test
    fun `make that two`() {
        val coffee = entry("1", "I had a coffee")
        assertEquals("I had two coffees", (apply("make that two coffees", coffee) as Changes.Outcome.Save).entry.words)
        val two = (apply("make that two", coffee) as Changes.Outcome.Save).entry
        assertEquals("I had two coffees", two.words)
        assertEquals(2, two.items.single().count)
    }

    @Test
    fun `the new words take the place of what they're like`() {
        val tea = entry("1", "had a green tea and toast")
        val saved = apply("actually I meant a coffee", tea) as Changes.Outcome.Save
        assertEquals("had a coffee and toast", saved.entry.words)
    }

    @Test
    fun `removing one thing keeps the rest`() {
        val lunch = entry("1", "I had a coffee and toast")
        val saved = apply("delete the coffee", lunch) as Changes.Outcome.Save
        assertEquals("I had toast", saved.entry.words)
        assertEquals(listOf("toast"), saved.entry.items.map { it.label })

        val alone = entry("2", "I had a coffee")
        assertTrue(apply("remove the coffee", alone) is Changes.Outcome.Delete)
    }

    @Test
    fun `finds an earlier entry by what was in it`() {
        val walk = entry("1", "went for a walk for forty minutes", hour = 8)
        val coffee = entry("2", "I had a coffee", hour = 12)
        val saved = Changes.apply(change("the walk was at seven"), listOf(coffee, walk), coffee, now) as Changes.Outcome.Save
        assertEquals("1", saved.entry.id)
        assertEquals(LocalTime.of(7, 0), saved.entry.time.atZone(zone).toLocalTime())
        assertEquals(40, saved.entry.items.single().minutes)
    }

    @Test
    fun `that was yesterday`() {
        val coffee = entry("1", "I had a coffee")
        val saved = apply("that was yesterday", coffee) as Changes.Outcome.Save
        assertEquals(now.toLocalDate().minusDays(1), saved.entry.time.atZone(zone).toLocalDate())
        assertEquals("Moved coffee to 12:00 pm yesterday", saved.reply)
    }

    @Test
    fun `ordinary sentences are not changes`() {
        assertTrue(parser.parse("the bus was late") is VoiceRequest.Record)
        assertTrue(parser.parse("I'm not hungry") is VoiceRequest.Record)
        assertTrue(parser.parse("I had a coffee") is VoiceRequest.Record)
        // Sounds like a change, but nothing matches: kept as said.
        val coffee = entry("1", "I had a coffee")
        assertTrue(apply("the meeting was at three", coffee) is Changes.Outcome.Keep)
    }

    @Test
    fun `nothing found is said`() {
        val coffee = entry("1", "I had a coffee")
        assertEquals("I couldn't find pizza", (apply("delete the pizza", coffee) as Changes.Outcome.Say).reply)
        assertNull((change("delete the last one") as Change.Remove).what)
    }
}
