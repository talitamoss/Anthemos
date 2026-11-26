// app/src/main/java/com/domain/app/pod/Dpop.kt
package com.domain.app.pod

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Proof of possession for Solid (DPoP, RFC 9449): a fresh P-256 key for
 * the session, and a small signed note for every request saying which
 * method and address it is for, so a token alone is no use to anyone
 * who copies it.
 */
class Dpop(private val keys: KeyPair = generate()) {

    /** The signed note for [method] on [url]; [token] binds it to an access token. */
    fun proof(method: String, url: String, token: String? = null, now: Long = System.currentTimeMillis() / 1000): String {
        val pub = keys.public as ECPublicKey
        val header = buildJsonObject {
            put("typ", "dpop+jwt")
            put("alg", "ES256")
            put("jwk", buildJsonObject {
                put("kty", "EC")
                put("crv", "P-256")
                put("x", b64(unsigned(pub.w.affineX)))
                put("y", b64(unsigned(pub.w.affineY)))
            })
        }
        val claims = buildJsonObject {
            put("htm", method)
            put("htu", url.substringBefore('#').substringBefore('?'))
            put("iat", now)
            put("jti", UUID.randomUUID().toString())
            if (token != null) put("ath", b64(token.encodeUtf8().sha256().toByteArray()))
        }
        val input = b64(header.toString().toByteArray()) + "." + b64(claims.toString().toByteArray())
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(input.toByteArray())
            sign()
        }
        return input + "." + b64(raw(der))
    }

    companion object {
        fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        internal fun b64(bytes: ByteArray) = bytes.toByteString().base64Url().trimEnd('=')

        /** A coordinate as exactly 32 bytes. */
        private fun unsigned(n: BigInteger): ByteArray {
            val b = n.toByteArray()
            return when {
                b.size == 32 -> b
                b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                else -> ByteArray(32 - b.size) + b
            }
        }

        /** Java signs ECDSA as DER; JWS wants r and s, 32 bytes each. */
        internal fun raw(der: ByteArray): ByteArray {
            var i = 2
            if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
            fun int(): BigInteger {
                check(der[i].toInt() == 0x02) { "Not a DER integer" }
                val len = der[i + 1].toInt() and 0xff
                val v = BigInteger(1, der.copyOfRange(i + 2, i + 2 + len))
                i += 2 + len
                return v
            }
            return unsigned(int()) + unsigned(int())
        }
    }
}
