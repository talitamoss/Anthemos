package com.domain.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookTest {
    @Test
    fun plainAnthemosFollowsTheMainBuilds() {
        val look = Look.parse("""{"name": "Anthemos", "background": "#000000", "text": "#FFFFFF", "accent": "#6ECBB5", "channel": null}""")
        assertEquals(Look.Plain, look)
        assertNull(look.channel)
    }

    @Test
    fun aPersonsOwnCopy() {
        val look = Look.parse("""{"name": "Wren", "background": "#F4EFE6", "text": "1A1A1A", "accent": "#B5446E", "channel": "tal-3f9a"}""")
        assertEquals("Wren", look.name)
        assertEquals(Color(0xFFF4EFE6), look.background)
        assertEquals(Color(0xFF1A1A1A), look.text)
        assertEquals(Color(0xFFB5446E), look.accent)
        assertEquals("tal-3f9a", look.channel)
    }

    @Test
    fun anythingOddFallsBackToPlain() {
        val look = Look.parse("""{"name": "", "background": "blue", "accent": "#12345", "channel": "../../etc"}""")
        assertEquals(Look.Plain.copy(channel = null), look)
    }
}
