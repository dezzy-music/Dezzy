/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p.crypto

/**
 * Bech32 (BIP-173) para los identificadores legibles de NIP-19: `npub1…` (clave pública)
 * y `nsec1…` (clave privada).
 */
object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GENERATOR[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray =
        IntArray(hrp.length * 2 + 1).also { out ->
            hrp.forEachIndexed { i, c ->
                out[i] = c.code ushr 5
                out[i + hrp.length + 1] = c.code and 31
            }
            out[hrp.length] = 0
        }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (b in data) {
            val value = b.toInt() and ((1 shl from) - 1)
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.add((acc shl (to - bits)) and maxv)
        } else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) {
            return null
        }
        return out.toIntArray()
    }

    fun encode(hrp: String, data: ByteArray): String {
        val values = requireNotNull(convertBits(data, 8, 5, true))
        val checksumInput = hrpExpand(hrp) + values + IntArray(6)
        val mod = polymod(checksumInput) xor 1
        val checksum = IntArray(6) { i -> (mod ushr (5 * (5 - i))) and 31 }
        return buildString {
            append(hrp).append('1')
            (values + checksum).forEach { append(CHARSET[it]) }
        }
    }

    /** Devuelve (hrp, datos) o null si el texto no es Bech32 válido. */
    fun decode(text: String): Pair<String, ByteArray>? {
        val lower = text.trim().lowercase()
        val sep = lower.lastIndexOf('1')
        if (sep < 1 || sep + 7 > lower.length || lower.length > 1000) return null
        val hrp = lower.substring(0, sep)
        val values = IntArray(lower.length - sep - 1)
        for (i in values.indices) {
            val idx = CHARSET.indexOf(lower[sep + 1 + i])
            if (idx < 0) return null
            values[i] = idx
        }
        if (polymod(hrpExpand(hrp) + values) != 1) return null
        val payload = values.copyOfRange(0, values.size - 6)
        val bytes = ByteArray(payload.size) { payload[it].toByte() }
        val data = convertBits(bytes, 5, 8, false) ?: return null
        return hrp to ByteArray(data.size) { data[it].toByte() }
    }

    fun npub(publicKeyHex: String): String = encode("npub", publicKeyHex.hexToBytes())

    /** Acepta `npub1…`, `nostr:npub1…` o hex de 64; devuelve la clave pública en hex. */
    fun parsePublicKey(input: String): String? {
        val clean = input.trim().removePrefix("nostr:").lowercase()
        if (clean.isHex64()) return clean
        val (hrp, data) = decode(clean) ?: return null
        return if (hrp == "npub" && data.size == 32) data.toHex() else null
    }

    fun nsec(privateKey: ByteArray): String = encode("nsec", privateKey)

    fun parsePrivateKey(input: String): ByteArray? {
        val (hrp, data) = decode(input.trim()) ?: return null
        return if (hrp == "nsec" && data.size == 32 && Secp256k1.isValidPrivateKey(data)) data else null
    }
}
