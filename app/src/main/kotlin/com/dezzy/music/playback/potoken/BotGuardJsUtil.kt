/*
 * Dezzy Project (2026)
 *
 * BotGuard challenge/token parsing helpers.
 * Ported to kotlinx.serialization + okio from NewPipe's JavaScriptUtil.kt
 * (org.schabi.newpipe.util.potoken), Licensed Under GPL-3.0.
 */

package com.dezzy.music.playback.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

class PoTokenException(message: String) : Exception(message)

class BadWebViewException(message: String) : Exception(message)

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Parses the raw challenge data obtained from the Create endpoint and returns a JSON object string
 * that can be embedded directly in a JavaScript snippet.
 */
fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = lenientJson.parseToJsonElement(rawChallengeData).jsonArray

    val challengeData: JsonArray =
        if (scrambled.size > 1 && (scrambled[1] as? JsonPrimitive)?.isString == true) {
            val descrambled = descramble(scrambled[1].jsonPrimitive.content)
            lenientJson.parseToJsonElement(descrambled).jsonArray
        } else {
            scrambled[0].jsonArray
        }

    val messageId = challengeData[0].jsonPrimitive.content
    val interpreterHash = challengeData[3].jsonPrimitive.content
    val program = challengeData[4].jsonPrimitive.content
    val globalName = challengeData[5].jsonPrimitive.content
    val clientExperimentsStateBlob = challengeData.getOrNull(7)?.let {
        (it as? JsonPrimitive)?.content
    }

    val interpreterJavascript = (challengeData.getOrNull(1) as? JsonArray)
        ?.firstOrNull { it is JsonPrimitive && it.isString }
        ?.jsonPrimitive?.content
    val trustedResourceUrl = (challengeData.getOrNull(2) as? JsonArray)
        ?.firstOrNull { it is JsonPrimitive && it.isString }
        ?.jsonPrimitive?.content

    return buildJsonObject {
        put("messageId", messageId)
        putJsonObject("interpreterJavascript") {
            put("privateDoNotAccessOrElseSafeScriptWrappedValue", interpreterJavascript)
            put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", trustedResourceUrl)
        }
        put("interpreterHash", interpreterHash)
        put("program", program)
        put("globalName", globalName)
        put("clientExperimentsStateBlob", clientExperimentsStateBlob)
    }.toString()
}

/**
 * Parses the raw integrity token data obtained from the GenerateIT endpoint into a JavaScript
 * `Uint8Array` literal and the token's lifetime in seconds.
 */
fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = lenientJson.parseToJsonElement(rawIntegrityTokenData).jsonArray
    return base64ToU8(integrityTokenData[0].jsonPrimitive.content) to
        integrityTokenData[1].jsonPrimitive.content.toLong()
}

/**
 * Converts a string identifier to a JavaScript `Uint8Array` literal.
 */
fun stringToU8(identifier: String): String {
    return newUint8Array(identifier.toByteArray())
}

/**
 * Takes a poToken encoded as comma-separated unsigned-byte integers (the output of
 * `Uint8Array::toString()` in JavaScript) and converts it to the URL-safe base64 poToken form.
 */
fun u8ToBase64(poToken: String): String {
    return poToken.split(",")
        .map { it.toUByte().toByte() }
        .toByteArray()
        .toByteString()
        .base64()
        .replace('+', '-')
        .replace('/', '_')
}

/** Decodes the scrambled challenge from base64 and adds 97 to each byte. */
private fun descramble(scrambledChallenge: String): String {
    return base64ToByteArray(scrambledChallenge)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()
}

private fun base64ToU8(base64: String): String {
    return newUint8Array(base64ToByteArray(base64))
}

private fun newUint8Array(contents: ByteArray): String {
    return "new Uint8Array([" + contents.joinToString(separator = ",") { it.toUByte().toString() } + "])"
}

/** Decodes a base64 string encoded in the specific base64 representation used by YouTube. */
private fun base64ToByteArray(base64: String): ByteArray {
    val base64Mod = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')

    return (base64Mod.decodeBase64() ?: throw PoTokenException("Cannot base64 decode"))
        .toByteArray()
}
