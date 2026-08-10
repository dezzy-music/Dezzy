/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.social

import android.os.Build
import com.arturo254.opentune.constants.SocialShareActivityKey
import com.arturo254.opentune.constants.SocialVisibilityKey
import com.arturo254.opentune.models.MediaMetadata
import com.arturo254.opentune.utils.dataStore
import com.arturo254.opentune.utils.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.content.Context

/**
 * Publica al backend lo que el usuario está escuchando, para el panel de
 * actividad de amigos y el Music Map.
 *
 * Reemplaza a `OmegaMathIntegration`, que escribía en `music_presence` usando la
 * clave anónima y un `user_id` enviado por el cliente: cualquiera podía publicar
 * presencia haciéndose pasar por otro. Aquí el servidor deriva la identidad del
 * JWT (`auth.uid()`), así que suplantar es imposible.
 *
 * Regula la frecuencia por dos motivos: no desperdiciar batería/datos con una
 * petición por evento del reproductor, y no castigar la base de datos con una
 * escritura por segundo por usuario activo.
 */
object PresencePublisher {

    /** Mínimo entre pings del mismo estado. Un cambio de canción ignora la espera. */
    private const val THROTTLE_MS = 20_000L

    /** La sincronización entre dispositivos se guarda con menos frecuencia. */
    private const val PLAYBACK_STATE_THROTTLE_MS = 30_000L

    @Volatile private var lastSentAt = 0L
    @Volatile private var lastVideoId: String? = null
    @Volatile private var lastIsPlaying: Boolean? = null
    @Volatile private var lastStateSavedAt = 0L
    /** Evita contar la misma canción varias veces en el top del perfil. */
    @Volatile private var lastCountedPlay: String? = null

    private var job: Job? = null

    private val deviceName: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(40)

    /**
     * Notifica el estado actual. Es seguro llamarla en cada evento del reproductor:
     * decide sola si toca enviar algo.
     */
    fun publish(
        context: Context,
        scope: CoroutineScope,
        metadata: MediaMetadata?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        lat: Double? = null,
        lng: Double? = null,
    ) {
        val now = System.currentTimeMillis()
        val songChanged = metadata?.id != lastVideoId
        val playStateChanged = isPlaying != lastIsPlaying
        val throttleExpired = now - lastSentAt >= THROTTLE_MS

        if (!songChanged && !playStateChanged && !throttleExpired) return

        lastVideoId = metadata?.id
        lastIsPlaying = isPlaying
        lastSentAt = now

        job?.cancel()
        job = scope.launch {
            if (!SupabaseAuth.isLoggedIn) return@launch
            // El usuario puede apagar por completo el compartir actividad.
            if (!context.dataStore.get(SocialShareActivityKey, true)) {
                clear(context, scope)
                return@launch
            }

            val visibility = when (context.dataStore.get(SocialVisibilityKey, "friends")) {
                "public" -> ActivityVisibility.Public
                "private" -> ActivityVisibility.Private
                else -> ActivityVisibility.Friends
            }

            DezzySocial.updatePresence(
                videoId = metadata?.id,
                title = metadata?.title,
                artist = metadata?.artists?.joinToString(", ") { it.name },
                album = metadata?.album?.title,
                artworkUrl = metadata?.thumbnailUrl,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                lat = lat,
                lng = lng,
                // Solo se manda cuando el usuario la cambió; en los pings normales
                // va null para no pisar su preferencia guardada en el servidor.
                visibility = visibility,
                deviceName = deviceName,
            )

            // Estadísticas del perfil musical: una vez por canción.
            if (songChanged && metadata != null && metadata.id != lastCountedPlay) {
                lastCountedPlay = metadata.id
                DezzySocial.trackPlay(
                    entityType = "song",
                    entityId = metadata.id,
                    entityName = metadata.title,
                    artworkUrl = metadata.thumbnailUrl,
                )
                metadata.artists.firstOrNull()?.let { artist ->
                    if (!artist.id.isNullOrBlank()) {
                        DezzySocial.trackPlay(
                            entityType = "artist",
                            entityId = artist.id!!,
                            entityName = artist.name,
                        )
                    }
                }
            }

            // Sincronización entre dispositivos (privada, distinta de la presencia).
            if (metadata != null && now - lastStateSavedAt >= PLAYBACK_STATE_THROTTLE_MS) {
                lastStateSavedAt = now
                DezzySocial.savePlaybackState(
                    videoId = metadata.id,
                    title = metadata.title,
                    artist = metadata.artists.joinToString(", ") { it.name },
                    artworkUrl = metadata.thumbnailUrl,
                    positionMs = positionMs,
                    deviceName = deviceName,
                )
            }
        }
    }

    /** Borra la presencia (al detener la reproducción o cerrar sesión). */
    fun clear(context: Context, scope: CoroutineScope) {
        lastVideoId = null
        lastIsPlaying = null
        scope.launch {
            if (SupabaseAuth.isLoggedIn) DezzySocial.clearPresence()
        }
    }
}
