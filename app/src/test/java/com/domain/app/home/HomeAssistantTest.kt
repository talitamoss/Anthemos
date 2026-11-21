package com.domain.app.home

import com.domain.app.voice.RuleParser
import com.domain.app.voice.VoiceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantTest {
    private val parser = RuleParser()

    @Test
    fun `house commands go to Home Assistant`() {
        for (said in listOf(
            "Hey Polly, turn off the lounge lights", "turn the kitchen light on", "set the heating to 20",
            "lock the front door", "is the back door locked", "dim the bedroom lights", "close all the blinds",
            "what's the temperature inside", "start the vacuum"
        )) assertTrue(said, parser.parse(said) is VoiceRequest.Home)
    }

    @Test
    fun `other things stay where they were`() {
        assertTrue(parser.parse("turn it up") is VoiceRequest.Media)
        assertTrue(parser.parse("pause") is VoiceRequest.Media)
        assertTrue(parser.parse("I turned off the oven at six") is VoiceRequest.Record)
        assertTrue(parser.parse("remind me to lock the front door at ten") is VoiceRequest.AddEvent)
        assertFalse(HomeAssistant.isHomeCommand("is the dentist on thursday"))
    }

    @Test
    fun `addresses as typed`() {
        assertEquals("http://192.168.1.10:8123", HomeAssistant.normalise("192.168.1.10"))
        assertEquals("http://homeassistant.local:8123", HomeAssistant.normalise("homeassistant.local:8123/"))
        assertEquals("https://ha.example.org", HomeAssistant.normalise("https://ha.example.org").removeSuffix(":8123"))
    }
}
