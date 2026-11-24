package com.domain.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceWatchTest {
    private val zeros = ShortArray(3200)
    private val hush = ShortArray(3200) { if (it == 7) 1 else 0 }

    @Test
    fun `only zeros for long enough count as Android's silence`() {
        val watch = SilenceWatch(limitMs = 1_000)
        assertFalse(watch.take(zeros, 3200, 0))
        assertFalse(watch.take(zeros, 3200, 999))
        assertTrue(watch.take(zeros, 3200, 1_000))
    }

    @Test
    fun `any real sound, however quiet, starts it over`() {
        val watch = SilenceWatch(limitMs = 1_000)
        assertFalse(watch.take(zeros, 3200, 0))
        assertFalse(watch.take(hush, 3200, 900))
        assertFalse(watch.take(zeros, 3200, 1_000))
        assertFalse(watch.take(zeros, 3200, 1_999))
        assertTrue(watch.take(zeros, 3200, 2_000))
    }
}
