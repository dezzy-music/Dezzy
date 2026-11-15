/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.musicmap

import com.dezzy.music.p2p.P2PMap
import com.dezzy.music.p2p.P2PSocial
import com.dezzy.music.utils.reportException

/** A single listener shown on the Music Map. */
data class MapPresence(
    val userId: String,
    val displayName: String,
    val videoId: String?,
    val title: String?,
    val artist: String?,
    val artworkUrl: String?,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Presencias del Mapa musical leídas de la red P2P (Nostr), sin Supabase ni servidor propio.
 *
 * Cada persona publica su estado «escuchando ahora» con la celda geohash de ~5 km donde está
 * (ver [P2PMap]); aquí sólo se leen esos estados de los relés públicos. Cualquier fallo devuelve
 * una lista vacía para que el mapa siga pintándose.
 */
object MusicMapRepository {

    suspend fun fetchPresences(): List<MapPresence> = try {
        P2PMap.listeners().map { listener ->
            val user = P2PSocial.user(listener.pubkey)
            val song = listener.song
            MapPresence(
                userId = listener.pubkey,
                displayName = user.displayName,
                videoId = song?.videoId,
                title = song?.title ?: listener.status.substringBefore(" · ").ifBlank { null },
                artist = song?.artist?.ifBlank { null },
                artworkUrl = song?.thumbnail,
                latitude = listener.latitude,
                longitude = listener.longitude,
            )
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        reportException(e)
        emptyList()
    }
}
