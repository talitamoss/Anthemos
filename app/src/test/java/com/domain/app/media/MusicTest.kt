package com.domain.app.media

import com.domain.app.voice.RuleParser
import com.domain.app.voice.VoiceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicTest {
    @Test
    fun `controls said many ways`() {
        for (s in listOf("play", "play music", "play some music", "resume", "put on some music")) assertEquals(s, Music.Resume, Music.parse(s))
        for (s in listOf("pause", "pause the music", "stop the music", "stop playing")) assertEquals(s, Music.Pause, Music.parse(s))
        for (s in listOf("skip", "next song", "skip this song")) assertEquals(s, Music.Next, Music.parse(s))
        for (s in listOf("previous song", "play the last song", "play that again")) assertEquals(s, Music.Previous, Music.parse(s))
        assertEquals(Music.Louder, Music.parse("turn it up"))
        assertEquals(Music.Quieter, Music.parse("turn the volume down"))
    }

    @Test
    fun `play something by name`() {
        assertEquals(Music.Play("bonobo"), Music.parse("play bonobo"))
        assertEquals(Music.Play("jazz"), Music.parse("play some jazz"))
        assertEquals(Music.Play("the new album by fred again"), Music.parse("play the new album by fred again"))
    }

    @Test
    fun `to Polly, and things that happened stay entries`() {
        val parser = RuleParser()
        assertTrue(parser.parse("Hey Polly, play music") is VoiceRequest.Media)
        assertTrue(parser.parse("I played tennis for an hour") is VoiceRequest.Record)
        assertTrue(parser.parse("stop sharing") is VoiceRequest.Where)
    }
}
