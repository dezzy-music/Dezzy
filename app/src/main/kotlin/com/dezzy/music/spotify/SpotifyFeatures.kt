/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.spotify

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.dezzy.music.constants.EnableSpotifyKey
import com.dezzy.music.constants.SpotifyHomeOnlyKey
import com.dezzy.music.constants.SpotifyLastLikeSyncKey
import com.dezzy.music.constants.SpotifySpDcKey
import com.dezzy.music.constants.SpotifySyncLikesKey
import com.dezzy.music.constants.UseSpotifyHomeKey
import com.dezzy.music.constants.UseSpotifySearchKey
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.security.SessionCrypto
import com.dezzy.music.ui.screens.settings.SpotifyLikeSyncState
import com.dezzy.music.ui.screens.settings.runInitialSpotifyLikeSync
import com.dezzy.music.utils.dataStore
import com.dezzy.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Puente entre los ajustes de Spotify (Ajustes > Spotify) y las pantallas:
 * búsqueda, inicio y sincronización periódica de "Me gusta".
 *
 * Todas las funciones son tolerantes a fallos: sin sesión o sin red
 * devuelven listas vacías / null en lugar de lanzar.
 */
object SpotifyFeatures {

    /** Intervalo mínimo entre sincronizaciones automáticas de "Me gusta". */
    private const val LIKE_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** Spotify activado en ajustes y con sesión (cookie sp_dc) guardada. */
    fun isActive(context: Context): Boolean {
        if (!context.dataStore.get(EnableSpotifyKey, false)) return false
        val spDc = context.dataStore.get(SpotifySpDcKey, "")
        if (spDc.isEmpty()) return false
        return runCatching { SessionCrypto.decrypt(spDc).isNotEmpty() }.getOrDefault(false)
    }

    /**
     * Hay sesión de Spotify guardada (cookie sp_dc), aunque la integración esté apagada en ajustes.
     * Lo usan el mapa y las estadísticas de canciones para saber si pedir iniciar sesión.
     */
    fun hasSession(context: Context): Boolean {
        val spDc = context.dataStore.get(SpotifySpDcKey, "")
        if (spDc.isEmpty()) return false
        return runCatching { SessionCrypto.decrypt(spDc).isNotEmpty() }.getOrDefault(false)
    }

    fun searchEnabled(context: Context): Boolean =
        isActive(context) && context.dataStore.get(UseSpotifySearchKey, false)

    fun homeEnabled(context: Context): Boolean =
        isActive(context) && context.dataStore.get(UseSpotifyHomeKey, false)

    /** "Exclusivo de inicio": solo tiene efecto si el inicio de Spotify está activo. */
    fun homeOnly(context: Context): Boolean =
        homeEnabled(context) && context.dataStore.get(SpotifyHomeOnlyKey, false)

    suspend fun searchTracks(query: String, limit: Int = 10): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            try {
                if (!SpotifyTokenManager.ensureAuthenticated()) return@withContext emptyList()
                Spotify.search(query, limit = limit).getOrThrow()
                    .tracks?.items.orEmpty()
                    .filterNot { it.isLocal }
            } catch (e: Exception) {
                Timber.w(e, "Spotify search failed")
                emptyList()
            }
        }

    suspend fun topTracks(context: Context, database: MusicDatabase, limit: Int = 20): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            try {
                SpotifyProfileCache.getTopTracks(context, database, limit).filterNot { it.isLocal }
            } catch (e: Exception) {
                Timber.w(e, "Spotify top tracks failed")
                emptyList()
            }
        }

    suspend fun recentLikes(limit: Int = 20): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            try {
                if (!SpotifyTokenManager.ensureAuthenticated()) return@withContext emptyList()
                Spotify.likedSongs(limit = limit, offset = 0).getOrThrow()
                    .items.map { it.track }
                    .filterNot { it.isLocal }
            } catch (e: Exception) {
                Timber.w(e, "Spotify liked songs failed")
                emptyList()
            }
        }

    /** Busca el equivalente en YouTube Music de una canción de Spotify. */
    suspend fun resolve(context: Context, database: MusicDatabase, track: SpotifyTrack): MediaMetadata? =
        try {
            SpotifyYouTubeMapper(database, context).mapToYouTube(track)
        } catch (e: Exception) {
            Timber.w(e, "Spotify → YouTube mapping failed")
            null
        }

    /**
     * Sincroniza los "Me gusta" de Spotify si la opción está activa y la última
     * sincronización tiene más de 6 horas. Pensado para lanzarse al abrir la app.
     */
    suspend fun maybeAutoSyncLikes(context: Context, database: MusicDatabase) {
        if (!isActive(context)) return
        if (!context.dataStore.get(SpotifySyncLikesKey, false)) return
        if (SpotifyLikeSyncState.isSyncing.value) return
        val last = context.dataStore.get(SpotifyLastLikeSyncKey, 0L)
        if (System.currentTimeMillis() - last < LIKE_SYNC_INTERVAL_MS) return

        runInitialSpotifyLikeSync(
            database = database,
            context = context,
            onComplete = { count -> Timber.d("Spotify likes auto-sync: %d songs", count) },
            onError = { e -> Timber.w(e, "Spotify likes auto-sync failed") },
        )
        // Se marca aunque falle para no reintentar en bucle en cada arranque.
        withContext(Dispatchers.IO) {
            context.dataStore.edit { it[SpotifyLastLikeSyncKey] = System.currentTimeMillis() }
        }
    }
}
