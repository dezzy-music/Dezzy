/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p.crypto

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger

/**
 * Curva secp256k1 (la de Bitcoin y Nostr) con firmas Schnorr BIP-340.
 *
 * La aritmética de la curva la hace Bouncy Castle (implementación rápida y probada); aquí
 * sólo está el esquema BIP-340 y el secreto compartido de NIP-44.
 */
object Secp256k1 {

    private val params = CustomNamedCurves.getByName("secp256k1")
    private val curve = params.curve
    private val g: ECPoint = params.g
    val order: BigInteger = params.n
    private val fieldPrime: BigInteger = curve.field.characteristic

    fun isValidPrivateKey(key: ByteArray): Boolean {
        if (key.size != 32) return false
        val d = BigInteger(1, key)
        return d.signum() > 0 && d < order
    }

    fun generatePrivateKey(): ByteArray {
        while (true) {
            val candidate = CryptoUtils.randomBytes(32)
            if (isValidPrivateKey(candidate)) return candidate
        }
    }

    /** Clave pública «x-only» de 32 bytes (formato Nostr). */
    fun xOnlyPublicKey(privateKey: ByteArray): ByteArray =
        g.multiply(BigInteger(1, privateKey)).normalize().affineXCoord.encoded

    /** Clave pública comprimida de 33 bytes (para la derivación BIP-32). */
    fun compressedPublicKey(privateKey: ByteArray): ByteArray =
        g.multiply(BigInteger(1, privateKey)).normalize().getEncoded(true)

    fun to32(value: BigInteger): ByteArray = BigIntegers.asUnsignedByteArray(32, value)

    /** Punto con la y par cuya x es [x] (lift_x de BIP-340), o null si no está en la curva. */
    private fun liftX(x: ByteArray): ECPoint? {
        if (x.size != 32 || BigInteger(1, x) >= fieldPrime) return null
        return runCatching { curve.decodePoint(byteArrayOf(0x02) + x).normalize() }.getOrNull()
    }

    private fun taggedHash(tag: String, vararg data: ByteArray): ByteArray {
        val tagHash = CryptoUtils.sha256(tag.toByteArray(Charsets.UTF_8))
        return CryptoUtils.sha256(tagHash, tagHash, *data)
    }

    private fun ECPoint.hasEvenY(): Boolean = !affineYCoord.toBigInteger().testBit(0)

    /** Firma Schnorr BIP-340 de un mensaje de 32 bytes. */
    fun sign(message: ByteArray, privateKey: ByteArray, auxRand: ByteArray = CryptoUtils.randomBytes(32)): ByteArray {
        require(message.size == 32) { "el mensaje debe medir 32 bytes" }
        var d = BigInteger(1, privateKey)
        require(d.signum() > 0 && d < order) { "clave privada inválida" }
        val p = g.multiply(d).normalize()
        if (!p.hasEvenY()) d = order.subtract(d)
        val px = p.affineXCoord.encoded

        val dBytes = to32(d)
        val auxHash = taggedHash("BIP0340/aux", auxRand)
        val t = ByteArray(32) { i -> (dBytes[i].toInt() xor auxHash[i].toInt()).toByte() }
        val k0 = BigInteger(1, taggedHash("BIP0340/nonce", t, px, message)).mod(order)
        check(k0.signum() != 0) { "nonce nulo" }
        val r = g.multiply(k0).normalize()
        val k = if (r.hasEvenY()) k0 else order.subtract(k0)
        val rx = r.affineXCoord.encoded
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rx, px, message)).mod(order)
        val s = k.add(e.multiply(d)).mod(order)
        return rx + to32(s)
    }

    /** Verifica una firma Schnorr BIP-340. Nunca lanza: devuelve false ante datos raros. */
    fun verify(message: ByteArray, publicKey: ByteArray, signature: ByteArray): Boolean {
        if (message.size != 32 || signature.size != 64) return false
        return runCatching {
            val p = liftX(publicKey) ?: return@runCatching false
            val rBytes = signature.copyOfRange(0, 32)
            val r = BigInteger(1, rBytes)
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            if (r >= fieldPrime || s >= order) return@runCatching false
            val e = BigInteger(1, taggedHash("BIP0340/challenge", rBytes, publicKey, message)).mod(order)
            // R = s·G − e·P
            val point = ECAlgorithms.sumOfTwoMultiplies(g, s, p, order.subtract(e)).normalize()
            !point.isInfinity && point.hasEvenY() && point.affineXCoord.toBigInteger() == r
        }.getOrDefault(false)
    }

    /** Coordenada x de priv·Pub: el secreto compartido sin hash que pide NIP-44. */
    fun sharedSecretX(privateKey: ByteArray, publicKeyX: ByteArray): ByteArray {
        val p = requireNotNull(liftX(publicKeyX)) { "clave pública inválida" }
        return p.multiply(BigInteger(1, privateKey)).normalize().affineXCoord.encoded
    }

    /** (a + b) mod n como 32 bytes; usado por la derivación BIP-32. */
    fun addModOrder(a: ByteArray, b: ByteArray): ByteArray =
        to32(BigInteger(1, a).add(BigInteger(1, b)).mod(order))
}
