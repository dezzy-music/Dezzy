/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Utilidades criptográficas básicas (hex, hashes, HMAC, HKDF y PBKDF2) sin dependencias. */
internal object CryptoUtils {

    val random: SecureRandom by lazy { SecureRandom() }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hmacSha256(key: ByteArray, vararg data: ByteArray): ByteArray = hmac("HmacSHA256", key, *data)

    fun hmacSha512(key: ByteArray, vararg data: ByteArray): ByteArray = hmac("HmacSHA512", key, *data)

    private fun hmac(algorithm: String, key: ByteArray, vararg data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        data.forEach { mac.update(it) }
        return mac.doFinal()
    }

    /** HKDF-Extract (RFC 5869) con SHA-256. */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    /** HKDF-Expand (RFC 5869) con SHA-256. */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = hmacSha256(prk, previous, info, byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - written)
            System.arraycopy(previous, 0, out, written, n)
            written += n
            counter++
        }
        return out
    }

    /**
     * PBKDF2-HMAC-SHA512 implementado a mano: el `SecretKeyFactory` de Android no garantiza
     * cómo convierte la contraseña a bytes, y BIP-39 exige UTF-8 exacto.
     */
    fun pbkdf2Sha512(password: ByteArray, salt: ByteArray, iterations: Int, length: Int = 64): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val blocks = (length + 63) / 64
        val out = ByteArray(blocks * 64)
        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (block ushr 24).toByte(),
                    (block ushr 16).toByte(),
                    (block ushr 8).toByte(),
                    block.toByte(),
                ),
            )
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (block - 1) * 64, 64)
        }
        return out.copyOf(length)
    }

    /** Comparación en tiempo constante (para MAC). */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()

internal fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        chars[i * 2] = HEX_CHARS[v ushr 4]
        chars[i * 2 + 1] = HEX_CHARS[v and 0x0F]
    }
    return String(chars)
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex de longitud impar" }
    return ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) or Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}

internal fun String.isHex64(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
