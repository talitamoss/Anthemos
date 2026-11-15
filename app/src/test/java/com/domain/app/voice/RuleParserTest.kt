package com.domain.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class RuleParserTest {
    private val parser = RuleParser(now = { LocalTime.of(13, 0) })

    private fun record(text: String) = parser.parse(text) as VoiceRequest.Record

    @Test
    fun `lunch with what was eaten`() {
        val r = record("I just had lunch, chicken and a salad")
        assertEquals("food", r.pluginId)
        assertEquals("Lunch", r.value["mealType"])
        assertEquals("chicken and a salad", r.value["notes"])
        assertEquals("Lunch logged", r.reply)
    }

    @Test
    fun `food for a meal`() {
        val r = record("I had soup and bread for dinner.")
        assertEquals("Dinner", r.value["mealType"])
        assertEquals("soup and bread", r.value["notes"])
    }

    @Test
    fun `eating without a meal takes the time of day`() {
        val r = record("I ate a banana")
        assertEquals("Lunch", r.value["mealType"])
        assertEquals("a banana", r.value["notes"])
    }

    @Test
    fun `a call keeps the name as said`() {
        val r = parser.parse("Call Sam Smith") as VoiceRequest.Call
        assertEquals("Sam Smith", r.name)
    }

    @Test
    fun `water by the glass`() {
        assertEquals(500, record("I drank two glasses of water").value["amount"])
        assertEquals(250, record("had a glass of water").value["amount"])
        assertEquals(330, record("I drank 330 ml of water").value["amount"])
    }

    @Test
    fun `coffee is caffeine and steak is not tea`() {
        assertEquals("caffeine", record("I just had a flat white").pluginId)
        assertEquals("food", record("I had steak for dinner").pluginId)
    }

    @Test
    fun `movement with a length`() {
        val r = record("I went for a walk for 40 minutes")
        assertEquals("movement", r.pluginId)
        assertEquals("Walk", r.value["type"])
        assertEquals(40, r.value["duration"])
        assertEquals(30, record("went for a run, half an hour").value["duration"])
    }

    @Test
    fun `a note keeps the words after the cue`() {
        val r = record("Note that the gate code is 4471")
        assertEquals("journal", r.pluginId)
        assertEquals("the gate code is 4471", r.value["entry"])
    }

    @Test
    fun `anything else is kept as a journal entry`() {
        val r = record("Feeling a bit tired after the drive")
        assertEquals("journal", r.pluginId)
        assertEquals("Feeling a bit tired after the drive", r.value["entry"])
    }

    @Test
    fun undo() {
        assertTrue(parser.parse("Undo that") is VoiceRequest.Undo)
    }
}
