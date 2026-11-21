package com.domain.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CommandsTest {
    private val now = ZonedDateTime.of(2026, 11, 18, 13, 0, 0, 0, ZoneId.of("Australia/Sydney"))
    private val parser = RuleParser(now = { LocalTime.of(13, 0) }, today = { now })
    private val commands = Commands.focuses(parser).flatMap { it.commands }

    @Test
    fun `every example lands on its own command`() {
        val wrong = commands.flatMap { command ->
            command.examples.mapNotNull { example ->
                val got = parser.match(example).command.id
                if (got == command.id) null else "\"$example\" is ${command.id} but went to $got"
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `every command is tried exactly once, and has examples`() {
        val ids = commands.map { it.id }
        assertEquals(ids.sorted(), Commands.order.sorted())
        assertEquals(ids.size, ids.toSet().size)
        commands.forEach { assertTrue("${it.id} needs examples", it.examples.isNotEmpty()) }
        // Ids name their focus.
        Commands.focuses(parser).forEach { focus -> focus.commands.forEach { assertTrue(it.id.startsWith("${focus.id}.")) } }
    }

    @Test
    fun `anything else is logged`() {
        assertEquals("log.record", Commands.order.last())
        assertEquals("log.record", parser.match("the bus was late").command.id)
    }
}
