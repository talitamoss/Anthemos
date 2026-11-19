package com.domain.app.place

import com.domain.app.voice.RuleParser
import com.domain.app.voice.VoiceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class WhereaboutsTest {
    private val now = ZonedDateTime.of(2026, 11, 18, 15, 0, 0, 0, ZoneId.of("Australia/Sydney"))
    private val parser = RuleParser(now = { LocalTime.of(15, 0) }, today = { now })

    private fun where(text: String) = (parser.parse(text) as VoiceRequest.Where).ask

    @Test
    fun `naming a place`() {
        assertEquals(Whereabouts.Name("home"), where("This is home"))
        assertEquals(Whereabouts.Name("mum's place"), where("this is mum's place"))
        assertEquals(Whereabouts.Name("gym"), where("call this place gym"))
        assertEquals(Whereabouts.Name("the gym"), where("save this place as the gym"))
        // Not places: kept as what they are.
        assertTrue(parser.parse("this is my third coffee") !is VoiceRequest.Where)
        assertTrue(parser.parse("this is great") !is VoiceRequest.Where)
    }

    @Test
    fun `where am I and where was I`() {
        assertEquals(Whereabouts.WhereAmI, where("where am I"))
        val was = where("where was I yesterday") as Whereabouts.WhereWas
        assertEquals(now.toLocalDate().minusDays(1), was.day)
        assertEquals("yesterday", was.phrase)
        assertEquals("today", (where("where have I been") as Whereabouts.WhereWas).phrase)
    }

    @Test
    fun `the trail`() {
        assertEquals(Whereabouts.Trail(true), where("start tracking"))
        assertEquals(Whereabouts.Trail(true), where("track my location"))
        assertEquals(Whereabouts.Trail(false), where("stop tracking"))
    }

    @Test
    fun `sharing live location`() {
        val hour = where("share my location with Ana") as Whereabouts.Share
        assertEquals(listOf("ana"), hour.names)
        assertEquals(now.plusHours(1), hour.until)

        val two = where("share my live location with Ana and Sam for two hours") as Whereabouts.Share
        assertEquals(listOf("ana", "sam"), two.names)
        assertEquals(now.plusHours(2), two.until)

        val five = where("let dad see where I am until five") as Whereabouts.Share
        assertEquals(listOf("dad"), five.names)
        assertEquals(17, five.until.hour)

        val jye = where("share live tracking with Jye on Kronk") as Whereabouts.Share
        assertEquals(listOf("jye"), jye.names)
        assertEquals(now.plusHours(1), jye.until)
        assertEquals(listOf("jye"), (where("share my location with Jye on Kronk for two hours") as Whereabouts.Share).names)
        assertEquals(Whereabouts.StopSharing("jye"), where("stop sharing live tracking with Jye"))

        val long = where("share my location with Ana for 30 hours") as Whereabouts.Share
        assertEquals(now.plusHours(24), long.until)
    }

    @Test
    fun `stopping and connecting`() {
        assertEquals(Whereabouts.StopSharing(null), where("stop sharing"))
        assertEquals(Whereabouts.StopSharing(null), where("stop sharing my location"))
        assertEquals(Whereabouts.StopSharing("ana"), where("stop sharing with Ana"))
        assertEquals(Whereabouts.LinkKronk, where("connect to Kronk"))
        assertEquals(Whereabouts.LinkKronk, where("connect to crank"))
        assertEquals(listOf("pago"), (where("share my location with pago on cronk") as Whereabouts.Share).names)
        assertNull(Whereabouts.parse("i had a coffee", now))
    }
}
