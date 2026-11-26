package com.domain.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The kinds added for more of a life (docs/anthemos-2.md, "Kinds"). */
class KindsTest {
    private val now = ZonedDateTime.of(2026, 11, 22, 18, 0, 0, 0, ZoneId.of("Australia/Sydney"))
    private val parser = RuleParser(now = { LocalTime.of(18, 0) }, today = { now })

    private fun items(said: String) = (parser.parse(said) as VoiceRequest.Record).items
    private fun kinds(said: String) = items(said).map { "${it.kind}:${it.label}" }
    private fun value(said: String) = items(said).single().details["value"]

    @Test
    fun `measures`() {
        assertEquals("58", value("resting heart rate 58"))
        assertEquals("resting heart rate", items("resting heart rate was fifty eight")[0].label)
        assertEquals("58", value("resting heart rate was fifty eight"))
        assertEquals("120/80", value("blood pressure 120 over 80"))
        assertEquals("120/80", value("my bp was 120/80"))
        assertEquals("120/80", value("blood pressure one twenty over eighty"))
        assertEquals("37.8", value("temperature 37.8"))
        assertEquals("37.8", value("temperature thirty seven point eight"))
        assertEquals("5.6", value("blood sugar 5.6"))
        assertEquals("97", value("oxygen 97"))
    }

    @Test
    fun `steps`() {
        assertEquals(8000, items("walked 8000 steps").single().count)
        assertEquals(12000, items("did 12,000 steps today").single().count)
        assertEquals(10000, items("ten thousand steps").single().count)
        assertEquals(9000, items("9k steps").single().count)
    }

    @Test
    fun `smoking and cutting out`() {
        assertEquals(listOf("smoking:cigarette"), kinds("had a cigarette"))
        assertEquals(3, items("smoked three cigarettes").single().count)
        assertEquals(listOf("smoking:vape"), kinds("vaped after lunch"))
        assertEquals(listOf("smoking:none"), kinds("no cigarettes today"))
        assertEquals(listOf("smoking:quit"), kinds("I quit smoking"))
        assertEquals(listOf("food:smoked salmon"), kinds("had smoked salmon"))
        assertEquals(listOf("cannabis:cannabis"), kinds("smoked a joint"))
        assertEquals(listOf("drink:no alcohol"), kinds("didn't drink today"))
        assertEquals(listOf("drink:no alcohol"), kinds("alcohol-free day"))
    }

    @Test
    fun `body and mind`() {
        assertEquals(listOf("injury:sprained ankle"), kinds("sprained my ankle"))
        assertEquals(listOf("craving:chocolate"), kinds("craving chocolate"))
        assertEquals(listOf("fasting:fasting"), kinds("fasted for 16 hours"))
        assertEquals(960, items("fasted for 16 hours").single().minutes)
        assertEquals(listOf("activity:meditation"), kinds("meditated for 20 minutes"))
        assertEquals(listOf("activity:breathwork"), kinds("did some breathwork"))
        assertEquals(listOf("care:flossed"), kinds("flossed"))
        assertEquals(listOf("care:cold plunge"), kinds("had an ice bath"))
        assertEquals(listOf("care:sauna"), kinds("sauna for 20 minutes"))
        assertEquals(listOf("worry:money"), kinds("worried about money"))
        assertEquals(listOf("journal:journal"), kinds("journaled this morning"))
        assertEquals(listOf("spirit:prayer"), kinds("prayed"))
    }

    @Test
    fun `people, making and the rest`() {
        assertEquals("sam", items("had an argument with Sam about the dishes").single().details["who"])
        assertEquals(listOf("creative:guitar"), kinds("played guitar for an hour"))
        assertEquals(listOf("creative:painted"), kinds("painted"))
        assertEquals(listOf("scrolling:instagram"), kinds("on instagram for an hour"))
        assertEquals(60, items("on instagram for an hour").single().minutes)
        assertEquals(listOf("volunteer:volunteering"), kinds("volunteered at the food bank"))
        assertEquals(listOf("pet:walked the dog", "activity:walk"), kinds("walked the dog"))
        assertEquals(listOf("travel:train"), kinds("took the train"))
        assertEquals(listOf("goal:run a marathon"), kinds("my goal is to run a marathon"))
        assertEquals(listOf("idea:a new song"), kinds("had an idea for a new song"))
    }

    @Test
    fun `what was read before still is`() {
        assertEquals(listOf("activity:tennis"), kinds("played tennis with Ana"))
        assertEquals(listOf("drink:coffee"), kinds("I had a coffee"))
        assertEquals(listOf("mood:good day"), kinds("it was a good day"))
        assertEquals(listOf("study:reading"), kinds("read for an hour"))
    }
}
