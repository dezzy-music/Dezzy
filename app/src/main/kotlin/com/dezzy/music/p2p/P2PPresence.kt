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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Escuchando ahora» (estado de usuario NIP-38): un evento reemplazable que caduca solo
 * cuando termina la canción. Nadie guarda mi historial: sólo existe la última canción.
 */
object P2PPresence {

    private const val MIN_INTERVAL_MS = 20_000L

    @Volatile private var lastVideoId: String? = null
    @Volatile private var lastPublishAt = 0L
    @Volatile private var cleared = true
    @Volatile private var lastCell: String? = null

    /**
     * Llamado por el reproductor al cambiar de canción (o al pausar, con null).
     * [location] sólo llega si el usuario comparte su ubicación en el Mapa musical; se publica
     * difuminada a una celda de ~5 km (ver [P2PMap]).
     */
    fun onNowPlaying(song: P2PSong?, location: Pair<Double, Double>? = null) {
        val keys = P2PSocial.keys.value ?: return
        val visible = P2PSocial.listeningVisibility == ListeningVisibility.EVERYONE
        val effective = if (visible) song else null
        val cell = if (effective != null) location?.let { P2PMap.cellOf(it.first, it.second) } else null
        val nowMs = System.currentTimeMillis()
        if (effective == null) {
            if (cleared) return
        } else if (effective.videoId == lastVideoId && cell == lastCell && nowMs - lastPublishAt < MIN_INTERVAL_MS) {
            return
        }
        lastVideoId = effective?.videoId
        lastCell = cell
        lastPublishAt = nowMs
        cleared = effective == null
        P2PSocial.scope.launch {
            val now = NostrEvent.now()
            val tags = buildList {
                add(listOf("d", "music"))
                add(listOf("t", P2PSocial.APP_TAG))
                if (effective != null) {
                    add(listOf("r", effective.url))
                    add(effective.toTag())
                    val remaining = if (effective.durationSec > 0) effective.durationSec.toLong() else 300L
                    add(listOf("expiration", (now + remaining + 30).toString()))
                    if (location != null) addAll(P2PMap.locationTags(location.first, location.second))
                }
            }
            val content = effective?.let { "${it.title} · ${it.artist}" }.orEmpty()
            runCatching { RelayPool.publish(NostrEvent.sign(keys, Kinds.USER_STATUS, tags, content, now)) }
        }
    }

    /** Lo que escuchan ahora mismo las personas que sigo. */
    suspend fun friendsListening(): List<P2PListening> = withContext(Dispatchers.IO) {
        val following = P2PSocial.following.value.toList()
        if (following.isEmpty()) return@withContext emptyList()
        val now = NostrEvent.now()
        val events = following.chunked(250).flatMap { chunk ->
            RelayPool.query(
                listOf(
                    NostrFilter(
                        authors = chunk,
                        kinds = listOf(Kinds.USER_STATUS),
                        tags = mapOf("#d" to listOf("music")),
                        since = now - 2 * 60 * 60,
                    ),
                ),
                timeoutMs = 5000,
            )
        }
        val list = events.groupBy { it.pubkey }.mapNotNull { (_, list) ->
            val latest = list.maxBy { it.createdAt }
            val expires = latest.tag("expiration")?.toLongOrNull()
            if (latest.content.isBlank() || (expires != null && expires < now)) return@mapNotNull null
            P2PListening(latest.pubkey, P2PSong.fromTag(latest.fullTag(P2PSong.TAG)), latest.content, latest.createdAt)
        }.sortedByDescending { it.updatedAt }
        P2PSocial.loadProfiles(list.map { it.pubkey })
        list
    }

    /** Lo que escucha una persona (para su perfil o para el radar «Cerca»). */
    suspend fun listeningOf(pubkeys: List<String>): Map<String, P2PListening> = withContext(Dispatchers.IO) {
        if (pubkeys.isEmpty()) return@withContext emptyMap()
        val now = NostrEvent.now()
        RelayPool.query(
            listOf(NostrFilter(authors = pubkeys, kinds = listOf(Kinds.USER_STATUS), tags = mapOf("#d" to listOf("music")), since = now - 2 * 60 * 60)),
            timeoutMs = 4000,
        ).groupBy { it.pubkey }.mapNotNull { (pubkey, list) ->
            val latest = list.maxBy { it.createdAt }
            val expires = latest.tag("expiration")?.toLongOrNull()
            if (latest.content.isBlank() || (expires != null && expires < now)) null
            else pubkey to P2PListening(pubkey, P2PSong.fromTag(latest.fullTag(P2PSong.TAG)), latest.content, latest.createdAt)
        }.toMap()
    }
}
