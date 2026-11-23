package com.domain.app.pod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Against a real pod server, only when POD_TEST_URL, POD_TEST_ID and POD_TEST_SECRET are set. */
class PodLiveTest {
    private val url = System.getenv("POD_TEST_URL")
    private val id = System.getenv("POD_TEST_ID")
    private val secret = System.getenv("POD_TEST_SECRET")

    @Test
    fun `token, write, list, read, delete`() {
        assumeTrue(url != null && id != null && secret != null)
        val pod = PodClient(url, id, secret)
        pod.check()
        val seal = Seal.fresh()
        val name = seal.name("live")
        pod.put(PodSync.DIR + name, seal.seal("hello".toByteArray()))
        assertEquals(true, name in pod.list(PodSync.DIR))
        assertEquals("hello", String(seal.open(pod.get(PodSync.DIR + name)!!)))
        pod.delete(PodSync.DIR + name)
        assertNull(pod.get(PodSync.DIR + name))
        assertEquals(false, name in pod.list(PodSync.DIR))
        assertEquals(true, runCatching { PodClient(url, id, "wrong").check() }.isFailure)
    }
}
