package com.domain.app.pod

import com.domain.app.core.storage.entity.DataPointEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Signature
import java.time.Instant

class SealTest {
    @Test
    fun `sealed opens with its key only`() {
        val seal = Seal.fresh()
        val sealed = seal.seal("a coffee at 9".toByteArray())
        assertEquals("a coffee at 9", String(seal.open(sealed)))
        assertTrue(runCatching { Seal.fresh().open(sealed) }.isFailure)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertTrue(runCatching { seal.open(sealed) }.isFailure)
    }

    @Test
    fun `the recovery code brings the key back, however it is typed`() {
        val seal = Seal.fresh()
        val code = seal.recoveryCode()
        assertEquals(13, code.split("-").size)
        assertArrayEquals(seal.key, Seal.fromCode(code)!!.key)
        assertArrayEquals(seal.key, Seal.fromCode(" " + code.lowercase().replace("-", " ") + " ")!!.key)
        assertNull(Seal.fromCode("not a code"))
    }

    @Test
    fun `names say nothing and stay the same`() {
        val seal = Seal.fresh()
        assertEquals(seal.name("e1"), seal.name("e1"))
        assertNotEquals(seal.name("e1"), seal.name("e2"))
        assertNotEquals(seal.name("e1"), Seal.fresh().name("e1"))
        assertEquals(32, seal.name("e1").length)
    }

    @Test
    fun `an item goes and comes back whole`() {
        val e = DataPointEntity("e1", "entry", Instant.ofEpochMilli(1_700_000_000_000), "entry", "{\"words\":\"tea\"}", "{\"a\":\"b\"}", "voice", 2, true, Instant.ofEpochMilli(1_700_000_000_500), extra3 = 4)
        assertEquals(e.copy(synced = false), PodSync.decode(PodSync.encode(e)))
    }

    @Test
    fun `the proof is a signed ES256 JWT`() {
        val keys = Dpop.generate()
        val jwt = Dpop(keys).proof("GET", "https://pod.example/tal/x?y=1#z", "tok")
        val parts = jwt.split(".")
        assertEquals(3, parts.size)
        val claims = String(okio.ByteString.Companion.run { parts[1].decodeBase64()!!.toByteArray() })
        assertTrue(claims.contains("\"htu\":\"https://pod.example/tal/x\""))
        assertTrue(claims.contains("\"ath\""))
        val ok = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(keys.public)
            update((parts[0] + "." + parts[1]).toByteArray())
            verify(okio.ByteString.Companion.run { parts[2].decodeBase64()!!.toByteArray() })
        }
        assertTrue(ok)
    }
}
