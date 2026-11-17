package com.domain.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RuleParserTest {
    private val now = ZonedDateTime.of(2026, 11, 16, 13, 0, 0, 0, ZoneId.of("Australia/Sydney"))
    private val parser = RuleParser(now = { LocalTime.of(13, 0) }, today = { now })

    private fun record(text: String) = parser.parse(text) as VoiceRequest.Record
    private fun labels(text: String) = record(text).items.map { it.label }

    @Test
    fun `several things in one sentence`() {
        val r = record("I had a chicken sandwich for lunch and I had a salad and I also had a coffee")
        assertEquals(listOf("chicken sandwich", "salad", "coffee"), r.items.map { it.label })
        assertEquals(listOf("food", "food", "drink"), r.items.map { it.kind })
        assertEquals("lunch", r.items[0].meal)
        assertEquals("Logged chicken sandwich, salad and coffee", r.reply)
    }

    @Test
    fun `a meal then what was in it`() {
        assertEquals(listOf("chicken", "salad"), labels("I just had lunch, chicken and a salad"))
        assertEquals(listOf("lunch"), labels("I just had lunch"))
    }

    @Test
    fun `a drink with a meal is the drink`() {
        assertEquals(listOf("coffee"), labels("I had a coffee with lunch"))
        assertEquals(listOf("coffee"), labels("I had coffee for lunch"))
        assertEquals(listOf("lunch", "coffee"), labels("I had lunch and a coffee"))
    }

    @Test
    fun `a time said is when it happened`() {
        val r = record("I had a coffee early this morning at about 8 am")
        assertEquals(listOf("coffee"), r.items.map { it.label })
        assertEquals(LocalTime.of(8, 0), r.at?.toLocalTime())
        assertEquals("Logged coffee at 8:00 am", r.reply)
        // Seven, said at one in the afternoon, is the morning.
        assertEquals(LocalTime.of(7, 0), record("had toast at seven").at?.toLocalTime())
        assertEquals(listOf("toast"), labels("had toast at seven"))
        assertEquals(null, record("I got about 8 hours sleep").at)
    }

    @Test
    fun `parts with different times are separate`() {
        val r = record("hi I've woken up, I woke up at five thirty am, I got about 8 hours sleep and I'm having a coffee now")
        assertEquals(2, r.parts.size)
        assertEquals("hi I've woken up, I woke up at five thirty am", r.parts[0].transcript)
        assertEquals(LocalTime.of(5, 30), r.parts[0].at?.toLocalTime())
        assertEquals(listOf("wake", "wake"), r.parts[0].items.map { it.kind })
        assertEquals(listOf("sleep", "coffee"), r.parts[1].items.map { it.label })
        assertEquals(480, r.parts[1].items[0].minutes)
        assertEquals(null, r.parts[1].at)
    }

    @Test
    fun `waking and bed`() {
        val r = record("hi I've woken up, I got about 8 hours sleep and I'm having a coffee now")
        assertEquals(listOf("woke up", "sleep", "coffee"), r.items.map { it.label })
        assertEquals(listOf("went to bed"), labels("went to sleep about eleven"))
    }

    @Test
    fun `tea as a meal is not a drink`() {
        val r = record("we had pasta for tea")
        assertEquals(listOf("pasta"), r.items.map { it.label })
        assertEquals("dinner", r.items[0].meal)
    }

    @Test
    fun `drinks with counts and groups`() {
        val r = record("I've had two flat whites and a glass of water")
        assertEquals(2, r.items[0].count)
        assertEquals("coffee", r.items[0].group)
        assertEquals(250, r.items[1].ml)
        assertEquals(listOf("green tea"), labels("had a green tea"))
    }

    @Test
    fun `spoken numbers for activities`() {
        val r = record("went for a walk for forty five minutes")
        assertEquals("walk", r.items.single().label)
        assertEquals(45, r.items.single().minutes)
        assertEquals(30, record("half an hour of yoga").items.single().minutes)
    }

    @Test
    fun `anything else is kept as said`() {
        val r = record("the bus was late again")
        assertTrue(r.items.isEmpty())
        assertEquals("Noted", r.reply)
        assertEquals("the bus was late again", r.transcript)
    }

    @Test
    fun `call in its many forms`() {
        assertEquals("sam smith", (parser.parse("Call Sam Smith") as VoiceRequest.Call).name)
        assertEquals("dad", (parser.parse("ring dad") as VoiceRequest.Call).name)
        assertEquals("dad", (parser.parse("give dad a call") as VoiceRequest.Call).name)
        assertEquals("mum", (parser.parse("can you please call mum") as VoiceRequest.Call).name)
        assertEquals("dad", (parser.parse("phone dad on speaker") as VoiceRequest.Call).name)
    }

    @Test
    fun `call misheard is a call that isn't sure`() {
        val cool = parser.parse("cool mum") as VoiceRequest.Call
        assertEquals("mum", cool.name)
        assertFalse(cool.sure)
        assertFalse((parser.parse("coal dad") as VoiceRequest.Call).sure)
        assertTrue((parser.parse("call dad") as VoiceRequest.Call).sure)
    }

    @Test
    fun `teaching a name`() {
        val t = parser.parse("Dad is John Moss") as VoiceRequest.Teach
        assertEquals("dad", t.alias)
        assertEquals("john moss", t.name)
        assertTrue(parser.parse("dinner is pasta") is VoiceRequest.Record)
    }

    @Test
    fun `calendar before calls and questions`() {
        assertTrue(parser.parse("remind me to call dad at five") is VoiceRequest.AddEvent)
        assertTrue(parser.parse("what's on tomorrow") is VoiceRequest.AskDiary)
        assertTrue(parser.parse("how much screen time today") is VoiceRequest.AskPhone)
        assertTrue(parser.parse("I had a coffee") is VoiceRequest.Record)
    }

    @Test
    fun `undo and questions are not entries`() {
        assertTrue(parser.parse("Undo") is VoiceRequest.Undo)
        assertTrue(parser.parse("when did I last call dad") is VoiceRequest.Ask)
        assertTrue(parser.parse("how many coffees have I had this week") is VoiceRequest.Ask)
    }
}
