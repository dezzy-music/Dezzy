/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.musicmap

import com.arturo254.opentune.social.DezzySocial
import com.arturo254.opentune.social.SupabaseAuth
import com.arturo254.opentune.utils.SupabaseConfig
import com.arturo254.opentune.utils.reportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

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
 * Reads live "now playing" presences for the Music Map directly from the app's NATIVE Supabase
 * backend (`music_presence` table) using the public anon key — no third-party proxy.
 *
 * Returns listeners seen in the last [TTL_SECONDS] seconds that are sharing a location. Any failure
 * resolves to an empty list so the screen still renders gracefully.
 */
object MusicMapRepository {
    private const val TTL_SECONDS = 120L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Lee las presencias del mapa por la RPC `dezzy_map_presences`, que solo
     * devuelve a quien eligió visibilidad pública Y está compartiendo ubicación.
     * Si no hay sesión, cae al camino antiguo sobre `music_presence` para que el
     * mapa siga mostrando algo mientras el usuario no ha entrado.
     */
    suspend fun fetchPresences(): List<MapPresence> {
        val fromDezzy = runCatching { fetchFromDezzy() }.getOrNull()
        if (fromDezzy != null) return fromDezzy
        return fetchLegacy()
    }

    private suspend fun fetchFromDezzy(): List<MapPresence>? {
        if (!SupabaseAuth.isLoggedIn) return null
        return DezzySocial.mapPresences().map {
            MapPresence(
                userId = it.userId,
                displayName = it.name,
                videoId = it.videoId,
                title = it.title,
                artist = it.artist,
                artworkUrl = it.artworkUrl,
                latitude = it.lat,
                longitude = it.lng,
            )
        }
    }

    private suspend fun fetchLegacy(): List<MapPresence> = withContext(Dispatchers.IO) {
        try {
            val since = URLEncoder.encode(
                Instant.now().minusSeconds(TTL_SECONDS).toString(), "UTF-8"
            )
            val query = "select=user_id,display_name,title,artist,url,lat,lng" +
                "&lat=not.is.null&lng=not.is.null" +
                "&updated_at=gt.$since" +
                "&order=updated_at.desc&limit=2000"

            val request = Request.Builder()
                .url("${SupabaseConfig.REST}/music_presence?$query")
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string()?.takeIf { it.isNotBlank() }
                    ?: return@withContext emptyList()
                parse(body)
            }
        } catch (e: Exception) {
            reportException(e)
            emptyList()
        }
    }

    private fun parse(body: String): List<MapPresence> {
        val array = JSONArray(body.trim())
        val result = ArrayList<MapPresence>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val userId = obj.optString("user_id")
            if (userId.isBlank()) continue
            val lat = obj.optDouble("lat", Double.NaN)
            val lng = obj.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) continue
            result += MapPresence(
                userId = userId,
                displayName = obj.optString("display_name").ifBlank { "Anónimo" },
                // El camino legacy (sin sesión) no expone el video_id.
                videoId = obj.optString("video_id").ifBlank { null },
                title = obj.optString("title").ifBlank { null },
                artist = obj.optString("artist").ifBlank { null },
                artworkUrl = obj.optString("url").ifBlank { null },
                latitude = lat,
                longitude = lng,
            )
        }
        return result
    }
}
