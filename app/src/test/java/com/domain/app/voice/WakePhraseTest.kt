package com.domain.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {

    @Test
    fun `the phrase on its own, said clearly, counts`() {
        assertTrue(WakePhrase.heard(listOf("hey" to 1.0, "poly" to 0.95)))
        assertTrue(WakePhrase.heard(listOf("hey" to 0.9, "paulie" to 0.85)))
    }

    @Test
    fun `the name, its sureness split between alike spellings, still counts`() {
        // Tal's phone, 2.35: hey 100%, poly 38%.
        assertTrue(WakePhrase.heard(listOf("hey" to 1.0, "poly" to 0.38)))
    }

    @Test
    fun `an unsure guess does not`() {
        assertFalse(WakePhrase.heard(listOf("hey" to 1.0, "poly" to 0.2)))
        assertFalse(WakePhrase.heard(listOf("hey" to 0.5, "poly" to 1.0)))
    }

    @Test
    fun `the phrase inside something longer does not`() {
        assertFalse(WakePhrase.heard(listOf("so" to 1.0, "hey" to 1.0, "poly" to 1.0)))
        assertFalse(WakePhrase.heard(listOf("hey" to 1.0, "poly" to 1.0, "[unk]" to 1.0)))
    }

    @Test
    fun `a near sound does not`() {
        assertFalse(WakePhrase.heard(listOf("hey" to 1.0, "holy" to 1.0)))
        assertFalse(WakePhrase.heard(listOf("hey" to 1.0)))
        assertFalse(WakePhrase.heard(emptyList()))
    }

    @Test
    fun `the grammar holds the phrases and the decoys`() {
        assertTrue(WakePhrase.GRAMMAR.contains("\"hey poly\""))
        assertTrue(WakePhrase.GRAMMAR.contains("\"holy\""))
        assertTrue(WakePhrase.GRAMMAR.contains("\"[unk]\""))
        // One spelling per sound, so the name's sureness splits less.
        assertFalse(WakePhrase.GRAMMAR.contains("\"hey paulie\""))
    }
}
