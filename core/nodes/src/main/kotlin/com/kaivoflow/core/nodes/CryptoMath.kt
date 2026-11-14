package com.kaivoflow.core.nodes

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Small, dependency-free crypto primitives shared by the Crypto, JWT, TOTP and binary Steps.
 * Everything here is pure JVM Kotlin — no Android Keystore, no third-party library.
 */
object CryptoMath {

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hashHex(algorithm: String, text: String): String =
        hex(MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8)))

    fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }

    fun hmacHex(algorithm: String, key: ByteArray, data: ByteArray): String = hex(hmac(algorithm, key, data))

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            append(HEX[(byte.toInt() shr 4) and 0xF])
            append(HEX[byte.toInt() and 0xF])
        }
    }

    /** JWT/JWS style base64url without padding. */
    fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun base64UrlDecode(text: String): ByteArray? = runCatching {
        Base64.getUrlDecoder().decode(text)
    }.getOrNull()

    /** Avoids leaking secret length/content through early-exit string comparison. */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** RFC 4648 base32 decoder (TOTP secrets, authenticator apps). */
    fun base32Decode(text: String): ByteArray? {
        val clean = text.trim().replace("\\s".toRegex(), "").replace("=", "").uppercase()
        if (clean.isEmpty()) return null
        var buffer = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    fun base32Encode(bytes: ByteArray): String {
        var buffer = 0
        var bits = 0
        val sb = StringBuilder()
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
}
