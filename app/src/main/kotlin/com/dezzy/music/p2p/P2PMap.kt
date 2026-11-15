/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p

import com.dezzy.music.p2p.nostr.Kinds
import com.dezzy.music.p2p.nostr.NostrEvent
import com.dezzy.music.p2p.nostr.NostrFilter
import com.dezzy.music.p2p.nostr.RelayPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Alguien escuchando música en el mapa (posición ya difuminada a su celda). */
data class P2PMapListener(
    val pubkey: String,
    val song: P2PSong?,
    val status: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAt: Long,
)

/**
 * Mapa musical sin servidor: la posición viaja dentro del propio estado «escuchando ahora»
 * (NIP-38, kind 30315 `d=music`) como geohash de 5 caracteres (celda de ~5 × 5 km), con las
 * etiquetas `g` de 3, 4 y 5 caracteres (como NIP-52) y `t=dezzy_map` para poder buscarlo.
 *
 * - Nunca sale la ubicación exacta: el teléfono redondea a la celda ANTES de firmar.
 * - Al pausar o al dejar de compartir, el estado se reemplaza por uno vacío y sin `g`.
 * - El evento caduca solo (`expiration`) cuando termina la canción.
 */
object P2PMap {

    const val MAP_TAG = "dezzy_map"
    const val PRECISION = 5

    /** Etiquetas que se añaden al estado «escuchando» cuando se comparte la ubicación. */
    fun locationTags(lat: Double, lng: Double): List<List<String>> {
        val hash = Geohash.encode(lat, lng, PRECISION)
        return listOf(
            listOf("t", MAP_TAG),
            listOf("g", hash),
            listOf("g", hash.take(4)),
            listOf("g", hash.take(3)),
        )
    }

    /** Celda pública que se publicaría para esta posición (para saber si ha cambiado). */
    fun cellOf(lat: Double, lng: Double): String = Geohash.encode(lat, lng, PRECISION)

    /** Quién está escuchando ahora mismo en el mapa (últimas 2 h, sólo eventos vigentes). */
    suspend fun listeners(limit: Int = 1000): List<P2PMapListener> = withContext(Dispatchers.IO) {
        val now = NostrEvent.now()
        val events = RelayPool.query(
            listOf(
                NostrFilter(
                    kinds = listOf(Kinds.USER_STATUS),
                    tags = mapOf("#t" to listOf(MAP_TAG)),
                    since = now - 2 * 60 * 60,
                    limit = limit,
                ),
            ),
            timeoutMs = 5000,
        )
        val list = events
            .filter { it.tag("d") == "music" }
            .groupBy { it.pubkey }
            .mapNotNull { (pubkey, list) ->
                // Sólo cuenta el último estado: si el último ya no tiene celda, no sale.
                val latest = list.maxBy { it.createdAt }
                val expires = latest.tag("expiration")?.toLongOrNull()
                if (latest.content.isBlank() || (expires != null && expires < now)) return@mapNotNull null
                val cell = latest.tagValues("g").maxByOrNull { it.length }
                    ?.takeIf { it.length >= 3 }
                    ?: return@mapNotNull null
                val (lat, lng) = Geohash.decodeCenter(cell) ?: return@mapNotNull null
                P2PMapListener(
                    pubkey = pubkey,
                    song = P2PSong.fromTag(latest.fullTag(P2PSong.TAG)),
                    status = latest.content,
                    latitude = lat,
                    longitude = lng,
                    updatedAt = latest.createdAt,
                )
            }
            .sortedByDescending { it.updatedAt }
        if (list.isNotEmpty()) runCatching { P2PSocial.loadProfiles(list.map { it.pubkey }) }
        list
    }
}

/** Geohash estándar (base32 sin a, i, l, o). */
internal object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lng: Double, precision: Int): String {
        var latMin = -90.0
        var latMax = 90.0
        var lngMin = -180.0
        var lngMax = 180.0
        val out = StringBuilder(precision)
        var even = true
        var bit = 0
        var ch = 0
        while (out.length < precision) {
            if (even) {
                val mid = (lngMin + lngMax) / 2
                if (lng >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lngMin = mid
                } else {
                    lngMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2
                if (lat >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    latMin = mid
                } else {
                    latMax = mid
                }
            }
            even = !even
            if (bit < 4) {
                bit++
            } else {
                out.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }
        return out.toString()
    }

    /** Centro de la celda, o null si el geohash no es válido. */
    fun decodeCenter(hash: String): Pair<Double, Double>? {
        var latMin = -90.0
        var latMax = 90.0
        var lngMin = -180.0
        var lngMax = 180.0
        var even = true
        for (c in hash.lowercase()) {
            val value = BASE32.indexOf(c)
            if (value < 0) return null
            for (i in 4 downTo 0) {
                val on = ((value shr i) and 1) == 1
                if (even) {
                    val mid = (lngMin + lngMax) / 2
                    if (on) lngMin = mid else lngMax = mid
                } else {
                    val mid = (latMin + latMax) / 2
                    if (on) latMin = mid else latMax = mid
                }
                even = !even
            }
        }
        return (latMin + latMax) / 2 to (lngMin + lngMax) / 2
    }
}
