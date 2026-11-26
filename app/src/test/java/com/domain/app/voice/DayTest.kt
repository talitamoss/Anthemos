package com.domain.app.voice

import com.domain.app.prompts.PromptRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DayTest {
    private val now = ZonedDateTime.of(2026, 11, 18, 18, 0, 0, 0, ZoneId.of("Australia/Sydney"))
    private val parser = RuleParser(now = { LocalTime.of(18, 0) }, today = { now })
    private val reader = RuleReader { LocalTime.of(18, 0) }

    private fun items(said: String) = (parser.parse(said) as VoiceRequest.Record).items
    private fun entry(said: String) = Entry("e", now.toInstant(), said, items(said), "t")
    private fun ask(said: String, round: Int = 1) = PromptRules.followUp(entry(said), round)?.text

    @Test
    fun `what each is read as`() {
        fun kinds(said: String) = items(said).map { "${it.kind}:${it.label}" }
        assertEquals(listOf("meeting:meeting"), kinds("I had a 2-hour meeting today"))
        assertEquals(120, items("I had a 2-hour meeting today")[0].minutes)
        assertEquals("sam", items("had a meeting with Sam about the budget")[0].details["who"])
        assertEquals("the budget", items("had a meeting with Sam about the budget")[0].details["about"])
        assertEquals(listOf("medication:medication"), kinds("I took my meds"))
        assertEquals(listOf("medication:panadol"), kinds("took two panadol"))
        assertEquals(listOf("symptom:headache"), kinds("I've got a headache"))
        assertEquals(listOf("symptom:sore back"), kinds("I have a sore back"))
        assertEquals(listOf("period:period started"), kinds("my period started"))
        assertEquals(listOf("mood:good day"), kinds("it was a good day"))
        assertEquals(listOf("mood:stressed"), kinds("feeling stressed about work"))
        assertEquals(listOf("energy:exhausted"), kinds("I'm exhausted"))
        assertEquals(listOf("social:saw sam", "drink:coffee"), kinds("had coffee with Sam"))
        assertEquals(listOf("social:saw a friend"), kinds("caught up with a friend"))
        assertEquals(listOf("social:saw jo"), kinds("met Jo for lunch"))
        assertEquals(listOf("media:movie"), kinds("watched a movie"))
        assertEquals(listOf("media:severance"), kinds("watched severance"))
        assertEquals(listOf("call:mum"), kinds("called mum for an hour"))
        assertEquals(listOf("work:work"), kinds("worked on the website for three hours"))
        assertEquals(listOf("activity:workout"), kinds("worked out"))
        assertEquals(listOf("cooking:cooked", "food:pasta"), kinds("cooked pasta"))
        assertEquals(listOf("shopping:groceries"), kinds("did the groceries"))
        assertEquals(listOf("chore:did the laundry"), kinds("did the laundry"))
        assertEquals(listOf("activity:tennis"), kinds("played tennis with Ana"))
        assertEquals(listOf("study:reading"), kinds("read for an hour"))
        assertEquals(listOf("weight:weight"), kinds("I weighed 72.5 kg"))
        assertEquals(listOf("drink:coffee"), kinds("I had a coffee"))
        assertEquals(listOf("food:chicken sandwich"), kinds("I had a chicken sandwich"))
    }

    @Test
    fun `the questions asked after`() {
        assertEquals("Who was it with?", ask("I had a 2-hour meeting today"))
        assertEquals("What was it about?", ask("had a meeting with Sam"))
        assertNull(ask("had a meeting with Sam about the budget"))
        assertEquals("What did you take?", ask("I took my meds"))
        assertEquals("How much?", ask("took panadol"))
        assertEquals("How bad, out of ten?", ask("I've got a headache"))
        assertEquals("Light, medium or heavy?", ask("my period started"))
        assertEquals("Out of ten?", ask("feeling flat"))
        assertEquals("Who was it?", ask("caught up with a friend"))
        assertEquals("How was it?", ask("had coffee with Sam"))
        assertEquals("What was it?", ask("watched a movie"))
        assertEquals("What did you make?", ask("cooked dinner"))
        assertNull(ask("cooked pasta").takeIf { it == "What did you make?" })
        assertEquals("How much did it come to?", ask("did the groceries"))
        assertEquals("How long was the tennis?", ask("played tennis"))
        assertEquals("What on?", ask("spent \$40"))
        assertNull(ask("did the laundry"))
        assertNull(ask("I did a poo"))
        assertNull(ask("I had a 2-hour meeting today", round = 3))
    }

    @Test
    fun `answers fill the detail`() {
        val meeting = items("I had a 2-hour meeting today")[0]
        assertEquals("sam and jo", Day.fill(meeting, "who", "It was with Sam and Jo", reader)?.details?.get("who"))
        val headache = items("I've got a headache")[0]
        assertEquals("6/10", Day.fill(headache, "score", "about a six", reader)?.details?.get("score"))
        assertNull(Day.fill(headache, "score", "pretty bad", reader))
        assertEquals("heavy", Day.fill(items("my period started")[0], "flow", "Heavy.", reader)?.details?.get("flow"))
    }
}
