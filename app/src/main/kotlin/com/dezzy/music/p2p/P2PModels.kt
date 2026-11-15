/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p

import androidx.compose.runtime.Immutable
import com.dezzy.music.p2p.crypto.Bech32
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Canción adjunta a un mensaje, publicación o estado «escuchando». */
@Immutable
data class P2PSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val durationSec: Int = 0,
) {
    /** Etiqueta propia de Dezzy: `["dezzy_song", id, título, artista, portada, duración]`. */
    fun toTag(): List<String> =
        listOf(TAG, videoId, title, artist, thumbnail.orEmpty(), durationSec.toString())

    val url: String get() = "https://music.youtube.com/watch?v=$videoId"

    fun toJson(): String = buildJsonObject {
        put("id", videoId)
        put("title", title)
        put("artist", artist)
        thumbnail?.let { put("thumb", it) }
        put("dur", durationSec)
    }.toString()

    companion object {
        const val TAG = "dezzy_song"

        fun fromTag(tag: List<String>?): P2PSong? {
            if (tag == null || tag.size < 4 || tag[0] != TAG || tag[1].isBlank()) return null
            return P2PSong(
                videoId = tag[1],
                title = tag[2],
                artist = tag[3],
                thumbnail = tag.getOrNull(4)?.takeIf { it.isNotBlank() },
                durationSec = tag.getOrNull(5)?.toIntOrNull() ?: 0,
            )
        }

        fun fromJson(text: String?): P2PSong? = runCatching {
            val o = Json.parseToJsonElement(text ?: return null).jsonObject
            P2PSong(
                videoId = o["id"]!!.jsonPrimitive.content,
                title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                artist = o["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                thumbnail = o["thumb"]?.jsonPrimitive?.contentOrNull,
                durationSec = o["dur"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }.getOrNull()
    }
}

@Immutable
data class P2PUser(
    val pubkey: String,
    val name: String = "",
    val about: String = "",
    val picture: String? = null,
    val isPrivate: Boolean = false,
) {
    val npub: String get() = Bech32.npub(pubkey)

    /** Huella corta para comprobar a mano que es la persona correcta («dezzy:7f3a·c19e·88b2»). */
    val fingerprint: String
        get() = "dezzy:" + pubkey.take(12).chunked(4).joinToString("·")

    val displayName: String get() = name.ifBlank { "Usuario ${pubkey.take(6)}" }

    val initials: String
        get() = displayName.split(' ').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifBlank { "?" }
}

@Immutable
data class P2PPost(
    val id: String,
    val author: String,
    val createdAt: Long,
    val text: String,
    val song: P2PSong? = null,
    val imageUrl: String? = null,
    /** Llegó cifrada sólo para seguidores aprobados. */
    val isPrivate: Boolean = false,
    val likes: Int = 0,
    val comments: Int = 0,
    val likedByMe: Boolean = false,
)

@Immutable
data class P2PComment(
    val id: String,
    val author: String,
    val createdAt: Long,
    val text: String,
)

@Immutable
data class P2PListening(
    val pubkey: String,
    val song: P2PSong?,
    val text: String,
    val updatedAt: Long,
)

@Immutable
data class P2PConversation(
    val peer: String,
    val lastText: String,
    val lastAt: Long,
    val lastFromMe: Boolean,
    val unread: Int,
)

@Immutable
data class P2PMessage(
    val id: String,
    val fromMe: Boolean,
    val text: String,
    val createdAt: Long,
    val song: P2PSong? = null,
    val juntosLink: String? = null,
)

/** Persona detectada cerca por Bluetooth. */
@Immutable
data class P2PNearbyPerson(
    val pubkey: String,
    val rssi: Int,
    val lastSeen: Long,
)

/** Cómo me relaciono con un perfil. */
enum class FollowState { NONE, REQUESTED, FOLLOWING }

/** Quién puede ver lo que escucho. */
enum class ListeningVisibility { EVERYONE, NOBODY }
