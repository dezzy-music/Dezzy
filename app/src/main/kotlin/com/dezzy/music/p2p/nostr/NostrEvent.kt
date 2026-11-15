/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p.nostr

import androidx.compose.runtime.Immutable
import com.dezzy.music.p2p.crypto.CryptoUtils
import com.dezzy.music.p2p.crypto.Secp256k1
import com.dezzy.music.p2p.crypto.hexToBytes
import com.dezzy.music.p2p.crypto.isHex64
import com.dezzy.music.p2p.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Par de claves Nostr. La privada sólo vive en memoria y en el almacén cifrado del teléfono. */
class NostrKeys(val privateKey: ByteArray) {
    val publicKey: String = Secp256k1.xOnlyPublicKey(privateKey).toHex()

    companion object {
        fun generate(): NostrKeys = NostrKeys(Secp256k1.generatePrivateKey())
    }
}

/** Evento Nostr (NIP-01). [sig] vacío = «rumor» sin firmar de NIP-59. */
@Immutable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** Primer valor de la etiqueta [name] (p. ej. `tag("d")`). */
    fun tag(name: String): String? = tags.firstOrNull { it.size > 1 && it[0] == name }?.get(1)

    fun tagValues(name: String): List<String> = tags.filter { it.size > 1 && it[0] == name }.map { it[1] }

    fun fullTag(name: String): List<String>? = tags.firstOrNull { it.isNotEmpty() && it[0] == name }

    fun hasValidId(): Boolean = id == computeId(pubkey, createdAt, kind, tags, content)

    fun verify(): Boolean = id.isHex64() && pubkey.isHex64() && sig.length == 128 && hasValidId() &&
        Secp256k1.verify(id.hexToBytes(), pubkey.hexToBytes(), sig.hexToBytes())

    fun toJsonObject(includeSig: Boolean = true): JsonObject = buildJsonObject {
        put("id", id)
        put("pubkey", pubkey)
        put("created_at", createdAt)
        put("kind", kind)
        put("tags", buildJsonArray { tags.forEach { t -> add(JsonArray(t.map { JsonPrimitive(it) })) } })
        put("content", content)
        if (includeSig) put("sig", sig)
    }

    fun toJson(includeSig: Boolean = true): String = toJsonObject(includeSig).toString()

    companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun now(): Long = System.currentTimeMillis() / 1000

        fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
            val serialized = buildString {
                append("[0,\"").append(pubkey).append("\",").append(createdAt).append(',').append(kind).append(",[")
                tags.forEachIndexed { i, tag ->
                    if (i > 0) append(',')
                    append('[')
                    tag.forEachIndexed { j, value ->
                        if (j > 0) append(',')
                        appendJsonString(value)
                    }
                    append(']')
                }
                append("],")
                appendJsonString(content)
                append(']')
            }
            return CryptoUtils.sha256(serialized.toByteArray(Charsets.UTF_8)).toHex()
        }

        /** Escapa como `JSON.stringify` (lo que usan relés y clientes para calcular el id). */
        private fun StringBuilder.appendJsonString(value: String) {
            append('"')
            for (c in value) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }

        fun sign(keys: NostrKeys, kind: Int, tags: List<List<String>>, content: String, createdAt: Long = now()): NostrEvent {
            val id = computeId(keys.publicKey, createdAt, kind, tags, content)
            val sig = Secp256k1.sign(id.hexToBytes(), keys.privateKey).toHex()
            return NostrEvent(id, keys.publicKey, createdAt, kind, tags, content, sig)
        }

        /** Rumor de NIP-59: evento con id pero sin firma (no se puede reenviar como prueba). */
        fun rumor(pubkey: String, kind: Int, tags: List<List<String>>, content: String, createdAt: Long = now()): NostrEvent =
            NostrEvent(computeId(pubkey, createdAt, kind, tags, content), pubkey, createdAt, kind, tags, content, "")

        fun fromJson(obj: JsonObject): NostrEvent? = runCatching {
            NostrEvent(
                id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                pubkey = obj["pubkey"]!!.jsonPrimitive.content,
                createdAt = obj["created_at"]!!.jsonPrimitive.longOrNull ?: 0L,
                kind = obj["kind"]!!.jsonPrimitive.content.toInt(),
                tags = obj["tags"]?.jsonArray?.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }.orEmpty(),
                content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                sig = obj["sig"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }.getOrNull()

        fun fromJson(text: String): NostrEvent? =
            runCatching { fromJson(json.parseToJsonElement(text).jsonObject) }.getOrNull()
    }
}

/** Filtro de suscripción (NIP-01). Las etiquetas van como `"#p" to listOf(...)`. */
data class NostrFilter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val tags: Map<String, List<String>> = emptyMap(),
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val search: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        ids?.let { v -> put("ids", JsonArray(v.map { JsonPrimitive(it) })) }
        authors?.let { v -> put("authors", JsonArray(v.map { JsonPrimitive(it) })) }
        kinds?.let { v -> put("kinds", JsonArray(v.map { JsonPrimitive(it) })) }
        tags.forEach { (key, values) -> put(key, JsonArray(values.map { JsonPrimitive(it) })) }
        since?.let { put("since", it) }
        until?.let { put("until", it) }
        limit?.let { put("limit", it) }
        search?.let { put("search", it) }
    }
}

/** Tipos de evento que usa Dezzy (los estándar de Nostr + etiquetas propias). */
object Kinds {
    const val METADATA = 0
    const val TEXT_NOTE = 1
    const val CONTACTS = 3
    const val DELETION = 5
    const val REACTION = 7
    const val SEAL = 13
    const val CHAT_MESSAGE = 14
    const val GIFT_WRAP = 1059
    const val DM_RELAYS = 10050
    const val USER_STATUS = 30315
    const val BLOSSOM_AUTH = 24242
}
