// app/src/main/java/com/domain/app/pod/Seal.kt
package com.domain.app.pod

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What goes to the pod is sealed on the phone first (Tal, 2026-11-23):
 * AES-256-GCM under a key only the phone and its recovery code hold, so
 * whoever hosts the pod sees scrambled files with meaningless names.
 */
class Seal(val key: ByteArray) {
    init { require(key.size == 32) { "A pod key is 32 bytes" } }

    /** A file name for an item that says nothing about it. */
    fun name(id: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return mac.doFinal("name:$id".toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }

    fun seal(plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(byteArrayOf(VERSION))
        }
        return byteArrayOf(VERSION) + nonce + cipher.doFinal(plain)
    }

    /** The plain bytes; throws when the key is wrong or the file was changed. */
    fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 13 && sealed[0] == VERSION) { "Not an Anthemos file" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
            updateAAD(byteArrayOf(VERSION))
        }
        return cipher.doFinal(sealed, 13, sealed.size - 13)
    }

    /** The key as a recovery code: 52 letters and digits in groups of four. */
    fun recoveryCode(): String = base32(key).chunked(4).joinToString("-")

    companion object {
        private const val VERSION: Byte = 1
        private val random = SecureRandom()
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        fun fresh() = Seal(ByteArray(32).also { random.nextBytes(it) })

        /** From a recovery code as typed: spaces, dashes and case don't matter. Null if it isn't one. */
        fun fromCode(code: String): Seal? {
            val clean = code.uppercase().filter { it.isLetterOrDigit() }
                .replace('0', 'O').replace('1', 'I').replace('8', 'B')
            if (clean.length != 52 || clean.any { it !in ALPHABET }) return null
            return runCatching { Seal(unbase32(clean)) }.getOrNull()
        }

        private fun base32(bytes: ByteArray): String {
            val out = StringBuilder()
            var buffer = 0
            var bits = 0
            for (b in bytes) {
                buffer = (buffer shl 8) or (b.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                    bits -= 5
                }
            }
            if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
            return out.toString()
        }

        private fun unbase32(text: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var buffer = 0
            var bits = 0
            for (c in text) {
                buffer = (buffer shl 5) or ALPHABET.indexOf(c)
                bits += 5
                if (bits >= 8) {
                    out.write((buffer shr (bits - 8)) and 0xff)
                    bits -= 8
                }
            }
            return out.toByteArray().copyOf(32)
        }
    }
}
