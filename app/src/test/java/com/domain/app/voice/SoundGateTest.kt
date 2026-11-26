package com.domain.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SoundGateTest {
    private fun chunk(level: Int) = ShortArray(3200) { if (it % 2 == 0) level.toShort() else (-level).toShort() }

    @Test
    fun `only sound above the room goes on, and ends after a short quiet`() {
        val gate = SoundGate()
        // A steady hum: learned as the room, never sound.
        repeat(20) { assertEquals(SoundGate.Quiet, gate.take(chunk(150), 3200)) }
        assertEquals(SoundGate.Start, gate.take(chunk(3000), 3200))
        assertEquals(SoundGate.Sound, gate.take(chunk(3000), 3200))
        // Quiet again: still sound for a moment (between words), then the end.
        val after = (1..6).map { gate.take(chunk(150), 3200) }
        assertEquals(List(5) { SoundGate.Sound } + SoundGate.End, after)
        assertEquals(SoundGate.Quiet, gate.take(chunk(150), 3200))
    }
}
