/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p.crypto

import java.util.Base64

/**
 * Cifrado NIP-44 versión 2 (el estándar de mensajes cifrados de Nostr):
 * ECDH secp256k1 → HKDF-SHA256 → ChaCha20 + HMAC-SHA256, con relleno para ocultar la longitud.
 * https://github.com/nostr-protocol/nips/blob/master/44.md
 */
object Nip44 {

    private val SALT = "nip44-v2".toByteArray(Charsets.UTF_8)

    /** Clave de conversación entre dos personas; es la misma vista desde cada lado. */
    fun conversationKey(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray =
        CryptoUtils.hkdfExtract(SALT, Secp256k1.sharedSecretX(privateKey, peerPublicKey))

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray = CryptoUtils.randomBytes(32)): String {
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val padded = pad(plaintext)
        val ciphertext = ChaCha20.xor(chachaKey, chachaNonce, padded)
        val mac = CryptoUtils.hmacSha256(hmacKey, nonce, ciphertext)
        val out = byteArrayOf(2) + nonce + ciphertext + mac
        return Base64.getEncoder().encodeToString(out)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        require(payload.isNotEmpty() && payload[0] != '#') { "versión de cifrado no soportada" }
        require(payload.length in 132..87472) { "longitud de mensaje inválida" }
        val data = Base64.getDecoder().decode(payload)
        require(data.size in 99..65603) { "longitud de datos inválida" }
        require(data[0].toInt() == 2) { "versión ${data[0]} no soportada" }
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val expected = CryptoUtils.hmacSha256(hmacKey, nonce, ciphertext)
        require(CryptoUtils.constantTimeEquals(expected, mac)) { "MAC inválido" }
        return unpad(ChaCha20.xor(chachaKey, chachaNonce, ciphertext))
    }

    private fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        require(conversationKey.size == 32 && nonce.size == 32)
        val keys = CryptoUtils.hkdfExpand(conversationKey, nonce, 76)
        return Triple(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), keys.copyOfRange(44, 76))
    }

    internal fun calcPaddedLength(unpaddedLength: Int): Int {
        if (unpaddedLength <= 32) return 32
        val nextPower = 1 shl (31 - Integer.numberOfLeadingZeros(unpaddedLength - 1) + 1)
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLength - 1) / chunk + 1)
    }

    private fun pad(plaintext: String): ByteArray {
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..65535) { "mensaje vacío o demasiado largo" }
        val out = ByteArray(2 + calcPaddedLength(bytes.size))
        out[0] = (bytes.size ushr 8).toByte()
        out[1] = bytes.size.toByte()
        System.arraycopy(bytes, 0, out, 2, bytes.size)
        return out
    }

    private fun unpad(padded: ByteArray): String {
        val length = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        require(length > 0 && padded.size == 2 + calcPaddedLength(length)) { "relleno inválido" }
        return String(padded, 2, length, Charsets.UTF_8)
    }
}

/** ChaCha20 (RFC 8439) con contador inicial 0, tal como lo usa NIP-44. */
internal object ChaCha20 {

    fun xor(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = le32(key, i * 4)
        state[12] = 0
        for (i in 0 until 3) state[13 + i] = le32(nonce, i * 4)

        val out = ByteArray(input.size)
        val block = ByteArray(64)
        val working = IntArray(16)
        var offset = 0
        while (offset < input.size) {
            state.copyInto(working)
            repeat(10) {
                quarter(working, 0, 4, 8, 12)
                quarter(working, 1, 5, 9, 13)
                quarter(working, 2, 6, 10, 14)
                quarter(working, 3, 7, 11, 15)
                quarter(working, 0, 5, 10, 15)
                quarter(working, 1, 6, 11, 12)
                quarter(working, 2, 7, 8, 13)
                quarter(working, 3, 4, 9, 14)
            }
            for (i in 0 until 16) {
                val v = working[i] + state[i]
                block[i * 4] = v.toByte()
                block[i * 4 + 1] = (v ushr 8).toByte()
                block[i * 4 + 2] = (v ushr 16).toByte()
                block[i * 4 + 3] = (v ushr 24).toByte()
            }
            val n = minOf(64, input.size - offset)
            for (i in 0 until n) out[offset + i] = (input[offset + i].toInt() xor block[i].toInt()).toByte()
            offset += n
            state[12]++
        }
        return out
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun quarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }
}
